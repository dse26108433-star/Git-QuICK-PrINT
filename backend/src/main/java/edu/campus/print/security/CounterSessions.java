package edu.campus.print.security;

import edu.campus.print.common.Secrets;
import edu.campus.print.config.AgentProperties;
import edu.campus.print.config.CounterProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * A counter screen signs in once with the counter password and gets a
 * sign-in token for 30 days ("<expiry>.<random>.<signature>", HMAC-SHA256).
 * After that it sends the token, not the password.
 *
 * Why: wrong passwords are limited (CounterAuthFilter). When someone on the
 * internet keeps guessing, new sign-ins with the password are paused for a
 * while, but a screen that is signed in already keeps working with its
 * token: the Xerox center is never locked out of its own counter.
 *
 * The signature depends on the counter password: changing COUNTER_PASSWORD
 * signs every screen out.
 */
@Service
public class CounterSessions {

    public static final long TTL_SECONDS = 30L * 24 * 3600;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;

    public CounterSessions(AgentProperties agent, CounterProperties counter) {
        String secret = agent.tokenSecret() == null ? "" : agent.tokenSecret().trim();
        String password = counter.password() == null ? "" : counter.password().trim();
        this.key = hmac(secret.getBytes(StandardCharsets.UTF_8), "counter-session|" + password);
    }

    public String issue() {
        String body = Instant.now().plusSeconds(TTL_SECONDS).getEpochSecond() + "." + Secrets.newKey();
        return body + "." + B64.encodeToString(hmac(key, body));
    }

    /** True only for a token this server signed, with the current password, that has not run out. Never throws. */
    public boolean valid(String token) {
        try {
            int last = token.lastIndexOf('.');
            if (last < 0) return false;
            String body = token.substring(0, last);
            if (!Secrets.sameText(B64.encodeToString(hmac(key, body)), token.substring(last + 1))) return false;
            return Instant.now().getEpochSecond() <= Long.parseLong(body.substring(0, body.indexOf('.')));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            // An empty key is not allowed by the JDK; the app refuses to start without AGENT_TOKEN_SECRET anyway.
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[] {0} : key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot sign counter session", e);
        }
    }
}
