package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public, anonymous, fire-and-forget analytics ingestion. Each endpoint
 * accepts a small JSON body, writes the event asynchronously, and returns
 * 204 immediately so the client (often a {@code sendBeacon}) doesn't block.
 *
 * <p>No-PII contract: only an anon UUID from {@code localStorage} ever ties
 * events together. The server hashes the IP and discards the raw value.
 */
@RestController
@RequestMapping("/api/events")
public class AnalyticsController {

    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @PostMapping("/view")
    public ResponseEntity<Void> view(@RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        analytics.recordView(asString(body.get("productId")), asString(body.get("anonId")), clientIp(req));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/click")
    public ResponseEntity<Void> click(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        analytics.recordClick(
                asString(body.get("productId")),
                asString(body.get("sellerSlug")),
                asString(body.get("anonId")),
                clientIp(req));
        return ResponseEntity.noContent().build();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
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
