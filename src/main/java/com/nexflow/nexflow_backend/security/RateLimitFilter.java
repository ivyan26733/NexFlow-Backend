package com.nexflow.nexflow_backend.security;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-IP rate limiting for sensitive endpoints using Bucket4j (in-memory, no Redis needed).
 *
 * Auth endpoints:   10 requests / minute per IP
 * Pulse endpoints:  30 requests / minute per IP  (public webhook triggers)
 */
@Component
@Order(2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final Map<String, Bucket> authBuckets  = new ConcurrentHashMap<>();
    private final Map<String, Bucket> pulseBuckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                     HttpServletResponse res,
                                     FilterChain chain) throws ServletException, IOException {
        String path = req.getServletPath();
        String ip   = getClientIp(req);

        if (path.startsWith("/api/auth/")) {
            Bucket bucket = authBuckets.computeIfAbsent(ip, k -> buildBucket(10, Duration.ofMinutes(1)));
            if (!bucket.tryConsume(1)) {
                sendRateLimited(res, "Too many requests. Please wait before trying again.");
                return;
            }
        } else if (path.startsWith("/api/pulse/")) {
            Bucket bucket = pulseBuckets.computeIfAbsent(ip, k -> buildBucket(30, Duration.ofMinutes(1)));
            if (!bucket.tryConsume(1)) {
                sendRateLimited(res, "Too many flow trigger requests. Please slow down.");
                return;
            }
        }

        chain.doFilter(req, res);
    }

    private static Bucket buildBucket(int capacity, Duration refillPeriod) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, refillPeriod)))
                .build();
    }

    private static void sendRateLimited(HttpServletResponse res, String message) throws IOException {
        res.setStatus(429);
        res.setContentType("application/json");
        res.setHeader("Retry-After", "60");
        res.getWriter().write("{\"error\":\"" + message + "\"}");
    }

    /**
     * Prefer X-Forwarded-For (set by nginx / load balancer) over getRemoteAddr,
     * which would always return the proxy IP in a typical deployment.
     */
    private static String getClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
