package com.damKemon.dam.kemon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Caps anonymous traffic on {@code /api/search} and {@code /api/search/suggest}
 * to a sane per-IP budget so scrapers can't pin the Mongo free tier.
 *
 * <p>Defaults: 60 tokens, refilled at 1/s (≈ 60 req/min sustained, brief
 * bursts to 60). Tunable via {@code RATE_LIMIT_CAPACITY} and
 * {@code RATE_LIMIT_REFILL_PER_SEC}.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter limiter;

    public RateLimitFilter(RateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path == null || !path.startsWith("/api/search")) {
            chain.doFilter(req, res);
            return;
        }

        String key = clientIp(req);
        if (!limiter.tryConsume(key)) {
            res.setStatus(429);
            res.setHeader("Retry-After", "5");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"rate limit exceeded\",\"retryAfterSec\":5}");
            return;
        }
        chain.doFilter(req, res);
    }

    private static String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma < 0 ? fwd : fwd.substring(0, comma)).trim();
        }
        return req.getRemoteAddr();
    }
}
