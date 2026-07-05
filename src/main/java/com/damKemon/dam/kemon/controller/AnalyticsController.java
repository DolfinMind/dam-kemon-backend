package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.AnalyticsService;
import com.damKemon.dam.kemon.util.ClientIp;
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
        analytics.recordView(asString(body.get("productId")), asString(body.get("anonId")), ClientIp.of(req));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/click")
    public ResponseEntity<Void> click(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        analytics.recordClick(
                asString(body.get("productId")),
                asString(body.get("sellerSlug")),
                asString(body.get("anonId")),
                ClientIp.of(req));
        return ResponseEntity.noContent().build();
    }

    /** Autosuggest dropdown click — records "typed X, picked product Y". */
    @PostMapping("/suggest-click")
    public ResponseEntity<Void> suggestClick(@RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        analytics.recordSuggestClick(
                asString(body.get("query")),
                asString(body.get("productId")),
                asString(body.get("productName")),
                asString(body.get("anonId")),
                ClientIp.of(req));
        return ResponseEntity.noContent().build();
    }

    /**
     * A single-page-app route change. The client fires this on every navigation
     * so the server sees the full page-by-page journey, not just API calls.
     */
    @PostMapping("/pageview")
    public ResponseEntity<Void> pageview(@RequestBody Map<String, Object> body,
                                         HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        analytics.recordPageView(
                asString(body.get("path")),
                asString(body.get("anonId")),
                ClientIp.of(req),
                userId,
                asString(body.get("referer")),
                req.getHeader("User-Agent"));
        return ResponseEntity.noContent().build();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
