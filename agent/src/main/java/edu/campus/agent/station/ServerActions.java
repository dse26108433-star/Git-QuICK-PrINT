package edu.campus.agent.station;

import com.fasterxml.jackson.databind.JsonNode;
import edu.campus.agent.server.LocalStack;
import edu.campus.agent.server.ServerSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the Station's screens can do with "the server on this PC": set it up,
 * show how it is doing, change how students pay, give the PC its internet
 * address, and look after the backups. (The work itself is in LocalStack.)
 *
 * Everything typed here is checked before it is saved: a wrong UPI ID or an
 * empty Razorpay key would stop the print service from starting, and nobody
 * at the counter could tell why.
 */
final class ServerActions {

    private static final Logger log = LoggerFactory.getLogger(ServerActions.class);
    private static final Pattern VPA = Pattern.compile("[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9.-]{1,64}");
    private static final Pattern TUNNEL_TOKEN = Pattern.compile("eyJ[A-Za-z0-9_=-]{60,}");
    private static final Pattern WEB_ADDRESS = Pattern.compile("(?i)https://[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:\\d{1,5})?");

    private final LocalStack stack;
    private final ServerSettings server;

    ServerActions(LocalStack stack, ServerSettings server) {
        this.stack = stack;
        this.server = server;
    }

    /** This copy of the Station came with the server programs. */
    boolean available() {
        return stack != null;
    }

    boolean enabled() {
        return stack != null && server.enabled;
    }

    String localUrl() {
        return server.localUrl();
    }

    String counterPassword() {
        return server.counterPassword;
    }

    /** No database has been made on this PC yet (it has never been the server). */
    boolean fresh() {
        return stack == null || stack.isFresh();
    }

    // ------------------------------------------------------------------ what the screens show

