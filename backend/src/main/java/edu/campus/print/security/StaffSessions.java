package edu.campus.print.security;

import edu.campus.print.common.Secrets;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.config.StaffProperties;
import edu.campus.print.domain.StaffAccount;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * A staff member signs in once with their username and password and the
 * device gets a sign-in token ("<staff id>.<password stamp>.<expiry>.<random>.<signature>",
 * HMAC-SHA256). After that the device sends the token (X-Staff-Session),
 * never the password.
 *
 * The token names the password it was made with (the moment that password
 * was set). So a new password from the Xerox center signs every device out
 * at once, and so does switching the ID off: StaffService checks both on
 * every request. Nobody can make or change a token without the server's
 * secret.
 */
@Service
public class StaffSessions {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final long ttlSeconds;

    /** staffId: whose token; passwordStamp: the password it was made with; expiresAt: when it runs out. */
    public record Claims(UUID staffId, long passwordStamp, Instant expiresAt) {}

    public StaffSessions(AgentProperties agent, StaffProperties staff) {
        String secret = agent.tokenSecret() == null ? "" : agent.tokenSecret().trim();
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "staff-session");
        this.ttlSeconds = staff.sessionDays() * 24L * 3600;
    }

    public long ttlSeconds() {
        return ttlSeconds;
    }

    public String issue(StaffAccount a) {
        String body = a.getId() + "." + a.getPasswordSetAt().toEpochMilli() + "."
                + Instant.now().plusSeconds(ttlSeconds).getEpochSecond() + "." + Secrets.newKey().substring(0, 16);
        return body + "." + B64.encodeToString(hmac(key, body));
    }

    /** What a token says, if this server signed it and it has not run out. Never throws. */
    public Optional<Claims> read(String token) {
        try {
            if (token == null || token.length() > 300) return Optional.empty();
            int last = token.lastIndexOf('.');
            if (last < 0) return Optional.empty();
            String body = token.substring(0, last);
            if (!Secrets.sameText(B64.encodeToString(hmac(key, body)), token.substring(last + 1))) return Optional.empty();
            String[] part = body.split("\\.");
            if (part.length != 4) return Optional.empty();
            Instant expires = Instant.ofEpochSecond(Long.parseLong(part[2]));
            if (Instant.now().isAfter(expires)) return Optional.empty();
            return Optional.of(new Claims(UUID.fromString(part[0]), Long.parseLong(part[1]), expires));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // An empty key is not allowed by the JDK; the app refuses to start without AGENT_TOKEN_SECRET anyway.
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[] {0} : key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign staff session", e);
        }
    }
}
