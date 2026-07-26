package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.model.RequestLog;
import com.damKemon.dam.kemon.service.RequestLogService;
import com.damKemon.dam.kemon.util.ClientIp;
import com.damKemon.dam.kemon.util.TrafficClassifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Logs EVERY inbound {@code /api/**} request into {@code request_log} — the
 * "every step" server-side access log. Runs for all API traffic, not just admin.
 *
 * <p>Registered at {@link Ordered#HIGHEST_PRECEDENCE} so it wraps Spring
 * Security's chain: it therefore also captures requests the chain short-circuits
 * (429 rate-limit, 401 admin-gate) with their final status. The actual Mongo
 * write is buffered off-thread by {@link RequestLogService}, so this filter only
 * adds a cheap object build + lock-free enqueue to the request.
 *
 * <p>A few paths are skipped to avoid noise and feedback loops: the analytics
 * beacons (already captured as richer typed events), the analytics read
 * endpoints (so viewing the log doesn't spam it), actuator health, and CORS
 * preflights.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLogFilter extends OncePerRequestFilter {

    private final RequestLogService sink;
    private final boolean storeRawIp;

    public RequestLogFilter(RequestLogService sink,
                            @Value("${analytics.store-raw-ip:false}") boolean storeRawIp) {
        this.sink = sink;
        this.storeRawIp = storeRawIp;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            try {
                record(req, res, (System.nanoTime() - start) / 1_000_000);
            } catch (Exception ignored) {
                // Logging must never affect the response.
            }
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) return true;
        String p = req.getRequestURI();
        if (p == null || !p.startsWith("/api/")) return true;     // only API traffic reaches the backend
        if (p.startsWith("/api/events")) return true;             // typed beacons — already logged as events
        if (p.startsWith("/api/admin/analytics")) return true;    // don't log the log reader
        return false;
    }

    private void record(HttpServletRequest req, HttpServletResponse res, long latencyMs) {
        String ip = ClientIp.of(req);
        Object userId = req.getAttribute("authUserId");
        sink.enqueue(RequestLog.builder()
                .method(req.getMethod())
                .path(req.getRequestURI())
                .query(trim(req.getQueryString(), 200))
                .status(res.getStatus())
                .latencyMs(latencyMs)
                .ip(storeRawIp ? ip : null)
                .ipHash(ClientIp.hash(ip))
                .userId(userId == null ? null : String.valueOf(userId))
                .anonId(trim(req.getHeader("X-Anon-Id"), 64))
                .userAgent(trim(req.getHeader("User-Agent"), 256))
                .trafficClass(TrafficClassifier.classify(req.getHeader("User-Agent"), ip))
                .referer(trim(req.getHeader("Referer"), 256))
                .ts(Instant.now())
                .build());
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty()) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
