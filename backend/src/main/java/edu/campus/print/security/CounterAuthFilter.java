package edu.campus.print.security;

import edu.campus.print.config.CounterProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The counter screen sends the staff password in the X-Counter-Password header.
 * Wrong guesses from one address are slowed down so the password cannot be
 * brute-forced from the internet.
 */
public class CounterAuthFilter extends OncePerRequestFilter {

    private static final int MAX_FAILURES_PER_10_MIN = 10;

    private final CounterProperties props;
    private final Map<String, int[]> failures = new ConcurrentHashMap<>();
    private volatile long windowStart = Instant.now().getEpochSecond();

    public CounterAuthFilter(CounterProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {

        if (!props.isConfigured()) {
            reject(res, 503, "COUNTER_NOT_SET_UP",
                    "Set COUNTER_PASSWORD (8 or more characters) in backend/.env and restart the backend.");
            return;
        }

        long now = Instant.now().getEpochSecond();
        if (now - windowStart > 600) {
            failures.clear();
            windowStart = now;
        }
        String ip = req.getRemoteAddr();
        int[] count = failures.computeIfAbsent(ip, k -> new int[1]);
        if (count[0] >= MAX_FAILURES_PER_10_MIN) {
            reject(res, 429, "TOO_MANY_ATTEMPTS", "Too many wrong passwords. Wait 10 minutes.");
            return;
        }

        String given = req.getHeader("X-Counter-Password");
        if (given != null && MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8),
                props.password().trim().getBytes(StandardCharsets.UTF_8))) {
            var auth = new UsernamePasswordAuthenticationToken("counter", null,
                    List.of(new SimpleGrantedAuthority("ROLE_COUNTER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } else if (given != null) {
            synchronized (count) {
                count[0]++;
            }
        }
        chain.doFilter(req, res);
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
}
