package com.damKemon.dam.kemon.intelligence;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application-scoped trigram index over every Product.name. Powers two
 * things:
 *
 *   1. The fuzzy-fallback inside {@link com.damKemon.dam.kemon.service.CatalogSearchService}
 *      — when a query produces few/no regex matches we ask this index for
 *      the most similar product names ("ipone" → "iPhone 15 Pro Max").
 *   2. The typo-tolerant autocomplete in the search bar (same path).
 *
 * Rebuild strategy:
 *   • Once at startup (so the first search after a deploy is already fuzzy).
 *   • Every hour via {@link Scheduled} (cheap — Atlas free tier averages
 *     ~10k products in this catalog, ~3MB RSS).
 *   • Manually via {@link #rebuild()} after a bulk indexer run.
 *
 * Concurrency note: the underlying {@link TrigramIndex} is built off-thread
 * and swapped atomically via {@link AtomicReference}, so readers never see a
 * half-built index.
 */
@Service
public class TrigramSearchIndex implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TrigramSearchIndex.class);

    private final ProductRepository productRepository;
    private final AtomicReference<TrigramIndex> indexRef = new AtomicReference<>(new TrigramIndex());
    private volatile boolean ready;
    private volatile Instant lastSuccess;
    private volatile String lastFailure;

    public TrigramSearchIndex(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Build the lightweight index before Spring reports the application ready. */
    @Override
    public void run(ApplicationArguments args) {
        rebuild();
    }

    /** Index refresh. Every 6h by default: the catalog only changes on the nightly
     *  crawl, so the old hourly cadence was wasted CPU + a Mongo full-read + a 2×
     *  memory blip (new index built while the old is held) on the web JVM each hour.
     *  Aligned to :05 so it never collides with the crawl window. */
    @Scheduled(cron = "${search.trigram.cron:0 5 */6 * * *}")
    public void hourlyRefresh() {
        try { rebuild(); }
        catch (Exception e) { log.warn("Trigram hourly refresh failed: {}", e.getMessage()); }
    }

    /** Public entry point. Safe to call from admin endpoints after a reindex. */
    public synchronized void rebuild() {
        long t0 = System.nanoTime();
        TrigramIndex next = new TrigramIndex();
        try {
            List<Product> rows = productRepository.findAllSearchDocuments();
            for (Product p : rows) {
                if (p.getId() == null || p.getName() == null) continue;
                String indexable = p.getName();
                if (p.getBrands() != null && !p.getBrands().isEmpty()) {
                    indexable = indexable + " " + String.join(" ", p.getBrands());
                }
                next.add(p.getId(), indexable, p.getId());
            }
            if (next.size() == 0 && (!rows.isEmpty() || productRepository.count() > 0)) {
                throw new IllegalStateException("catalog is non-empty but trigram index is empty");
            }
        } catch (RuntimeException e) {
            lastFailure = e.getClass().getSimpleName();
            log.warn("Trigram rebuild aborted, keeping previous index: {}", e.getMessage());
            return;
        }
        indexRef.set(next);
        ready = true;
        lastSuccess = Instant.now();
        lastFailure = null;
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("Trigram index rebuilt — {} products in {} ms", next.size(), ms);
    }

    /** Top-K fuzzy matches above the given threshold, ranked by trigram-Jaccard. */
    public List<TrigramIndex.Hit> topK(String query, int k, double minScore) {
        TrigramIndex idx = indexRef.get();
        if (idx == null || idx.size() == 0) return List.of();
        List<TrigramIndex.Hit> raw = idx.topK(query, k);
        List<TrigramIndex.Hit> out = new ArrayList<>(raw.size());
        for (TrigramIndex.Hit h : raw) if (h.score() >= minScore) out.add(h);
        return out;
    }

    public int size() { return indexRef.get().size(); }

    public boolean isEnabled() { return true; }

    public boolean isReady() { return ready; }

    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", true);
        out.put("ready", isReady());
        out.put("size", size());
        if (lastSuccess != null) out.put("lastSuccess", lastSuccess.toString());
        if (lastFailure != null) out.put("lastFailure", lastFailure);
        return out;
    }
}
