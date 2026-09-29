package edu.campus.print.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.campus.print.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only place the Supabase secret (service) key is used.
 *
 * Nothing in a browser, phone or print agent ever sees this key. They receive
 * signed URLs instead: a student gets a five-minute upload URL for one object
 * path, and the Xerox PC holding an order gets a five-minute download URL for
 * that one object. Documents are never public. The backend reads each file
 * once (probe) to check it and count its pages for the price.
 */
@Component
public class SupabaseStorage {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorage.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOTAL_SIZE = Pattern.compile("/(\\d+)\\s*$");

    private final SupabaseProperties props;
    private final String base;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public SupabaseStorage(SupabaseProperties props) {
        this.props = props;
        this.base = props.url().trim().replaceAll("/+$", "") + "/storage/v1";
    }

    public record SignedUpload(String url, String token, String path) {
    }

    /** What the server learned by reading the start of an uploaded object. */
    public record Probe(long totalSize, byte[] prefix) {
        public boolean isComplete() {
            return prefix.length >= totalSize;
        }
    }

    /**
     * An upload URL for exactly this object path. The student's browser or
     * phone PUTs the file straight to storage with it. "Upsert" lets a failed
     * or cancelled upload be tried again on the same path.
     */
    public SignedUpload createSignedUpload(String objectPath) {
        JsonNode body = postJson(base + "/object/upload/sign/" + props.bucket() + "/" + objectPath, "{}",
                "Could not prepare the upload", "x-upsert", "true");
        // Storage answers {"url": "/object/upload/sign/<bucket>/<path>?token=..."}
        String relative = body.path("url").asText("");
        if (relative.isEmpty()) {
            log.error("Storage returned no signed upload URL: {}", body);
            throw unavailable("Could not prepare the upload.");
        }
        String token = body.path("token").asText("");
        if (token.isEmpty()) {
            int i = relative.indexOf("token=");
            if (i >= 0) {
                token = URLDecoder.decode(relative.substring(i + 6).split("&")[0], StandardCharsets.UTF_8);
            }
        }
        return new SignedUpload(base + relative, token, objectPath);
    }

    /**
     * A short-lived read URL: for the Xerox PC holding the document, or for
     * the student's own device (to show a draft again after a page reload).
     */
    public String createSignedDownload(String objectPath) {
        String payload = "{\"expiresIn\":" + props.downloadUrlTtlSeconds() + "}";
        JsonNode body = postJson(base + "/object/sign/" + props.bucket() + "/" + objectPath, payload,
                "Could not prepare the download");
        String relative = body.path("signedURL").asText(body.path("signedUrl").asText(""));
        if (relative.isEmpty()) {
            log.error("Storage returned no signed download URL: {}", body);
            throw unavailable("Could not prepare the download.");
        }
        return base + relative;
    }

    /**
     * Reads the first bytes of an uploaded object and learns its full size in
     * the same request. Empty means the object does not exist (the upload never
     * arrived). Bounded, so a hostile upload cannot make the server allocate a
     * large buffer.
     */
    public Optional<Probe> probe(String objectPath, int maxBytes) {
        HttpResponse<byte[]> res;
        try {
            HttpRequest req = authorised(HttpRequest.newBuilder(
                            URI.create(base + "/object/authenticated/" + props.bucket() + "/" + objectPath)))
                    .header("Range", "bytes=0-" + (maxBytes - 1))
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable("Could not read the uploaded file.");
        } catch (Exception e) {
            log.warn("Storage probe failed for {}: {}", objectPath, e.toString());
            throw unavailable("Could not read the uploaded file.");
        }

        int status = res.statusCode();
        if (status == 404 || status == 400) {
            return Optional.empty();       // Storage uses 400 "Object not found" as well
        }
        if (status == 416) {
            return Optional.of(new Probe(0, new byte[0]));   // zero-byte object
        }
        if (status / 100 != 2) {
            log.error("Storage probe {} -> {} {}", objectPath, status,
                    new String(res.body(), StandardCharsets.UTF_8));
            throw unavailable("Could not read the uploaded file.");
        }

        byte[] body = res.body();
        long total = body.length;
        // 206 Partial Content carries "Content-Range: bytes 0-1023/52340"
        Optional<String> range = res.headers().firstValue("Content-Range");
        if (range.isPresent()) {
            Matcher m = TOTAL_SIZE.matcher(range.get());
            if (m.find()) {
                total = Long.parseLong(m.group(1));
            }
        }
        if (body.length > maxBytes) {
            byte[] cut = new byte[maxBytes];
            System.arraycopy(body, 0, cut, 0, maxBytes);
            body = cut;
        }
        return Optional.of(new Probe(total, body));
    }

    /** Called when a job finishes or is abandoned, so documents do not accumulate. */
    public void delete(String objectPath) {
        try {
            HttpRequest req = authorised(HttpRequest.newBuilder(
                            URI.create(base + "/object/" + props.bucket() + "/" + objectPath)))
                    .timeout(Duration.ofSeconds(20))
                    .DELETE().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2 && res.statusCode() != 404 && res.statusCode() != 400) {
                log.warn("Could not delete storage object {}: {} {}", objectPath, res.statusCode(), res.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Could not delete storage object {}: {}", objectPath, e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * New Supabase secret keys (sb_secret_...) are not JWTs and must travel
     * only in the apikey header; sending one as a Bearer token is rejected.
     * Legacy service_role keys (eyJ...) are JWTs and go in both headers.
     */
    private HttpRequest.Builder authorised(HttpRequest.Builder b) {
        String key = props.serviceKey().trim();
        b.header("apikey", key);
        if (!key.startsWith("sb_")) {
            b.header("Authorization", "Bearer " + key);
        }
        return b;
    }

    private JsonNode postJson(String url, String payload, String failureMessage, String... extraHeaders) {
        try {
            HttpRequest.Builder b = authorised(HttpRequest.newBuilder(URI.create(url)))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(payload));
            for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
                b.header(extraHeaders[i], extraHeaders[i + 1]);
            }
            HttpRequest req = b.build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                log.error("Supabase storage {} -> {} {}", url, res.statusCode(), res.body());
                if (res.statusCode() == 401 || res.statusCode() == 403) {
                    log.error("Check SUPABASE_SERVICE_KEY: it must be the SECRET key (sb_secret_...) "
                            + "or the legacy service_role key, not the publishable/anon key.");
                }
                if (res.body() != null && res.body().contains("Bucket not found")) {
                    log.error("The 'print-documents' bucket is missing. Run db/setup.sql in the Supabase SQL editor.");
                }
                throw unavailable(failureMessage + ".");
            }
            return JSON.readTree(res.body());
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(failureMessage + ".");
        } catch (Exception e) {
            log.error("Supabase storage call to {} failed: {}", url, e.toString());
            throw unavailable(failureMessage + ".");
        }
    }

    private static ApiException unavailable(String message) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "STORAGE_UNAVAILABLE",
                message + " Try again in a moment.");
    }

}
