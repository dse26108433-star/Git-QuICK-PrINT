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
 * on purpose: many students on the same campus Wi-Fi or mobile network share
 * one public address, and they must not block each other. Unpaid orders never
 * print, so the only thing being protected here is storage space.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final int perMinute;
    private final int perHour;
    private final Map<String, Bucket> minute = new ConcurrentHashMap<>();
    private final Map<String, Bucket> hour = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${campus.ratelimit.orders-per-minute:60}") int perMinute,
                           @Value("${campus.ratelimit.orders-per-hour:600}") int perHour) {
        this.perMinute = perMinute;
        this.perHour = perHour;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String ip = req.getRemoteAddr();
        boolean ok = bucket(minute, ip, perMinute, Duration.ofMinutes(1)).tryConsume()
                & bucket(hour, ip, perHour, Duration.ofHours(1)).tryConsume();
        if (!ok) {
            res.setStatus(429);
            res.setHeader("Retry-After", "60");
            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
            res.getWriter().write("{\"error\":\"RATE_LIMITED\",\"message\":\"Too many orders from this network right now. Try again in a minute.\"}");
            return;
        }
        chain.doFilter(req, res);
    }

    /** Only order creation is limited; checking status must always work. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !("POST".equals(req.getMethod()) && "/api/v1/orders".equals(pathOf(req)));
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
