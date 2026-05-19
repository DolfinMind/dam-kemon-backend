package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds products whose current cheapest price is materially lower than its
 * recent peak — the "hot drops" rail on the homepage.
 *
 * <p>Rebuilds nightly after the indexer + price-history snapshot finish.
 * The rolled-up result lives in the {@code hot-drops} cache (60s TTL) and
 * is served straight to the public {@code /api/stats/hot-drops}.
 */
@Service
public class HotDropsService {

    private static final Logger log = LoggerFactory.getLogger(HotDropsService.class);
    private static final double MIN_DROP_RATIO = 1.10;
    private static final int HISTORY_DAYS = 7;

    private final ProductRepository productRepository;
    private final PriceHistoryRepository historyRepository;

    private volatile List<Map<String, Object>> latest = List.of();

    public HotDropsService(ProductRepository productRepository,
                           PriceHistoryRepository historyRepository) {
        this.productRepository = productRepository;
        this.historyRepository = historyRepository;
    }

    /**
     * Run nightly at 05:00, after the indexer (03:00) + price snapshot (04:00).
     * Cheap enough to also run on demand from the admin endpoint.
     */
    @Scheduled(cron = "${hot-drops.cron:0 0 5 * * *}")
    public void rebuild() {
        try {
            List<Product> products = productRepository.findAll();
            LocalDateTime cutoff = LocalDateTime.now().minusDays(HISTORY_DAYS);
            List<Map<String, Object>> out = new ArrayList<>();

            for (Product p : products) {
                if (p.getId() == null || p.getLowestPrice() == null || p.getLowestPrice() <= 0) continue;
                double current = p.getLowestPrice();

                List<PriceHistory> hist = historyRepository.findByProductIdOrderByRecordedAtDesc(p.getId());
                if (hist.isEmpty()) continue;

                double peak = 0;
                for (PriceHistory h : hist) {
                    if (h.getRecordedAt() == null || h.getRecordedAt().isBefore(cutoff)) continue;
                    if (h.getPrice() == null) continue;
                    if (h.getPrice() > peak) peak = h.getPrice();
                }
                if (peak <= 0 || peak <= current) continue;
                if (peak < current * MIN_DROP_RATIO) continue;

                double dropPct = (peak - current) / peak * 100.0;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.getId());
                row.put("slug", p.getSlug());
                row.put("name", p.getName());
                row.put("imageUrl", p.getImageUrl());
                row.put("category", p.getCategory());
                row.put("currentPrice", current);
                row.put("peakPrice", peak);
                row.put("dropPct", Math.round(dropPct * 10.0) / 10.0);
                row.put("sellerCount", p.getPrices() == null ? 0 : p.getPrices().size());
                out.add(row);
            }
            out.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("dropPct")).reversed());
            if (out.size() > 24) out = new ArrayList<>(out.subList(0, 24));
            this.latest = out;
            log.info("HotDrops: rebuilt — {} qualifying products", out.size());
        } catch (DataAccessException e) {
            log.warn("HotDrops: rebuild failed ({})", e.getMessage());
        }
    }

    @Cacheable("hot-drops")
    public List<Map<String, Object>> get(int limit) {
        if (latest.size() <= limit) return latest;
        return new ArrayList<>(latest.subList(0, limit));
    }

    /** Snapshot of category counts in the current hot-drops set. */
    public Map<String, Integer> byCategory() {
        Map<String, Integer> agg = new HashMap<>();
        for (Map<String, Object> row : latest) {
            String c = (String) row.get("category");
            agg.merge(c == null ? "other" : c, 1, Integer::sum);
        }
        return agg;
    }
}
