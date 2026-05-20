package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.service.AnalyticsService;
import com.damKemon.dam.kemon.service.CatalogSearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final CatalogSearchService catalog;
    private final AnalyticsService analytics;

    public SearchController(CatalogSearchService catalog, AnalyticsService analytics) {
        this.catalog = catalog;
        this.analytics = analytics;
    }

    @GetMapping
    public ResponseEntity<SearchResponse> search(@RequestParam("q") String query,
                                                 @RequestHeader(value = "X-Anon-Id", required = false) String anonId,
                                                 HttpServletRequest req) {
        long start = System.nanoTime();
        SearchResponse resp = catalog.search(query);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        String userId = (String) req.getAttribute("authUserId");
        analytics.recordSearch(query,
                resp.getTotalResults() == null ? 0 : resp.getTotalResults(),
                anonId, clientIp(req), userId, latencyMs);
        return ResponseEntity.ok(resp);
    }

    @GetMapping("/suggest")
    public ResponseEntity<List<Map<String, Object>>> suggest(
            @RequestParam("q") String prefix,
            @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return ResponseEntity.ok(catalog.autocomplete(prefix, limit));
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
