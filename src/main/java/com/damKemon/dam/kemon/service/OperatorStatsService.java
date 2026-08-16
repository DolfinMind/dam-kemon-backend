package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Aggregations used by the admin console: DAU/MAU, zero-result leaderboard,
 * CTR per shop, top products by views/clicks, search latency p50/p95.
 *
 * <p>Everything reads off the {@code events} collection. For very large
 * traffic we'd roll these into a daily summary; at our scale a scan per
 * call (cached briefly upstream) is fine.
 */
@Service
public class OperatorStatsService {

    private final AnalyticsEventRepository events;
    private final ProductRepository products;

    public OperatorStatsService(AnalyticsEventRepository events, ProductRepository products) {
        this.events = events;
        this.products = products;
    }

    /** DAU (last 24h) + MAU (last 30d) distinct anon ids. */
    public Map<String, Object> userCounts() {
        Map<String, Object> out = new LinkedHashMap<>();
        Instant day = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant month = Instant.now().minus(30, ChronoUnit.DAYS);
        try {
            Set<String> dau = distinctAnonIds(events.findByTypeAndTsAfter("search", day));
            dau.addAll(distinctAnonIds(events.findByTypeAndTsAfter("view", day)));
            Set<String> mau = distinctAnonIds(events.findByTypeAndTsAfter("search", month));
            mau.addAll(distinctAnonIds(events.findByTypeAndTsAfter("view", month)));
            out.put("dau", dau.size());
            out.put("mau", mau.size());
        } catch (DataAccessException e) {
            out.put("dau", 0);
            out.put("mau", 0);
        }
        return out;
    }

