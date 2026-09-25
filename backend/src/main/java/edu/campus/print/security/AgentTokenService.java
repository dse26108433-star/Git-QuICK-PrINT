package edu.campus.print.security;

import edu.campus.print.config.AgentProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Short-lived tokens for the Xerox center PC.
 *
 * The PC holds a long secret but sends it only to /agent/v1/token. Every other
 * call carries a 15-minute token: "<agentId>.<expiry>.<signature>", signed
 * with HMAC-SHA256. A captured token stops working within the quarter hour.
 */
@Service
public class AgentTokenService {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final long ttlSeconds;

    public AgentTokenService(AgentProperties props) {
        String secret = props.tokenSecret() == null ? "" : props.tokenSecret().trim();
        if (secret.length() < 32) {
            throw new IllegalStateException(
                    "AGENT_TOKEN_SECRET must be at least 32 characters long. Set it in backend/.env");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = props.tokenTtlSeconds();
    }

    public String issue(UUID agentId) {
        String body = agentId + "." + Instant.now().plusSeconds(ttlSeconds).getEpochSecond();
        return body + "." + sign(body);
    }

    /** The agent id, or null for anything that does not verify. Never throws. */
    public UUID verify(String token) {
        try {
            int last = token.lastIndexOf('.');
            if (last < 0) return null;
            String body = token.substring(0, last);
            byte[] given = token.substring(last + 1).getBytes(StandardCharsets.US_ASCII);
            byte[] expected = sign(body).getBytes(StandardCharsets.US_ASCII);
            if (!MessageDigest.isEqual(given, expected)) return null;

            int dot = body.indexOf('.');
            UUID agentId = UUID.fromString(body.substring(0, dot));
            long expiry = Long.parseLong(body.substring(dot + 1));
            if (Instant.now().getEpochSecond() > expiry) return null;
            return agentId;
        } catch (Exception e) {
            return null;
        }
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return B64.encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign agent token", e);
        }
    }
}
