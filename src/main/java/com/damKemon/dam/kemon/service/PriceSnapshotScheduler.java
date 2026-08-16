package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
    private static final int WRITE_BATCH_SIZE = 1000;

    private final MongoTemplate mongo;
    private final PriceHistoryRepository priceHistoryRepository;
    private final AppRole appRole;

    @Value("${price-history.enabled:true}")
    private boolean enabled;

    public PriceSnapshotScheduler(MongoTemplate mongo,
                                  PriceHistoryRepository priceHistoryRepository,
                                  AppRole appRole) {
        this.mongo = mongo;
        this.priceHistoryRepository = priceHistoryRepository;
        this.appRole = appRole;
    }

    @Scheduled(cron = "${price-history.snapshot-cron:0 0 3 * * *}")
    public void snapshot() {
        if (!enabled) {
            log.debug("PriceSnapshotScheduler disabled via config");
            return;
        }
        if (!appRole.isWeb()) return;
        long t0 = System.currentTimeMillis();
        LocalDateTime now = LocalDateTime.now();
        List<PriceHistory> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        int productCount = 0;
        int pointCount = 0;
        try {
            Query query = new Query();
            query.fields().include("_id").include("prices");
            try (Stream<Product> stream = mongo.stream(query, Product.class)) {
                var products = stream.iterator();
                while (products.hasNext()) {
                    Product p = products.next();
                    productCount++;
                    if (p.getPrices() == null) continue;
                    for (SitePrice sp : p.getPrices()) {
                        if (sp == null || sp.getPrice() == null || sp.getPrice() <= 0) continue;
                        batch.add(PriceHistory.builder()
                                .productId(p.getId())
                                .siteName(sp.getSiteName())
                                .price(sp.getPrice())
                                .currency(sp.getCurrency() == null ? "BDT" : sp.getCurrency())
                                .recordedAt(now)
                                .build());
                        if (batch.size() >= WRITE_BATCH_SIZE) {
                            priceHistoryRepository.saveAll(batch);
                            pointCount += batch.size();
                            batch.clear();
                        }
                    }
                }
            }
            if (!batch.isEmpty()) {
                priceHistoryRepository.saveAll(batch);
                pointCount += batch.size();
                batch.clear();
            }
            if (pointCount == 0) {
                log.info("PriceSnapshotScheduler: nothing to snapshot");
                return;
            }
            log.info("PriceSnapshotScheduler: persisted {} price points from {} products in {}ms",
                    pointCount, productCount, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("PriceSnapshotScheduler: snapshot stopped after {} products / {} points: {}",
                    productCount, pointCount, e.getMessage());
        }
    }
}
