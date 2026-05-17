package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.scraper.BrowserFetcher;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/cache")
public class CacheController {

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

    /** DELETE /api/admin/cache/search — clear the search cache. */
    @DeleteMapping("/search")
    public Map<String, Object> clearSearchCache() {
        Cache cache = cacheManager.getCache("search");
        if (cache != null) cache.clear();
        return Map.of("cleared", "search", "ok", true);
    }

    private double round(double v, int places) {
        double scale = Math.pow(10, places);
        return Math.round(v * scale) / scale;
    }
}
