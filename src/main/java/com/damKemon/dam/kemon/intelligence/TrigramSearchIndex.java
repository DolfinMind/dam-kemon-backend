package com.damKemon.dam.kemon.intelligence;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
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
public class TrigramSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(TrigramSearchIndex.class);

    private final ProductRepository productRepository;
    private final AtomicReference<TrigramIndex> indexRef = new AtomicReference<>(new TrigramIndex());

    @Value("${search.trigram.enabled:true}")
    private boolean enabled;

    public TrigramSearchIndex(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @PostConstruct
    public void buildOnStartup() {
        if (!enabled) return;
        try { rebuild(); }
        catch (Exception e) { log.warn("Trigram initial build failed: {}", e.getMessage()); }
    }

    /** Hourly refresh. Aligned to :05 so it never collides with the 03:00 cron. */
    @Scheduled(cron = "0 5 * * * *")
    public void hourlyRefresh() {
        if (!enabled) return;
        try { rebuild(); }
        catch (Exception e) { log.warn("Trigram hourly refresh failed: {}", e.getMessage()); }
    }

    /** Public entry point. Safe to call from admin endpoints after a reindex. */
    public void rebuild() {
        long t0 = System.nanoTime();
        TrigramIndex next = new TrigramIndex();
        int n = 0;
        try {
            for (Product p : productRepository.findAll()) {
                if (p.getId() == null || p.getName() == null) continue;
                // Index name + brand tokens so "apple iphone" still hits "iPhone 15 Pro"
                String indexable = p.getName();
                if (p.getBrands() != null && !p.getBrands().isEmpty()) {
                    indexable = indexable + " " + String.join(" ", p.getBrands());
                }
                next.add(p.getId(), indexable, p);
                n++;
            }
        } catch (DataAccessException e) {
            log.warn("Trigram rebuild aborted, keeping previous index: {}", e.getMessage());
            return;
        }
        indexRef.set(next);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("Trigram index rebuilt — {} products in {} ms", n, ms);
    }

    /** Top-K fuzzy matches above the given threshold, ranked by trigram-Jaccard. */
    public List<TrigramIndex.Hit> topK(String query, int k, double minScore) {
        if (!enabled) return List.of();
        TrigramIndex idx = indexRef.get();
        if (idx == null || idx.size() == 0) return List.of();
        List<TrigramIndex.Hit> raw = idx.topK(query, k);
        List<TrigramIndex.Hit> out = new ArrayList<>(raw.size());
        for (TrigramIndex.Hit h : raw) if (h.score() >= minScore) out.add(h);
        return out;
    }

    public int size() { return indexRef.get().size(); }

    public boolean isEnabled() { return enabled; }
}
