package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.service.AnalyticsService;
import com.damKemon.dam.kemon.service.CatalogSearchService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
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
                                                 @RequestParam(value = "page", defaultValue = "0") int page,
                                                 @RequestParam(value = "size", required = false) Integer size,
                                                 @RequestParam(value = "acc", defaultValue = "false") boolean includeAccessories,
                                                 @RequestParam(value = "ram", required = false) String ram,
                                                 @RequestParam(value = "storage", required = false) String storage,
                                                 @RequestParam(value = "display", required = false) String display,
                                                 @RequestHeader(value = "X-Anon-Id", required = false) String anonId,
                                                 HttpServletRequest req) {
        java.util.Map<String, String> specFilters = new java.util.LinkedHashMap<>();
        if (ram != null && !ram.isBlank()) specFilters.put("RAM", ram.trim());
        if (storage != null && !storage.isBlank()) specFilters.put("Storage", storage.trim());
        if (display != null && !display.isBlank()) specFilters.put("Display", display.trim());
        long start = System.nanoTime();
        SearchResponse resp = catalog.search(query, Math.max(0, page), size == null ? 0 : size, includeAccessories, specFilters);
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        String userId = (String) req.getAttribute("authUserId");
        analytics.recordSearch(query,
                resp.getTotalResults() == null ? 0 : resp.getTotalResults(),
                anonId, clientIp(req), userId, latencyMs, resultShops(resp));
        return ResponseEntity.ok(resp);
    }

    /** Distinct cheapest-offer shop slug per result product, in ranked order (top ~10). */
    private static List<String> resultShops(SearchResponse resp) {
        List<String> shops = new ArrayList<>();
        if (resp == null || resp.getProducts() == null) return shops;
        for (Product p : resp.getProducts()) {
            String slug = cheapestSlug(p);
            if (slug != null && !shops.contains(slug)) shops.add(slug);
            if (shops.size() >= 10) break;
        }
        return shops;
    }

    private static String cheapestSlug(Product p) {
        if (p == null || p.getPrices() == null) return null;
        String slug = null;
        double best = Double.MAX_VALUE;
        for (SitePrice sp : p.getPrices()) {
            if (sp.getPrice() == null || sp.getSiteSlug() == null) continue;
            if (sp.getPrice() < best) { best = sp.getPrice(); slug = sp.getSiteSlug(); }
        }
        return slug;
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
