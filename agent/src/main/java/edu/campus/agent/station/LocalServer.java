package edu.campus.agent.station;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.campus.agent.net.AgentVersion;
import edu.campus.agent.net.Messages;
import edu.campus.agent.print.CapabilityCache;
import edu.campus.agent.print.PdfBoxPrintStrategy;
import edu.campus.agent.print.PrintTicket;
import edu.campus.agent.print.PrintEngine;
import edu.campus.agent.print.PrintJob;
import edu.campus.agent.print.PrinterDiscovery;
import edu.campus.agent.print.TestPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * The Station's own little web server, reachable only from this PC
 * (127.0.0.1). It serves the app's screens, answers questions only this PC
 * can answer (which printers are installed, test prints, start with
 * Windows), and passes counter actions on to the XeoGo server.
 *
 * Safety: it listens on 127.0.0.1 only, accepts only requests addressed to
 * 127.0.0.1/localhost (so other websites cannot reach it through DNS tricks),
 * and every action needs the random token the app window was opened with.
 * A request sent by a web page of another site (it carries that site's
 * Origin) is refused outright.
 *
 * Counter actions go to the server with this PC's counter sign-in (a token
 * the server gave for the counter password), not with the password itself.
 */
public class LocalServer {

    /** -Dcampusprint.station.port=... only for tests next to an installed Station. */
    public static final int PORT = Integer.getInteger("campusprint.station.port", 47800);

    private static final Logger log = LoggerFactory.getLogger(LocalServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> HOSTS = Set.of("127.0.0.1:" + PORT, "localhost:" + PORT);
    private static final Set<String> ORIGINS = Set.of("http://127.0.0.1:" + PORT, "http://localhost:" + PORT);
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8", "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8", "png", "image/png", "svg", "image/svg+xml",
            "ico", "image/x-icon", "json", "application/json");

    private final HttpServer http;
    private final StationConfig cfg;
    private final AgentRunner runner;
    private final Runnable openWindow;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    /** Until when the counter password is sent with every action: the server is an older one without sign-ins. */
    private volatile long passwordOnlyUntil;

