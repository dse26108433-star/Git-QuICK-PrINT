package edu.campus.print.security;

import edu.campus.print.common.ClientIp;
import edu.campus.print.config.CounterProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may use the counter API: a screen that is signed in (X-Counter-Session,
 * see CounterSessions), or one that sends the counter password
 * (X-Counter-Password: the sign-in itself, and Stations before version 4.3).
 *
 * The password is everything here (prices, "money received", registering a
 * PC), so guessing it from the internet must not work:
 *
 *   per address   10 wrong passwords in 10 minutes, then that address waits
 *   in total      40 wrong passwords in 5 minutes from anywhere, then nobody
 *                 can sign in with the password for 5 minutes; while the
 *                 guessing goes on, the pause doubles each time, up to an hour
 *
 * The total limit is the one that holds against a script that changes its
 * address. Screens that are signed in already are not affected by either:
 * they send their token, which nobody can guess.
 */
public class CounterAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CounterAuthFilter.class);

    static final int MAX_FAILURES_PER_ADDRESS = 10;
    static final long ADDRESS_WINDOW_MS = 10 * 60_000L;
    static final int MAX_FAILURES_IN_TOTAL = 40;
    static final long TOTAL_WINDOW_MS = 5 * 60_000L;
    static final long LONGEST_PAUSE_MS = 60 * 60_000L;
    static final long CALM_AFTER_MS = 30 * 60_000L;

    private final CounterProperties props;
    private final CounterSessions sessions;
    private final Guard guard = new Guard();

    public CounterAuthFilter(CounterProperties props, CounterSessions sessions) {
        this.props = props;
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (!props.isConfigured()) {
            reject(res, 503, "COUNTER_NOT_SET_UP",
                    "Set COUNTER_PASSWORD (8 or more characters) in backend/.env and restart the backend.");
            return;
        }

        String session = req.getHeader("X-Counter-Session");
        if (session != null && sessions.valid(session.trim())) {
            signIn();
            chain.doFilter(req, res);
            return;
        }

        String given = req.getHeader("X-Counter-Password");
        if (given != null) {
            String ip = ClientIp.of(req);
            long now = System.currentTimeMillis();
            if (guard.paused(ip, now)) {
                reject(res, 429, "TOO_MANY_ATTEMPTS", "Too many wrong passwords. Wait a few minutes, then try again.");
                return;
            }
            if (MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                    props.password().trim().getBytes(StandardCharsets.UTF_8))) {
                signIn();
            } else if (guard.failed(ip, now)) {
                log.warn("Counter password: many wrong tries (last from {}). Signing in with the password is paused "
                        + "for a while; screens that are signed in keep working.", ip);
            }
        }
        chain.doFilter(req, res);       // not signed in: Spring Security answers 401
    }

    private static void signIn() {
        var auth = new UsernamePasswordAuthenticationToken("counter", null,
                List.of(new SimpleGrantedAuthority("ROLE_COUNTER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !pathOf(req).startsWith("/api/v1/counter/") || "OPTIONS".equals(req.getMethod());
    }

    private static void reject(HttpServletResponse res, int status, String code, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    /** The path without the context path, the same in a real server and in tests. */
    private static String pathOf(jakarta.servlet.http.HttpServletRequest r) {
        String uri = r.getRequestURI();
        String ctx = r.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }

    /** Counts wrong passwords, per address and in total. */
    static final class Guard {

        private final Map<String, long[]> perAddress = new ConcurrentHashMap<>();   // address -> {window start, failures}
        private long totalWindowStart;
        private int totalFailures;
        private long pausedUntil;
        private long lastFailure;
        private int strikes;

        /** Must this caller wait before a password is even looked at? */
        synchronized boolean paused(String ip, long now) {
            if (now < pausedUntil) return true;
            long[] a = perAddress.get(ip);
            return a != null && now - a[0] < ADDRESS_WINDOW_MS && a[1] >= MAX_FAILURES_PER_ADDRESS;
        }

        /** A wrong password. True when this one starts a pause for everybody. */
        synchronized boolean failed(String ip, long now) {
            if (perAddress.size() > 20_000) perAddress.clear();
            long[] a = perAddress.computeIfAbsent(ip, k -> new long[] {now, 0});
            if (now - a[0] >= ADDRESS_WINDOW_MS) {
                a[0] = now;
                a[1] = 0;
            }
            a[1]++;

            if (now - lastFailure > CALM_AFTER_MS) strikes = 0;          // the guessing stopped for a while
            lastFailure = now;
            if (now - totalWindowStart >= TOTAL_WINDOW_MS) {
                totalWindowStart = now;
                totalFailures = 0;
            }
            if (++totalFailures < MAX_FAILURES_IN_TOTAL) return false;
            strikes = Math.min(strikes + 1, 8);
            pausedUntil = now + Math.min(LONGEST_PAUSE_MS, TOTAL_WINDOW_MS << (strikes - 1));
            totalWindowStart = now;
            totalFailures = 0;
            return true;
        }
    }
}