    /** How the server is doing and how it is set up. Secrets are never sent to the screen, only "is set". */
    Map<String, Object> view() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available());
        m.put("enabled", enabled());
        if (!available()) return m;
        m.put("fresh", stack.isFresh());
        m.put("status", stack.status());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("paymentMode", server.paymentMode);
        s.put("upiId", server.upiId);
        s.put("upiName", server.upiName);
        s.put("upiMerchantCode", server.upiMerchantCode);
        // the XeoGo Pay Verifier app on the shop's phone needs this one typed in: shown on purpose
        s.put("upiAlertToken", server.upiAlertToken);
        s.put("upiSmsSenders", server.upiSmsSenders);
        s.put("razorpayKeyId", server.razorpayKeyId);
        s.put("hasRazorpaySecret", !server.razorpayKeySecret.isBlank());
        s.put("publicUrl", server.publicUrl);
        s.put("hasTunnelToken", server.hasTunnel());
        s.put("cloudUrl", server.cloudUrl);
        s.put("hasCloudKey", !server.cloudKey.isBlank());
        s.put("webOrigins", server.webOrigins);
        m.put("settings", s);
        return m;
    }

    // ------------------------------------------------------------------ first setup

    /**
     * "This PC is the server." Sets the staff password, makes the secrets,
     * and starts the database and the print service (the screen then waits
     * for "ready"). from: "empty" (a new shop), "cloud-database" (bring the
     * data over from the Supabase database the cloud server used), or
     * "cloud-backup" (a PC that replaces another one: its latest backup).
     */
    synchronized void setup(JsonNode b) {
        requireAvailable();
        if (server.damaged) {
            throw new LocalServer.UserProblem("The server's settings file on this PC (server.json) cannot be read. "
                    + "Move it away from " + ServerSettings.file().getParent() + " and try again.");
        }
        String password = b.path("password").asText("");
        if (password.length() < 8) throw new LocalServer.UserProblem("Choose a counter password of 8 or more characters.");
        String from = b.path("from").asText("empty");
        try {
            if (!from.equals("empty")) {
                if (!stack.isFresh()) {
                    throw new LocalServer.UserProblem("This PC already has a XeoGo database, so nothing is brought in. "
                            + "Choose \"Start with an empty shop\" to keep using it.");
                }
                if (from.equals("cloud-database")) {
                    Path copy = stack.backups().copyFromCloudDatabase(b.path("dbAddress").asText(""),
                            b.path("dbUser").asText(""), b.path("dbPassword").asText(""));
                    stack.backups().restoreAtNextStart(copy);
                } else if (from.equals("cloud-backup")) {
                    String url = cloudUrl(b.path("cloudUrl").asText(""));
                    String key = b.path("cloudKey").asText("").trim();
                    if (url.isEmpty() || key.isEmpty()) {
                        throw new LocalServer.UserProblem("Type the cloud project's address and its secret key.");
                    }
                    server.cloudUrl = url;
                    server.cloudKey = key;
                    String problem = stack.backups().checkCloud();
                    if (problem != null) {
                        server.cloudUrl = "";
                        server.cloudKey = "";
                        throw new LocalServer.UserProblem(problem);
                    }
                    stack.backups().restoreAtNextStart(null);
                } else {
                    throw new LocalServer.UserProblem("Unknown choice.");
                }
            }
            boolean passwordChanged = !password.equals(server.counterPassword);
            server.counterPassword = password;
            server.enabled = true;
            server.ensureSecrets();
            server.save();
            if (stack.isWanted()) {
                if (passwordChanged) stack.applySettings(true, false);
            } else {
                stack.start();
            }
            DesktopShell.keepAwake();
            log.info("This PC is now the XeoGo server ({})", from);
        } catch (IOException e) {
            throw new LocalServer.UserProblem(e.getMessage());
        }
    }

    /** The server is somewhere else after all (the cloud): stop the one on this PC. Its data stays on the disk. */
    synchronized void disable() {
        if (!available() || !server.enabled) return;
        server.enabled = false;
        try {
            server.save();
        } catch (IOException e) {
            log.warn("Could not save the server settings: {}", e.getMessage());
        }
        stack.stop();
        log.warn("The server on this PC was switched off; its data stays in {}", stack.data());
    }

    // ------------------------------------------------------------------ settings

    /** Saves what was sent (only the fields that are there) and restarts what it concerns. */
    synchronized void save(JsonNode b) {
        requireEnabled();
        ServerSettings old = copy();
        boolean service = false, tunnel = false, cloud = false;
        try {
            if (b.has("paymentMode")) {
                String mode = b.path("paymentMode").asText("").trim().toLowerCase(Locale.ROOT);
                if (!mode.isEmpty() && !mode.equals("upi") && !mode.equals("razorpay") && !mode.equals("demo")) {
                    throw new LocalServer.UserProblem("Unknown way to pay.");
                }
                server.paymentMode = mode;
                if (b.has("upiId")) server.upiId = b.path("upiId").asText("").trim();
                if (b.has("upiName")) server.upiName = b.path("upiName").asText("").trim();
                if (b.has("upiMerchantCode")) server.upiMerchantCode = b.path("upiMerchantCode").asText("").trim();
                if (b.has("upiSmsSenders")) server.upiSmsSenders = b.path("upiSmsSenders").asText("").trim();
                if (b.has("razorpayKeyId")) server.razorpayKeyId = b.path("razorpayKeyId").asText("").trim();
                // an empty box means "keep the secret that is saved"
                if (!b.path("razorpayKeySecret").asText("").isBlank()) server.razorpayKeySecret = b.path("razorpayKeySecret").asText("").trim();
                checkPayment();
                service = true;
            }
            if (b.has("publicUrl")) {
                String url = b.path("publicUrl").asText("").trim().replaceAll("/+$", "");
                if (!url.isEmpty() && !url.matches("(?i)https?://.*")) url = "https://" + url;
                if (!url.isEmpty() && !WEB_ADDRESS.matcher(url).matches()) {
                    throw new LocalServer.UserProblem("The internet address looks like https://print.yourcollege.in "
                            + "(https, and nothing after the name).");
                }
                if (!url.equalsIgnoreCase(server.publicUrl)) {
                    server.publicUrl = url;
                    service = true;
                }
            }
            if (!b.path("tunnelToken").asText("").isBlank()) {
                // People paste the whole line Cloudflare shows ("cloudflared.exe service install eyJh..."): take the token out.
                Matcher m = TUNNEL_TOKEN.matcher(b.path("tunnelToken").asText(""));
                if (!m.find()) {
                    throw new LocalServer.UserProblem("That is not a Cloudflare tunnel token. It is the long text that "
                            + "starts with eyJ, shown on your tunnel's page at Cloudflare.");
                }
                if (!m.group().equals(server.tunnelToken)) {
                    server.tunnelToken = m.group();
                    tunnel = true;
                }
            }
            if (b.path("removeTunnel").asBoolean(false) && server.hasTunnel()) {
                server.tunnelToken = "";
                tunnel = true;
            }
            if (b.has("cloudUrl")) {
                String url = cloudUrl(b.path("cloudUrl").asText(""));
                String key = b.path("cloudKey").asText("").trim();
                if (url.isEmpty()) {
                    server.cloudUrl = "";
                    server.cloudKey = "";
                } else {
                    server.cloudUrl = url;
                    if (!key.isEmpty()) server.cloudKey = key;
                    if (server.cloudKey.isBlank()) throw new LocalServer.UserProblem("Type the cloud project's secret key too.");
                    String problem = stack.backups().checkCloud();
                    if (problem != null) throw new LocalServer.UserProblem(problem);
                    cloud = true;
                }
            }
            if (b.has("webOrigins")) {
                String o = b.path("webOrigins").asText("").trim();
                if (o.isEmpty()) o = "*";
                if (!o.equals(server.webOrigins)) {
                    server.webOrigins = o;
                    service = true;
                }
            }
            server.save();
        } catch (LocalServer.UserProblem e) {
            restore(old);
            throw e;
        } catch (IOException e) {
            restore(old);
            throw new LocalServer.UserProblem("The settings could not be saved: " + e.getMessage());
        }
        if (service || tunnel) stack.applySettings(service, tunnel);
        if (cloud) {
            // the first copy to the cloud straight away, so "is it working?" is answered now
            new Thread(() -> {
                try {
                    stack.backups().runNow();
                } catch (RuntimeException e) {
                    log.warn("The first backup to the cloud did not work: {}", e.getMessage());
                }
            }, "first-cloud-backup").start();
        }
    }

    /** The same checks the print service makes when it starts, made before it is restarted. */
    private void checkPayment() {
        switch (server.paymentMode) {
            case "upi" -> {
                String id = server.upiId;
                String vpa = id;
                if (id.toLowerCase(Locale.ROOT).startsWith("upi://")) {
                    Matcher m = Pattern.compile("[?&]pa=([^&]+)").matcher(id);
                    vpa = m.find() ? java.net.URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8) : "";
                }
                if (!VPA.matcher(vpa.trim()).matches()) {
                    throw new LocalServer.UserProblem("Type the shop's UPI ID (like xeroxshop@okaxis), or paste the whole "
                            + "text of the shop's UPI QR code (it starts with upi://pay?pa=).");
                }
            }
            case "razorpay" -> {
                if (server.razorpayKeyId.isBlank() || server.razorpayKeySecret.isBlank()) {
                    throw new LocalServer.UserProblem("Razorpay needs both the Key ID and the Key Secret.");
                }
            }
            default -> { }
        }
    }

    /** A new staff password for the counter: the server on this PC takes it, and this PC remembers it. */
    synchronized void counterPassword(String password) {
        requireEnabled();
        if (password == null || password.length() < 8) {
            throw new LocalServer.UserProblem("Choose a counter password of 8 or more characters.");
        }
        server.counterPassword = password;
        try {
            server.save();
        } catch (IOException e) {
            throw new LocalServer.UserProblem("The password could not be saved: " + e.getMessage());
        }
        stack.applySettings(true, false);
    }

    String backupNow() {
        requireEnabled();
        if (!stack.serviceRunning()) throw new LocalServer.UserProblem("The server is still starting. Try again in a minute.");
        try {
            return stack.backups().runNow();
        } catch (IllegalStateException e) {
            throw new LocalServer.UserProblem(e.getMessage());
        }
    }

    void restart() {
        requireEnabled();
        stack.applySettings(true, true);
    }

    void openFolder(String which) {
        requireAvailable();
        DesktopShell.openFolder("backups".equals(which) ? stack.backupsDir() : stack.data());
    }

    void forgetRepairNote() {
        if (stack != null) stack.forgetRepairNote();
    }

    // ------------------------------------------------------------------ helpers

    private static String cloudUrl(String typed) {
        String url = typed == null ? "" : typed.trim().replaceAll("/+$", "");
        if (url.isEmpty()) return "";
        if (!url.matches("(?i)https?://.*")) url = "https://" + url;
        if (!WEB_ADDRESS.matcher(url).matches()) {
            throw new LocalServer.UserProblem("The cloud project's address looks like https://abcdefgh.supabase.co");
        }
        return url;
    }

    private void requireAvailable() {
        if (!available()) {
            throw new LocalServer.UserProblem("This copy of XeoGo Station came without the server programs. "
                    + "Install the full XeoGo Station setup.");
        }
    }

    private void requireEnabled() {
        requireAvailable();
        if (!server.enabled) throw new LocalServer.UserProblem("This PC is not set up as the XeoGo server.");
    }

    private ServerSettings copy() {
        ServerSettings c = new ServerSettings();
        c.paymentMode = server.paymentMode;
        c.upiId = server.upiId;
        c.upiName = server.upiName;
        c.upiMerchantCode = server.upiMerchantCode;
        c.upiSmsSenders = server.upiSmsSenders;
        c.razorpayKeyId = server.razorpayKeyId;
        c.razorpayKeySecret = server.razorpayKeySecret;
        c.publicUrl = server.publicUrl;
        c.tunnelToken = server.tunnelToken;
        c.cloudUrl = server.cloudUrl;
        c.cloudKey = server.cloudKey;
        c.webOrigins = server.webOrigins;
        return c;
    }

    private void restore(ServerSettings c) {
        server.paymentMode = c.paymentMode;
        server.upiId = c.upiId;
        server.upiName = c.upiName;
        server.upiMerchantCode = c.upiMerchantCode;
        server.upiSmsSenders = c.upiSmsSenders;
        server.razorpayKeyId = c.razorpayKeyId;
        server.razorpayKeySecret = c.razorpayKeySecret;
        server.publicUrl = c.publicUrl;
        server.tunnelToken = c.tunnelToken;
        server.cloudUrl = c.cloudUrl;
        server.cloudKey = c.cloudKey;
        server.webOrigins = c.webOrigins;
    }
}