    /** Takes the port. Fails if the Station is already running (then that copy is asked to show itself). */
    public static HttpServer bind() throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
    }

    /** A second start (double-click on the icon): ask the running Station to open its window. */
    public static boolean askRunningAppToShow() {
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + PORT + "/local/show"))
                    .timeout(Duration.ofSeconds(5)).POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    public LocalServer(HttpServer http, StationConfig cfg, AgentRunner runner, Runnable openWindow) {
        this.http = http;
        this.cfg = cfg;
        this.runner = runner;
        this.openWindow = openWindow;
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        // Automated tests may choose the token (same Windows user only); normally it is random.
        String fixed = System.getenv("CAMPUSPRINT_STATION_TOKEN");
        this.token = fixed != null && fixed.length() >= 16 ? fixed : HexFormat.of().formatHex(b);
    }

    public void start() {
        http.createContext("/", this::handle);
        http.setExecutor(Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "station-web");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        log.info("Station screens on http://127.0.0.1:{}/", PORT);
    }

    public void stop() {
        http.stop(0);
    }

    /** The address the app window opens (carries the token). */
    public String url() {
        return "http://127.0.0.1:" + PORT + "/?t=" + token;
    }

    // ------------------------------------------------------------ routing

    private void handle(HttpExchange ex) throws IOException {
        try {
            String host = ex.getRequestHeaders().getFirst("Host");
            if (host == null || !HOSTS.contains(host.toLowerCase())) {
                send(ex, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String origin = ex.getRequestHeaders().getFirst("Origin");
            if (origin != null && !ORIGINS.contains(origin.toLowerCase())) {
                // A page of some other website is calling this PC: never.
                send(ex, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            if (path.equals("/local/show") && ex.getRequestMethod().equals("POST")) {
                openWindow.run();
                json(ex, 200, Map.of("ok", true));
                return;
            }
            if (path.startsWith("/local/") || path.startsWith("/api/")) {
                if (!tokenOk(ex.getRequestHeaders().getFirst("X-Station-Token"))) {
                    json(ex, 401, Map.of("message", "Please open XeoGo from its icon."));
                    return;
                }
                if (path.startsWith("/api/")) proxy(ex);
                else local(ex, path);
                return;
            }
            staticFile(ex, path);
        } catch (UserProblem e) {
            json(ex, 400, Map.of("message", e.getMessage()));
        } catch (Exception e) {
            log.error("Station request {} failed", ex.getRequestURI().getPath(), e);
            json(ex, 500, Map.of("message", "Something went wrong: " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private boolean tokenOk(String given) {
        return given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ local actions

    private void local(HttpExchange ex, String path) throws Exception {
        String method = ex.getRequestMethod();
        switch (method + " " + path) {
            case "GET /local/state" -> json(ex, 200, state());
            case "GET /local/windows-printers" -> json(ex, 200, WindowsPrinters.scan());
            case "POST /local/connect" -> json(ex, 200, connect(body(ex)));
            case "POST /local/test-print" -> json(ex, 200, testPrint(body(ex)));
            case "POST /local/autostart" -> {
                boolean on = body(ex).path("enabled").asBoolean();
                if (!DesktopShell.setAutostart(on)) throw new UserProblem("Could not change this setting.");
                json(ex, 200, state());
            }
            case "POST /local/password" -> json(ex, 200, savePassword(body(ex)));
            case "POST /local/refresh" -> {
                runner.refreshNow();
                json(ex, 200, Map.of("ok", true));
            }
            case "POST /local/rescan" -> {
                runner.rescanNow();
                json(ex, 200, Map.of("ok", true));
            }
            case "POST /local/word-check" -> {
                runner.checkWordNow();
                json(ex, 200, Map.of("ok", true));
            }
            case "POST /local/disconnect" -> json(ex, 200, disconnect());
            case "POST /local/open-logs" -> {
                DesktopShell.openFolder(StationConfig.dir().resolve("logs"));
                json(ex, 200, Map.of("ok", true));
            }
            default -> json(ex, 404, Map.of("message", "Unknown action"));
        }
    }

    private Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", AgentVersion.VALUE);
        m.put("configured", cfg.isConfigured());
        m.put("backendUrl", cfg.backendUrl);
        m.put("defaultBackendUrl", StationConfig.defaultBackendUrl());
        m.put("pcName", cfg.pcName.isBlank() ? computerName() : cfg.pcName);
        m.put("agentId", cfg.agentId);
        m.put("hasPassword", !cfg.counterPassword.isBlank());
        m.put("autostartSupported", DesktopShell.autostartSupported());
        m.put("autostart", DesktopShell.autostartSupported() && DesktopShell.autostartOn());
        m.put("dataFolder", StationConfig.dir().toString());
        m.put("printing", runner.status());
        return m;
    }

    /** Setup step 1: check the server and password, register this PC, start printing. */
    private synchronized Map<String, Object> connect(JsonNode b) throws Exception {
        String url = normaliseUrl(b.path("backendUrl").asText(""));
        String password = b.path("password").asText("");
        String pcName = b.path("pcName").asText("").trim();
        if (pcName.isEmpty()) pcName = computerName();
        if (password.isBlank()) throw new UserProblem("Type the counter password.");

        HttpResponse<String> shop = call("GET", url + "/api/v1/shop", null, null);
        if (shop == null || shop.statusCode() != 200) {
            throw new UserProblem("Cannot reach the XeoGo server at " + url
                    + ". Check the address and the internet connection.");
        }
        HttpResponse<String> summary = call("GET", url + "/api/v1/counter/summary", password, null);
        if (summary == null) throw new UserProblem("The server did not answer. Try again.");
        if (summary.statusCode() == 401) throw new UserProblem("Wrong counter password.");
        if (summary.statusCode() == 429) throw new UserProblem("Too many wrong passwords. Wait 10 minutes.");
        if (summary.statusCode() != 200) throw new UserProblem(messageOf(summary));

        boolean sameServer = cfg.isConfigured() && cfg.backendUrl.equalsIgnoreCase(url);
        if (!sameServer) {
            HttpResponse<String> enrolled = call("POST", url + "/api/v1/counter/pcs", password,
                    JSON.writeValueAsString(Map.of("name", pcName)));
            if (enrolled == null || enrolled.statusCode() != 200) {
                throw new UserProblem("The server did not register this PC: " + messageOf(enrolled));
            }
            JsonNode e = JSON.readTree(enrolled.body());
            cfg.agentId = e.path("agentId").asText();
            cfg.agentSecret = e.path("agentSecret").asText();
        }
        cfg.backendUrl = url;
        cfg.pcName = pcName;
        cfg.counterPassword = password;
        cfg.counterSession = "";            // sign in afresh with this password
        cfg.save();
        runner.start(cfg);
        if (!sameServer && DesktopShell.autostartSupported() && !DesktopShell.autostartOn()) {
            DesktopShell.setAutostart(true);          // a Xerox PC should always be printing
        }
        log.info("Connected to {} as PC \"{}\"", url, pcName);
        return state();
    }

    private Map<String, Object> savePassword(JsonNode b) throws Exception {
        String password = b.path("password").asText("");
        HttpResponse<String> r = call("GET", cfg.backendUrl + "/api/v1/counter/summary", password, null);
        if (r == null) throw new UserProblem("The server did not answer. Try again.");
        if (r.statusCode() == 401) throw new UserProblem("That password is not right.");
        if (r.statusCode() != 200) throw new UserProblem(messageOf(r));
        cfg.counterPassword = password;
        cfg.counterSession = "";
        cfg.save();
        return state();
    }

    /** Takes this PC out of use: the server forgets it (and its printers), the app forgets the server. */
    private synchronized Map<String, Object> disconnect() throws Exception {
        if (cfg.isConfigured()) {
            call("DELETE", cfg.backendUrl + "/api/v1/counter/pcs/" + cfg.agentId, cfg.counterPassword, null);
        }
        runner.stop();
        cfg.agentId = "";
        cfg.agentSecret = "";
        cfg.counterPassword = "";
        cfg.counterSession = "";
        cfg.save();
        log.warn("This PC was disconnected from XeoGo");
        return state();
    }

    /**
     * "Test print" buttons: the Step 1 test page, with the label "Order TEST1",
     * on one printer; "two-sided" prints it on both sides of one sheet.
     */
    private Map<String, Object> testPrint(JsonNode b) throws Exception {
        String printer = b.path("printer").asText("");
        boolean color = b.path("color").asBoolean(false);
        boolean twoSided = b.path("twoSided").asBoolean(false);
        if (PrinterDiscovery.find(printer).isEmpty()) throw new UserProblem("Windows has no printer called " + printer);
        Path file = Files.createTempFile("campusprint-test", ".pdf");
        try {
            TestPage.write(file, twoSided ? 2 : 1);
            Messages.JobSettings plain = Messages.JobSettings.plain(1, color, null);
            Messages.JobSettings settings = !twoSided ? plain : new Messages.JobSettings(1, color, null, "LONG_EDGE",
                    "A4", "AUTO", "FIT", 100, 1, 5, 0, true, true, null, null, null, null, "STANDARD");
            PrintJob job = new PrintJob(UUID.randomUUID().toString(), printer, "PDF", "TEST1",
                    "XeoGo test page", 1, 1, settings, new Messages.Paper("A4", 210, 297), null, true);
            new PrintEngine(new PdfBoxPrintStrategy(), true, 0, new PrintTicket(StationConfig.dir().resolve("work")),
                    new CapabilityCache()).print(file, job);
        } catch (PrintEngine.CannotPrint e) {
            throw new UserProblem(e.getMessage());
        } finally {
            Files.deleteIfExists(file);
        }
        log.info("Test page sent to \"{}\" ({}{})", printer, color ? "colour" : "B/W", twoSided ? ", two-sided" : "");
        if (twoSided) {
            return Map.of("ok", true, "message",
                    "Sent. Check the paper: ONE sheet, \"Side 1\" on the front and \"Side 2\" on the back.");
        }
        return Map.of("ok", true, "message", color
                ? "Sent. Check the paper: the red box must be RED, and \"Order TEST1\" in the corner."
                : "Sent. Check the paper: the red box must be GREY, and \"Order TEST1\" in the corner.");
    }

    // ------------------------------------------------------------ counter actions -> server

    private void proxy(HttpExchange ex) throws Exception {
        if (cfg.backendUrl.isBlank()) throw new UserProblem("Connect this PC to the XeoGo server first.");
        String query = ex.getRequestURI().getRawQuery();
        String target = cfg.backendUrl + ex.getRequestURI().getRawPath() + (query == null ? "" : "?" + query);
        byte[] body = ex.getRequestBody().readAllBytes();
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        HttpResponse<byte[]> r;
        try {
            r = forward(ex.getRequestMethod(), target, type, body, counterSession());
            if (r.statusCode() == 401 && !cfg.counterSession.isBlank()) {
                // The sign-in ran out (30 days), or the password was changed on the server: sign in once more.
                forgetSession();
                r = forward(ex.getRequestMethod(), target, type, body, counterSession());
            }
        } catch (IOException e) {
            json(ex, 502, Map.of("message", "Cannot reach the XeoGo server. Check the internet connection."));
            return;
        }
        send(ex, r.statusCode(), r.headers().firstValue("Content-Type").orElse("application/json"), r.body());
    }

    private HttpResponse<byte[]> forward(String method, String target, String contentType, byte[] body, String session)
            throws IOException, InterruptedException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofSeconds(30))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (contentType != null) rb.header("Content-Type", contentType);
        if (session != null) {
            rb.header("X-Counter-Session", session);
        } else if (!cfg.counterPassword.isBlank()) {
            rb.header("X-Counter-Password", cfg.counterPassword);
        }
        return client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /**
     * This PC's counter sign-in, fetched with the counter password when there
     * is none yet. Null: use the password itself (the server is an older one,
     * or would not sign us in just now: its own answer then reaches the screen).
     */
    private synchronized String counterSession() {
        if (!cfg.counterSession.isBlank()) return cfg.counterSession;
        if (cfg.counterPassword.isBlank() || System.currentTimeMillis() < passwordOnlyUntil) return null;
        HttpResponse<String> r = call("POST", cfg.backendUrl + "/api/v1/counter/session", cfg.counterPassword, null);
        if (r == null) return null;
        if (r.statusCode() == 404 || r.statusCode() == 405) {
            passwordOnlyUntil = System.currentTimeMillis() + 10 * 60_000L;       // an older server: ask again later
            return null;
        }
        if (r.statusCode() != 200) return null;
        try {
            String token = JSON.readTree(r.body()).path("token").asText("");
            if (token.isBlank()) return null;
            cfg.counterSession = token;
            cfg.save();
            return token;
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized void forgetSession() {
        cfg.counterSession = "";
        try {
            cfg.save();
        } catch (IOException e) {
            log.debug("Could not save the settings: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------ static screens

    private void staticFile(HttpExchange ex, String path) throws IOException {
        if (path.equals("/")) path = "/index.html";
        if (path.contains("..") || !path.matches("/[A-Za-z0-9._/-]+")) {
            send(ex, 404, "text/plain", new byte[0]);
            return;
        }
        try (InputStream in = LocalServer.class.getResourceAsStream("/station-ui" + path)) {
            if (in == null) {
                send(ex, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String ext = path.substring(path.lastIndexOf('.') + 1);
            ex.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self' data: blob:; "
                    + "style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'");
            send(ex, 200, TYPES.getOrDefault(ext, "application/octet-stream"), in.readAllBytes());
        }
    }

    // ------------------------------------------------------------ helpers

    private HttpResponse<String> call(String method, String url, String password, String jsonBody) {
        try {
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .method(method, jsonBody == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(jsonBody));
            if (jsonBody != null) rb.header("Content-Type", "application/json");
            if (password != null) rb.header("X-Counter-Password", password);
            return client.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IllegalArgumentException e) {
            throw new UserProblem("That server address is not valid.");
        } catch (Exception e) {
            return null;
        }
    }

    private static String messageOf(HttpResponse<String> r) {
        if (r == null) return "no answer from the server.";
        try {
            String m = JSON.readTree(r.body()).path("message").asText("");
            if (!m.isBlank()) return m;
        } catch (Exception ignored) {
        }
        return "the server answered " + r.statusCode() + ".";
    }

    static String normaliseUrl(String raw) {
        String url = raw.trim().replaceAll("/+$", "");
        if (url.isEmpty()) throw new UserProblem("Type the XeoGo server address.");
        if (!url.matches("(?i)https?://.*")) {
            boolean local = url.startsWith("localhost") || url.startsWith("127.") || url.matches("(10|192\\.168)\\..*");
            url = (local ? "http://" : "https://") + url;
        }
        boolean local = url.matches("(?i)http://(localhost|127\\.0\\.0\\.1|10\\.|192\\.168\\.|172\\.(1[6-9]|2\\d|3[01])\\.).*");
        if (!url.toLowerCase().startsWith("https://") && !local) {
            throw new UserProblem("The server address must start with https:// (http:// only works on the same network).");
        }
        return url;
    }

    private static String computerName() {
        String n = System.getenv("COMPUTERNAME");
        return n == null || n.isBlank() ? "Xerox PC" : n;
    }

    private static JsonNode body(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readAllBytes();
        return b.length == 0 ? JSON.createObjectNode() : JSON.readTree(b);
    }

    private static void json(HttpExchange ex, int status, Object value) throws IOException {
        send(ex, status, "application/json", JSON.writeValueAsBytes(value));
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) ex.getResponseBody().write(body);
    }

    /** A problem the person at the PC can fix; the message is shown as it is. */
    static class UserProblem extends RuntimeException {
        UserProblem(String message) {
            super(message);
        }
    }

}