    /** Top N searches that yielded 0 products — drives shop-catalog priorities. */
    public List<Map<String, Object>> zeroResultSearches(int limit) {
        try {
            Instant week = Instant.now().minus(7, ChronoUnit.DAYS);
            Map<String, Integer> count = new LinkedHashMap<>();
            for (AnalyticsEvent e : events.findByTypeAndTsAfter("search", week)) {
                if (e.getQuery() == null) continue;
                if (e.getResultCount() == null || e.getResultCount() > 0) continue;
                count.merge(e.getQuery(), 1, Integer::sum);
            }
            return count.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .map(en -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("query", en.getKey());
                        row.put("hits", en.getValue());
                        return row;
                    })
                    .toList();
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    /** Click-through rate per seller = clicks / views in the last 7 days. */
    public List<Map<String, Object>> ctrPerShop(int limit) {
        try {
            Instant week = Instant.now().minus(7, ChronoUnit.DAYS);
            Map<String, int[]> per = new HashMap<>(); // siteSlug -> {clicks, productsViewedFromShop}

            // Map productId -> {set of siteSlugs} so we can attribute views
            // to every shop that listed the product.
            for (AnalyticsEvent e : events.findByTypeAndTsAfter("click", week)) {
                String slug = e.getSellerSlug();
                if (slug == null) continue;
                per.computeIfAbsent(slug, k -> new int[]{0, 0})[0]++;
            }
            // Approximate views per shop by counting how many products viewed
            // that contain the shop. Bounded by 5000 events scanned.
            int scanned = 0;
            for (AnalyticsEvent e : events.findByTypeAndTsAfter("view", week)) {
                if (++scanned > 5000) break;
                if (e.getProductId() == null) continue;
                Product p = products.findById(e.getProductId()).orElse(null);
                if (p == null || p.getPrices() == null) continue;
                Set<String> shops = new HashSet<>();
                p.getPrices().forEach(sp -> { if (sp.getSiteSlug() != null) shops.add(sp.getSiteSlug()); });
                for (String s : shops) per.computeIfAbsent(s, k -> new int[]{0, 0})[1]++;
            }

            List<Map<String, Object>> out = new ArrayList<>();
            per.forEach((slug, arr) -> {
                int clicks = arr[0];
                int views = arr[1];
                double ctr = views == 0 ? 0 : (double) clicks / views;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sellerSlug", slug);
                row.put("clicks", clicks);
                row.put("views", views);
                row.put("ctr", Math.round(ctr * 1000.0) / 10.0);
                out.add(row);
            });
            out.sort(Comparator.comparingDouble((Map<String, Object> m) -> (double) m.get("ctr")).reversed());
            return out.size() > limit ? out.subList(0, limit) : out;
        } catch (DataAccessException e) {
            return List.of();
        }
    }

    /** Search latency p50 / p95 / p99 from events in the last 24h. */
    public Map<String, Object> searchLatency() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Instant day = Instant.now().minus(1, ChronoUnit.DAYS);
            java.util.List<Long> samples = new java.util.ArrayList<>();
            for (AnalyticsEvent e : events.findByTypeAndTsAfter("search", day)) {
                if (e.getLatencyMs() != null && e.getLatencyMs() >= 0) samples.add(e.getLatencyMs());
            }
            java.util.Collections.sort(samples);
            int n = samples.size();
            out.put("samples", n);
            out.put("p50", n == 0 ? null : samples.get(Math.min(n - 1, n / 2)));
            out.put("p95", n == 0 ? null : samples.get((int) Math.min(n - 1L, Math.round(n * 0.95))));
            out.put("p99", n == 0 ? null : samples.get((int) Math.min(n - 1L, Math.round(n * 0.99))));
        } catch (DataAccessException e) {
            out.put("samples", 0);
        }
        return out;
    }

    /** Last N searches with their result count + latency — feeds the search log tab. */
    public java.util.List<Map<String, Object>> recentSearches(int limit) {
        try {
            Instant week = Instant.now().minus(7, ChronoUnit.DAYS);
            java.util.List<AnalyticsEvent> all = events.findByTypeAndTsAfter("search", week);
            all.sort((a, b) -> b.getTs().compareTo(a.getTs()));
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (AnalyticsEvent e : all) {
                if (out.size() >= limit) break;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", e.getQuery());
                row.put("resultCount", e.getResultCount());
                row.put("latencyMs", e.getLatencyMs());
                row.put("ts", e.getTs());
                row.put("anonId", e.getAnonId());
                row.put("userId", e.getUserId());
                out.add(row);
            }
            return out;
        } catch (DataAccessException e) {
            return java.util.List.of();
        }
    }

    /** Last N autosuggest picks — "typed X, clicked product Y" — for the search log. */
    public java.util.List<Map<String, Object>> recentSuggestClicks(int limit) {
        try {
            Instant week = Instant.now().minus(7, ChronoUnit.DAYS);
            java.util.List<AnalyticsEvent> all = events.findByTypeAndTsAfter("suggest_click", week);
            all.sort((a, b) -> b.getTs().compareTo(a.getTs()));
            java.util.List<Map<String, Object>> out = new java.util.ArrayList<>();
            for (AnalyticsEvent e : all) {
                if (out.size() >= limit) break;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", e.getQuery());
                row.put("productId", e.getProductId());
                row.put("productName", e.getProductName());
                row.put("ts", e.getTs());
                row.put("anonId", e.getAnonId());
                out.add(row);
            }
            return out;
        } catch (DataAccessException e) {
            return java.util.List.of();
        }
    }

    /** Top viewed and top clicked products in the last 7 days. */
    public Map<String, Object> topProducts(int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Instant week = Instant.now().minus(7, ChronoUnit.DAYS);
            Map<String, Integer> views = countBy(events.findByTypeAndTsAfter("view", week), AnalyticsEvent::getProductId);
            Map<String, Integer> clicks = countBy(events.findByTypeAndTsAfter("click", week), AnalyticsEvent::getProductId);
            out.put("topViewed", hydrate(views, limit));
            out.put("topClicked", hydrate(clicks, limit));
        } catch (DataAccessException e) {
            out.put("topViewed", List.of());
            out.put("topClicked", List.of());
        }
        return out;
    }

    private List<Map<String, Object>> hydrate(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .filter(e -> e.getKey() != null)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(en -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    Product p = products.findById(en.getKey()).orElse(null);
                    row.put("id", en.getKey());
                    if (p != null) {
                        row.put("name", p.getName());
                        row.put("imageUrl", p.getImageUrl());
                        row.put("category", p.getCategory());
                        row.put("lowestPrice", p.getLowestPrice());
                    }
                    row.put("count", en.getValue());
                    return row;
                })
                .toList();
    }

    private static Map<String, Integer> countBy(List<AnalyticsEvent> events, java.util.function.Function<AnalyticsEvent, String> key) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (AnalyticsEvent e : events) {
            String k = key.apply(e);
            if (k == null) continue;
            out.merge(k, 1, Integer::sum);
        }
        return out;
    }

    private static Set<String> distinctAnonIds(List<AnalyticsEvent> evts) {
        Set<String> out = new HashSet<>();
        for (AnalyticsEvent e : evts) {
            if (e.getAnonId() != null) out.add(e.getAnonId());
            else if (e.getIpHash() != null) out.add("ip:" + e.getIpHash());
        }
        return out;
    }
}
