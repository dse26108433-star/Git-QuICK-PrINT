package edu.campus.print.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stops a script from creating thousands of orders.
 *
 * There is no login, so this counts per network address. Limits are generous
 * on purpose: a whole campus on the same Wi-Fi (and a whole mobile network)
 * shares one public address, and hundreds of students ordering in the same
 * break must not block each other. Unpaid orders never print, so the only
 * thing being protected here is storage space.
 *
 * Staff sign-ins are counted per address too (each one costs the server a
 * password check). Guessing a staff password is stopped elsewhere: passwords
 * are random, and an ID has to wait after a few wrong ones (StaffService).
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String ORDERS = "/api/v1/orders";
    private static final String STAFF_LOGIN = "/api/v1/staff/login";

    private final int perMinute;
    private final int perHour;
    private final int loginsPerTenMinutes;
    private final Map<String, Bucket> minute = new ConcurrentHashMap<>();
    private final Map<String, Bucket> hour = new ConcurrentHashMap<>();
    private final Map<String, Bucket> logins = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${campus.ratelimit.orders-per-minute:1000}") int perMinute,
                           @Value("${campus.ratelimit.orders-per-hour:10000}") int perHour,
                           @Value("${campus.ratelimit.staff-logins-per-10-minutes:30}") int loginsPerTenMinutes) {
        this.perMinute = perMinute;
        this.perHour = perHour;
        this.loginsPerTenMinutes = loginsPerTenMinutes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = ClientIp.of(req);
        boolean login = STAFF_LOGIN.equals(pathOf(req));
        boolean ok = login
                ? bucket(logins, ip, loginsPerTenMinutes, Duration.ofMinutes(10)).tryConsume()
                : bucket(minute, ip, perMinute, Duration.ofMinutes(1)).tryConsume()
                        & bucket(hour, ip, perHour, Duration.ofHours(1)).tryConsume();
        if (!ok) {
            res.setStatus(429);
            res.setHeader("Retry-After", login ? "300" : "60");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write(login
                    ? "{\"error\":\"RATE_LIMITED\",\"message\":\"Too many sign-in tries from this network. Try again in a few minutes.\"}"
                    : "{\"error\":\"RATE_LIMITED\",\"message\":\"Too many orders from this network right now. Try again in a minute.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /** Only order creation and staff sign-in are limited; checking status must always work. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String path = pathOf(req);
        return !("POST".equals(req.getMethod()) && (ORDERS.equals(path) || STAFF_LOGIN.equals(path)));
    }

    private static Bucket bucket(Map<String, Bucket> map, String key, int capacity, Duration window) {
        if (map.size() > 50_000) {
            map.entrySet().removeIf(e -> e.getValue().isStale());
        }
        return map.compute(key, (k, b) -> (b == null || b.isStale()) ? new Bucket(capacity, window) : b);
    }

    private static final class Bucket {
        private final int capacity;
        private final Duration window;
        private Instant start = Instant.now();
        private int used;

        Bucket(int capacity, Duration window) {
            this.capacity = capacity;
            this.window = window;
        }

        synchronized boolean tryConsume() {
            if (Instant.now().isAfter(start.plus(window))) {
                start = Instant.now();
                used = 0;
            }
            if (used >= capacity) return false;
            used++;
            return true;
        }

        synchronized boolean isStale() {
            return Instant.now().isAfter(start.plus(window).plus(window));
        }
    }

    /** The path without the context path, the same in a real server and in tests. */
    private static String pathOf(jakarta.servlet.http.HttpServletRequest r) {
        String uri = r.getRequestURI();
        String ctx = r.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
