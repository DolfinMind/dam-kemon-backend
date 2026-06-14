package com.damKemon.dam.kemon.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Per-IP rate limiting for the public API — the bot/scraper defense.
 *
 * <p>Each {@link Rule} meters a set of path prefixes with its own token bucket;
 * the first matching rule wins and consumes one token. Over budget → {@code 429}
 * with a {@code Retry-After}. Admin/auth/static paths match no rule and are
 * never limited, so operator scripts and logins keep working.
 *
 * <p>The key is the real client IP. Behind nginx that's {@code X-Real-IP} (set
 * by the proxy) or the LAST hop of {@code X-Forwarded-For} — the entry nginx
 * appends — NOT the leftmost XFF value, which a client can spoof to rotate keys
 * and slip the limit.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** A protection tier: requests under any of {@code prefixes} are metered by {@code limiter}. */
    public record Rule(RateLimiter limiter, int retryAfterSec, List<String> prefixes) {
        boolean matches(String path) {
            for (String p : prefixes) if (path.startsWith(p)) return true;
            return false;
        }
    }

    private final List<Rule> rules;

    public RateLimitFilter(List<Rule> rules) {
        this.rules = rules;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        if (path != null) {
            for (Rule r : rules) {
                if (r.matches(path)) {
                    if (!r.limiter().tryConsume(clientIp(req))) {
                        res.setStatus(429);
                        res.setHeader("Retry-After", Integer.toString(r.retryAfterSec()));
                        res.setContentType("application/json");
                        res.getWriter().write(
                                "{\"error\":\"rate limit exceeded\",\"retryAfterSec\":" + r.retryAfterSec() + "}");
                        return;
                    }
                    break; // one matching rule, one token per request
                }
            }
        }
        chain.doFilter(req, res);
    }

    /**
     * Spoof-resistant client IP. Trust only proxy-set values: {@code X-Real-IP}
     * first, else the LAST entry of {@code X-Forwarded-For} (the hop nginx
     * appended — a client spoofing XFF can only prepend entries). Never the
     * leftmost XFF value. Falls back to the socket address.
     */
    static String clientIp(HttpServletRequest req) {
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.lastIndexOf(',');
            return (comma < 0 ? fwd : fwd.substring(comma + 1)).trim();
        }
        return req.getRemoteAddr();
    }
}
