package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On boot, reads {@code resources/shops.json} and upserts each entry into
 * the {@code shops} collection. Tracks each shop by {@link Shop#getSlug()}
 * (unique).
 *
 * <p>Already-existing shops get their static fields refreshed (name, URLs,
 * categories) without disturbing crawl-state fields (lastIndexedAt,
 * lastIndexedCount, lastError, status). This makes the JSON the source of
 * truth for the shop catalog while preserving runtime state.
 *
 * <p>If MongoDB is unreachable on boot the bootstrap silently no-ops — the
 * scheduler will simply find no active shops to crawl until Mongo is back.
 */
@Component
public class ShopCatalogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ShopCatalogBootstrap.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ShopRepository shopRepository;

    public ShopCatalogBootstrap(ShopRepository shopRepository) {
        this.shopRepository = shopRepository;
    }

    // Runs on ApplicationReadyEvent, not @PostConstruct: @PostConstruct can fire
    // before the Mongo connection is ready on a cold/slow box, throw, and silently
    // no-op — leaving the shops collection (and so both the public and admin shop
    // views) empty. By ready-time the data layer is up, so the upsert lands.
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        List<ShopEntry> entries = loadCatalog();
        if (entries.isEmpty()) return;

        int inserted = 0, updated = 0;
        for (ShopEntry e : entries) {
            try {
                Optional<Shop> existing = shopRepository.findBySlug(e.slug);
                Shop shop = existing.orElseGet(() -> Shop.builder()
                        .slug(e.slug)
                        .status("active")
                        .createdAt(LocalDateTime.now())
                        .build());
                shop.setName(e.name);
                shop.setBaseUrl(e.baseUrl);
                shop.setSitemapUrl(e.sitemapUrl);
                shop.setSearchUrlTemplate(e.searchUrlTemplate);
                shop.setPlatform(e.platform);
                shop.setCategories(e.categories == null ? new ArrayList<>() : e.categories);
                shop.setRequiresJs(Boolean.TRUE.equals(e.requiresJs));
                shop.setUpdatedAt(LocalDateTime.now());
                shopRepository.save(shop);
                if (existing.isPresent()) updated++; else inserted++;
            } catch (DataAccessException ex) {
                log.warn("Shop catalog seed: Mongo unreachable ({}). Skipping further seeds.", ex.getMessage());
                return;
            } catch (Exception ex) {
                log.warn("Shop catalog seed: failed to upsert '{}': {}", e.slug, ex.getMessage());
            }
        }
        log.info("Shop catalog: {} inserted, {} updated, {} total in DB", inserted, updated, inserted + updated);
    }

    private List<ShopEntry> loadCatalog() {
        try (InputStream in = new ClassPathResource("shops.json").getInputStream()) {
            List<ShopEntry> entries = MAPPER.readValue(in, new TypeReference<List<ShopEntry>>() {});
            log.info("Shop catalog: read {} entries from shops.json", entries.size());
            return entries;
        } catch (Exception e) {
            log.warn("Could not load shops.json: {}", e.getMessage());
            return List.of();
        }
    }

    /** Plain DTO matching the JSON shape. */
    public static class ShopEntry {
        public String slug;
        public String name;
        public String baseUrl;
        public String sitemapUrl;
        public String searchUrlTemplate;
        public String platform;
        public List<String> categories;
        public Boolean requiresJs;
    }
}
