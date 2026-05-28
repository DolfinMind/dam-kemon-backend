package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the {@code events} collection to power the "live" widgets on the
 * homepage: distinct anonymous users in the last 60s, trending search terms
 * in the last 24h.
 *
 * <p>Each method is cached so we don't repeatedly scan events for every
 * homepage hit. Cache TTLs are intentionally short (5s for live counters,
 * 60s for trending) — they're rolling windows but a few seconds of staleness
 * is invisible to the user.
 */
@Service
public class LiveStatsService {

    private final AnalyticsEventRepository events;

    public LiveStatsService(AnalyticsEventRepository events) {
        this.events = events;
    }

    @Cacheable("live-stats")
    public Map<String, Object> liveCounters() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Instant nowWindow = Instant.now().minus(60, ChronoUnit.SECONDS);
            List<AnalyticsEvent> recent = events.findByTypeAndTsAfter("search", nowWindow);
            Set<String> ids = new HashSet<>();
            for (AnalyticsEvent e : recent) {
                if (e.getAnonId() != null) ids.add(e.getAnonId());
                else if (e.getIpHash() != null) ids.add("ip:" + e.getIpHash());
            }
            out.put("activeUsers", ids.size());
            out.put("searchesLast60s", recent.size());

            Instant dayAgo = Instant.now().minus(24, ChronoUnit.HOURS);
            out.put("searchesToday", events.countByTypeAndTsAfter("search", dayAgo));
            out.put("viewsToday", events.countByTypeAndTsAfter("view", dayAgo));
        } catch (Exception e) {
            // Public homepage widget — must never 500. Transient Atlas socket
            // timeouts/resets don't all translate to DataAccessException, so
            // catch broadly and degrade to zeros.
            out.put("activeUsers", 0);
            out.put("searchesLast60s", 0);
            out.put("searchesToday", 0L);
            out.put("viewsToday", 0L);
        }
        return out;
    }

    @Cacheable("trending-searches")
    public List<Map<String, Object>> trending(int limit) {
        try {
            Instant dayAgo = Instant.now().minus(24, ChronoUnit.HOURS);
            List<AnalyticsEvent> recent = events.findByTypeAndTsAfter("search", dayAgo);
            Map<String, int[]> agg = new LinkedHashMap<>();
            for (AnalyticsEvent e : recent) {
                String q = e.getQuery();
                if (q == null || q.length() < 2) continue;
                int[] v = agg.computeIfAbsent(q, k -> new int[]{0, 0});
                v[0]++;
                if (e.getResultCount() != null && e.getResultCount() > 0) v[1]++;
            }
            List<Map.Entry<String, int[]>> sorted = new ArrayList<>(agg.entrySet());
            sorted.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<String, int[]> e : sorted) {
                if (out.size() >= limit) break;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", e.getKey());
                row.put("hits", e.getValue()[0]);
                row.put("hitsWithResults", e.getValue()[1]);
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
