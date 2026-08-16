package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.ShopTrust;
import com.damKemon.dam.kemon.repository.ShopTrustRepository;
import com.damKemon.dam.kemon.service.TrustService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * On boot, reads {@code resources/shop-trust.json} and upserts a curated
 * trust/delivery/genuineness baseline for each shop into the
 * {@code shop_trust} collection. Mirrors {@link ShopCatalogBootstrap}.
 *
 * <p>Re-running refreshes only the editorial baseline fields (baseTrust,
 * delivery window, COD, returns, authenticity, warranty). Community
 * aggregates (ratings, trust votes, reported delivery days) are preserved,
 * then {@link #computedTrust} is recomputed so the seeded score reflects
 * any reviews collected since the last boot.
 *
 * <p>If MongoDB is unreachable on boot the bootstrap silently no-ops.
 */
@Component
public class ShopTrustBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ShopTrustBootstrap.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ShopTrustRepository repo;
    private final TrustService trustService;

    public ShopTrustBootstrap(ShopTrustRepository repo, TrustService trustService) {
        this.repo = repo;
        this.trustService = trustService;
    }

    // ApplicationReadyEvent, not @PostConstruct — see ShopCatalogBootstrap: avoids a
    // silent no-op when Mongo isn't ready yet at bean-init time on a cold box.
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        List<TrustEntry> entries = load();
        if (entries.isEmpty()) return;

        int inserted = 0, updated = 0;
        for (TrustEntry e : entries) {
            if (e.shopSlug == null || e.shopSlug.isBlank()) continue;
            try {
                Optional<ShopTrust> existing = repo.findByShopSlug(e.shopSlug);
                ShopTrust t = existing.orElseGet(() -> ShopTrust.builder().shopSlug(e.shopSlug).build());
                // Editorial baseline — always refreshed from JSON.
                t.setShopName(e.shopName);
                t.setBaseTrust(e.baseTrust);
                t.setDeliveryDaysMin(e.deliveryDaysMin);
                t.setDeliveryDaysMax(e.deliveryDaysMax);
                t.setCodAvailable(e.codAvailable);
                t.setReturnWindowDays(e.returnWindowDays);
                t.setReturnEase(e.returnEase);
                t.setAuthenticity(e.authenticity);
                t.setWarranty(e.warranty);
                t.setResponseTime(e.responseTime);
                // Community aggregates are left untouched; recompute the score.
                t.setComputedTrust(trustService.computeTrust(t));
                t.setUpdatedAt(LocalDateTime.now());
                repo.save(t);
                if (existing.isPresent()) updated++; else inserted++;
            } catch (DataAccessException ex) {
                log.warn("ShopTrust seed: Mongo unreachable ({}). Skipping further seeds.", ex.getMessage());
                return;
            } catch (Exception ex) {
                log.warn("ShopTrust seed: failed to upsert '{}': {}", e.shopSlug, ex.getMessage());
            }
        }
        log.info("Shop trust: {} inserted, {} updated, {} baselines total", inserted, updated, inserted + updated);

        // Fold scraped per-shop ratings into the scores so day-one numbers
        // reflect real catalog data, not just editorial baselines.
        try {
            int n = trustService.recomputeScrapedSignals();
            log.info("Shop trust: folded scraped ratings into {} shops", n);
        } catch (Exception e) {
            log.warn("Shop trust: scraped-signal recompute skipped ({})", e.getMessage());
        }
    }

    private List<TrustEntry> load() {
        try (InputStream in = new ClassPathResource("shop-trust.json").getInputStream()) {
            List<TrustEntry> entries = MAPPER.readValue(in, new TypeReference<List<TrustEntry>>() {});
            log.info("Shop trust: read {} baselines from shop-trust.json", entries.size());
            return entries;
        } catch (Exception e) {
            log.warn("Could not load shop-trust.json: {}", e.getMessage());
            return List.of();
        }
    }

    /** Plain DTO matching the JSON shape. */
    public static class TrustEntry {
        public String shopSlug;
        public String shopName;
        public Integer baseTrust;
        public Integer deliveryDaysMin;
        public Integer deliveryDaysMax;
        public Boolean codAvailable;
        public Integer returnWindowDays;
        public String returnEase;
        public String authenticity;
        public String warranty;
        public String responseTime;
    }
}
