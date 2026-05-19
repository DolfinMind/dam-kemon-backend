package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Synthetic "iPhone" check that fires every 15 min. Runs the same
 * {@code /api/search} path the user-facing controller does and reports
 * the result + latency. Surfaced on {@code /actuator/health} as a custom
 * component so external uptime monitors can alert on it.
 */
@Service
public class SyntheticMonitorService {

    private static final Logger log = LoggerFactory.getLogger(SyntheticMonitorService.class);
    private static final List<String> CANARIES = List.of("iphone", "samsung galaxy", "laptop");

    private final CatalogSearchService search;

    private volatile Map<String, Object> lastResult = Map.of();

    public SyntheticMonitorService(CatalogSearchService search) {
        this.search = search;
    }

    @Scheduled(fixedDelayString = "${synthetic.interval-ms:900000}", initialDelay = 60_000L)
    public void run() {
        Map<String, Object> agg = new LinkedHashMap<>();
        boolean ok = true;
        for (String q : CANARIES) {
            long start = System.nanoTime();
            int total = -1;
            String err = null;
            try {
                SearchResponse r = search.search(q);
                total = r.getTotalResults() == null ? 0 : r.getTotalResults();
            } catch (Exception e) {
                err = e.getMessage();
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("results", total);
            row.put("latencyMs", ms);
            if (err != null) row.put("error", err);
            if (err != null || total <= 0) ok = false;
            agg.put(q, row);
        }
        agg.put("ok", ok);
        agg.put("ts", Instant.now().toString());
        this.lastResult = agg;
        if (!ok) {
            log.warn("SyntheticMonitor: regression — {}", agg);
        }
    }

    public Map<String, Object> latest() { return lastResult; }
}
