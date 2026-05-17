package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures a daily snapshot of every Product's per-site prices into the
 * PriceHistory collection. This is what powers the price-history chart on
 * the product detail page.
 *
 * Default schedule: 03:00 local time every day.
 * Tunable via PRICE_HISTORY_CRON env var (Spring cron expression).
 * Disable entirely with PRICE_HISTORY_ENABLED=false.
 */
@Service
public class PriceSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(PriceSnapshotScheduler.class);

    private final ProductRepository productRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    @Value("${price-history.enabled:true}")
    private boolean enabled;

    public PriceSnapshotScheduler(ProductRepository productRepository,
                                  PriceHistoryRepository priceHistoryRepository) {
        this.productRepository = productRepository;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    @Scheduled(cron = "${price-history.snapshot-cron:0 0 3 * * *}")
    public void snapshot() {
        if (!enabled) {
            log.debug("PriceSnapshotScheduler disabled via config");
            return;
        }
        long t0 = System.currentTimeMillis();
        List<Product> all;
        try {
            all = productRepository.findAll();
        } catch (Exception e) {
            log.warn("PriceSnapshotScheduler: cannot load products: {}", e.getMessage());
            return;
        }

        List<PriceHistory> batch = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Product p : all) {
            if (p.getPrices() == null) continue;
            for (SitePrice sp : p.getPrices()) {
                if (sp.getPrice() == null) continue;
                batch.add(PriceHistory.builder()
                        .productId(p.getId())
                        .siteName(sp.getSiteName())
                        .price(sp.getPrice())
                        .currency(sp.getCurrency() == null ? "BDT" : sp.getCurrency())
                        .recordedAt(now)
                        .build());
            }
        }

        if (batch.isEmpty()) {
            log.info("PriceSnapshotScheduler: nothing to snapshot");
            return;
        }
        try {
            priceHistoryRepository.saveAll(batch);
            log.info("PriceSnapshotScheduler: persisted {} price points from {} products in {}ms",
                    batch.size(), all.size(), System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("PriceSnapshotScheduler: save failed: {}", e.getMessage());
        }
    }
}
