package edu.campus.agent.net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.campus.agent.config.AgentConfig;
import edu.campus.agent.net.Messages.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Everything this PC says to the backend. Safe to use from several printer
 * threads at once.
 *
 * The PC holds a long secret and swaps it for a 15-minute token, refreshing by
 * itself. Losing the internet is normal here, not exceptional: callers get an
 * OfflineException and simply try again later.
 */
public class BackendClient {

    private static final Logger log = LoggerFactory.getLogger(BackendClient.class);
    // A newer backend may send extra fields; an older PC must not stop printing because of them.
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Network down, or the backend is busy/restarting. Try again later. */
    public static class OfflineException extends IOException {
        public OfflineException(String m, Throwable c) { super(m, c); }
    }

    /** The backend said no. 409 means "this order is not yours any more". */
    public static class RejectedException extends IOException {
        public final int status;
        public RejectedException(int status, String m) { super(m); this.status = status; }
        public boolean notMine() { return status == 409; }
    }

    private final AgentConfig cfg;
    private final HttpClient http;
    private String token;
    private Instant tokenExpiry = Instant.EPOCH;

    public BackendClient(AgentConfig cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ---------------------------------------------------------------- calls

    public HeartbeatResult heartbeat(List<PrinterReport> reports) throws IOException {
        ObjectNode n = JSON.createObjectNode()
                .put("agentVersion", AgentVersion.VALUE)
                .put("hostName", hostName());
        n.set("printers", JSON.valueToTree(reports));
        HttpResponse<String> res = authed(() -> post("/agent/v1/heartbeat", n));
        requireOk(res);
        return JSON.readValue(res.body(), HeartbeatResult.class);
    }

    /** Asks for the next order for one printer. Empty = nothing to print. */
    public Optional<ClaimedOrder> claim(String printerId) throws IOException {
        ObjectNode n = JSON.createObjectNode().put("printerId", printerId);
        HttpResponse<String> res = authed(() -> post("/agent/v1/orders/claim", n));
        if (res.statusCode() == 204) return Optional.empty();
        requireOk(res);
        return Optional.of(JSON.readValue(res.body(), ClaimedOrder.class));
    }

    public String downloadUrl(String orderId, String claimToken) throws IOException {
        HttpResponse<String> res = authed(() -> base("/agent/v1/orders/" + orderId + "/download-url")
                .header("X-Claim-Token", claimToken).GET().build());
        requireOk(res);
        return JSON.readTree(res.body()).path("url").asText();
    }

    /** Downloads the student's file. Any failure here means nothing was printed. */
    public void downloadTo(String signedUrl, Path target) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(signedUrl)).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<Path> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofFile(target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OfflineException("Download interrupted", e);
        } catch (IOException e) {
            throw new OfflineException("Download failed: " + e.getMessage(), e);
        }
        if (res.statusCode() / 100 != 2) {
            throw new RejectedException(res.statusCode(), "Storage answered " + res.statusCode());
        }
    }

    /** DOWNLOADING, SUBMITTED, COMPLETED or FAILED. Carries the claim token. */
    public void reportStatus(String orderId, String claimToken, String status, String code, String message)
            throws IOException {
        ObjectNode n = JSON.createObjectNode()
                .put("claimToken", claimToken)
                .put("status", status)
                .put("errorCode", code == null ? "" : code)
                .put("message", truncate(message == null ? "" : message, 900));
        HttpResponse<String> res = authed(() -> post("/agent/v1/orders/" + orderId + "/status", n));
        requireOk(res);
    }

    /** Gives an order back before anything printed. Returns the new status. */
    public String release(String orderId, String claimToken, String code, String message) throws IOException {
        ObjectNode n = JSON.createObjectNode()
                .put("claimToken", claimToken)
                .put("errorCode", code == null ? "" : code)
                .put("message", truncate(message == null ? "" : message, 900));
        HttpResponse<String> res = authed(() -> post("/agent/v1/orders/" + orderId + "/release", n));
        requireOk(res);
        return JSON.readTree(res.body()).path("status").asText("");
    }

    public void renewLease(String orderId, String claimToken) throws IOException {
        ObjectNode n = JSON.createObjectNode().put("claimToken", claimToken);
        HttpResponse<String> res = authed(() -> post("/agent/v1/orders/" + orderId + "/lease", n));
        requireOk(res);
    }

    // ---------------------------------------------------------------- auth

    private synchronized String token(boolean forceNew) throws IOException {
        if (!forceNew && token != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60))) {
            return token;
        }
        String basic = Base64.getEncoder().encodeToString(
                (cfg.agentId.trim() + ":" + cfg.agentSecret.trim()).getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.backendUrl + "/agent/v1/token"))
                .header("Authorization", "Basic " + basic)
                .header("X-Agent-Version", AgentVersion.VALUE)
                .header("X-Agent-Host", hostName())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> res = send(req);
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            throw new RejectedException(res.statusCode(),
                    "The backend rejected agentId/agentSecret. Check agent.yml, or enroll the PC again (seed.sql step 3).");
        }
        if (res.statusCode() / 100 != 2) {
            throw new OfflineException("Sign-in answered " + res.statusCode(), null);
        }
        JsonNode body = JSON.readTree(res.body());
        token = body.path("token").asText();
        tokenExpiry = Instant.now().plusSeconds(body.path("expiresInSeconds").asLong(900));
        log.info("Signed in to the backend");
        return token;
    }

    /** Sends with the current token; on 401 gets a fresh token and tries once more. */
    private HttpResponse<String> authed(RequestFactory factory) throws IOException {
        HttpResponse<String> res = send(withToken(factory.build(), token(false)));
        if (res.statusCode() == 401) {
            res = send(withToken(factory.build(), token(true)));
        }
        return res;
    }

    @FunctionalInterface
    private interface RequestFactory {
        HttpRequest build() throws IOException;
    }

    private static HttpRequest withToken(HttpRequest r, String t) {
        HttpRequest.Builder b = HttpRequest.newBuilder(r.uri())
                .timeout(r.timeout().orElse(Duration.ofSeconds(45)))
                .header("Authorization", "Bearer " + t)
                .header("X-Agent-Version", AgentVersion.VALUE);
        r.headers().map().forEach((k, vs) -> {
            if (!k.equalsIgnoreCase("Authorization")) vs.forEach(v -> b.header(k, v));
        });
        b.method(r.method(), r.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        return b.build();
    }

    private HttpRequest.Builder base(String path) {
        return HttpRequest.newBuilder(URI.create(cfg.backendUrl + path)).timeout(Duration.ofSeconds(45));
    }

    private HttpRequest post(String path, JsonNode body) {
        return base(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
    }

    private HttpResponse<String> send(HttpRequest req) throws IOException {
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OfflineException("Request interrupted", e);
        } catch (IOException e) {
            throw new OfflineException("Cannot reach " + req.uri().getHost() + ": " + e.getMessage(), e);
        }
    }

    private static void requireOk(HttpResponse<String> res) throws IOException {
        int s = res.statusCode();
        if (s / 100 == 2) return;
        if (s / 100 == 5 || s == 429) {
            throw new OfflineException("Backend answered " + s, null);
        }
        throw new RejectedException(s, "Backend answered " + s + ": " + truncate(res.body(), 300));
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    public static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }
}
