package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.scraper.BrowserFetcher;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/cache")
public class CacheController {

    private static final Logger log = LoggerFactory.getLogger(CacheController.class);

    private final CacheManager cacheManager;
    private final BrowserFetcher browserFetcher;

    public CacheController(CacheManager cacheManager, BrowserFetcher browserFetcher) {
        this.cacheManager = cacheManager;
        this.browserFetcher = browserFetcher;
    }

    /** GET /api/admin/cache/stats — search-cache hit ratio + browser usage. */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();

        Cache cache = cacheManager.getCache("search");
        Map<String, Object> searchStats = new LinkedHashMap<>();
        if (cache instanceof CaffeineCache cc) {
            com.github.benmanes.caffeine.cache.Cache<Object, Object> native_ = cc.getNativeCache();
            CacheStats s = native_.stats();
            searchStats.put("size", native_.estimatedSize());
            searchStats.put("hitCount", s.hitCount());
            searchStats.put("missCount", s.missCount());
            searchStats.put("hitRate", round(s.hitRate(), 3));
            searchStats.put("requestCount", s.requestCount());
            searchStats.put("evictionCount", s.evictionCount());
            searchStats.put("loadFailureCount", s.loadFailureCount());
        } else {
            searchStats.put("note", "Cache not initialized (Caffeine?)");
        }
        out.put("search", searchStats);

        BrowserFetcher.Stats b = browserFetcher.stats();
        Map<String, Object> browserStats = new LinkedHashMap<>();
        browserStats.put("enabled", b.enabled());
        browserStats.put("ready", b.ready());
        browserStats.put("fetches", b.fetches());
        browserStats.put("failures", b.failures());
        out.put("browser", browserStats);

        return out;
    }

    /** GET /api/admin/cache — list every Caffeine cache with its live stats. */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : cacheManager.getCacheNames()) {
            Cache spring = cacheManager.getCache(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            if (spring instanceof CaffeineCache c) {
                com.github.benmanes.caffeine.cache.Cache<Object, Object> native_ = c.getNativeCache();
                row.put("size", native_.estimatedSize());
                CacheStats s = native_.stats();
                row.put("hitCount", s.hitCount());
                row.put("missCount", s.missCount());
                row.put("hitRate", round(s.hitRate(), 3));
                row.put("evictionCount", s.evictionCount());
                row.put("requestCount", s.requestCount());
            }
            out.add(row);
        }
        return ResponseEntity.ok(out);
    }

    /** DELETE /api/admin/cache/search — clear the search cache (legacy path). */
    @DeleteMapping("/search")
    public Map<String, Object> clearSearchCache() {
        Cache cache = cacheManager.getCache("search");
        if (cache != null) cache.clear();
        return Map.of("cleared", "search", "ok", true);
    }

    /** POST /api/admin/cache/{name}/flush — explicit, single-cache flush. */
    @PostMapping("/{name}/flush")
    public ResponseEntity<?> flush(@PathVariable String name) {
        Cache c = cacheManager.getCache(name);
        if (c == null) return ResponseEntity.notFound().build();
        c.clear();
        log.info("Cache flushed: {}", name);
        return ResponseEntity.ok(Map.of("flushed", name));
    }

    /** POST /api/admin/cache/flush-all — nuke every cache in one go. */
    @PostMapping("/flush-all")
    public ResponseEntity<?> flushAll() {
        int n = 0;
        for (String name : cacheManager.getCacheNames()) {
            Cache c = cacheManager.getCache(name);
            if (c != null) { c.clear(); n++; }
        }
        log.info("Cache flushed: all ({} caches)", n);
        return ResponseEntity.ok(Map.of("flushed", n));
    }

    private double round(double v, int places) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0;
        double scale = Math.pow(10, places);
        return Math.round(v * scale) / scale;
    }
}
