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

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final MongoTemplate mongoTemplate;

    public ShopCatalogBootstrap(ShopRepository shopRepository, MongoTemplate mongoTemplate) {
        this.shopRepository = shopRepository;
        this.mongoTemplate = mongoTemplate;
    }

    // Runs on ApplicationReadyEvent, not @PostConstruct: @PostConstruct can fire
    // before the Mongo connection is ready on a cold/slow box, throw, and silently
    // no-op — leaving the shops collection (and so both the public and admin shop
    // views) empty. By ready-time the data layer is up, so the upsert lands.
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        // Heal first: two JVMs (web + worker) booting at once used to race this
        // seed's exists-check and double-insert a slug. Once duplicated,
        // findBySlug(slug) THROWS (non-unique result) — 500ing the admin hide
        // button and aborting this very loop. Collapse dupes, then lock the
        // invariant with a unique index so the race can never re-create them.
        dedupeShops();
        ensureUniqueSlugIndex();

        List<ShopEntry> entries = loadCatalog();
        if (entries.isEmpty()) return;

        int inserted = 0, updated = 0;
        for (ShopEntry e : entries) {
            try {
                List<Shop> matches = shopRepository.findAllBySlug(e.slug);
                Shop shop = matches.isEmpty()
                        ? Shop.builder()
                                .slug(e.slug)
                                .status("active")
                                .createdAt(LocalDateTime.now())
                                .build()
                        : matches.get(0);
                boolean existed = !matches.isEmpty();
                shop.setName(e.name);
                shop.setBaseUrl(e.baseUrl);
                shop.setSitemapUrl(e.sitemapUrl);
                shop.setSearchUrlTemplate(e.searchUrlTemplate);
                shop.setPlatform(e.platform);
                shop.setCategories(e.categories == null ? new ArrayList<>() : e.categories);
                shop.setRequiresJs(Boolean.TRUE.equals(e.requiresJs));
                shop.setUpdatedAt(LocalDateTime.now());
                shopRepository.save(shop);
                if (existed) updated++; else inserted++;
            } catch (org.springframework.dao.DuplicateKeyException race) {
                // the OTHER JVM inserted this slug between our check and save — fine
                updated++;
            } catch (DataAccessException ex) {
                log.warn("Shop catalog seed: Mongo unreachable ({}). Skipping further seeds.", ex.getMessage());
                return;
            } catch (Exception ex) {
                log.warn("Shop catalog seed: failed to upsert '{}': {}", e.slug, ex.getMessage());
            }
        }
        log.info("Shop catalog: {} inserted, {} updated, {} total in DB", inserted, updated, inserted + updated);
    }

    /**
     * Collapse shops sharing a slug into one survivor: the doc with real crawl
     * history wins (latest lastIndexedAt, then oldest createdAt as tiebreak).
     * An operator's hide on ANY duplicate is carried onto the survivor — intent
     * must outlive the cleanup. Loser docs are deleted.
     */
    void dedupeShops() {
        List<Shop> all;
        try {
            all = shopRepository.findAll();
        } catch (DataAccessException e) {
            return;   // Mongo not up yet — seed() will bail on its own
        }
        Map<String, List<Shop>> bySlug = new LinkedHashMap<>();
        for (Shop s : all) {
            if (s.getSlug() == null) continue;
            bySlug.computeIfAbsent(s.getSlug(), k -> new ArrayList<>()).add(s);
        }
        for (Map.Entry<String, List<Shop>> e : bySlug.entrySet()) {
            List<Shop> dupes = e.getValue();
            if (dupes.size() < 2) continue;
            dupes.sort(Comparator
                    .comparing(Shop::getLastIndexedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                    .thenComparing(Shop::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())));
            Shop survivor = dupes.get(0);
            // A hide on ANY duplicate transfers to the survivor, keeping who set it.
            Shop hidden = dupes.stream()
                    .filter(d -> d.getStatus() != null && !"active".equals(d.getStatus()))
                    .findFirst().orElse(null);
            if (hidden != null && (survivor.getStatus() == null || "active".equals(survivor.getStatus()))) {
                survivor.setStatus(hidden.getStatus());
                survivor.setBlockedBy(hidden.getBlockedBy());
            }
            try {
                shopRepository.save(survivor);
                for (Shop loser : dupes.subList(1, dupes.size())) shopRepository.delete(loser);
                log.warn("Shop catalog: deduped slug '{}' — kept {}, deleted {} duplicate row(s)",
                        e.getKey(), survivor.getId(), dupes.size() - 1);
            } catch (DataAccessException ex) {
                log.warn("Shop catalog: dedupe of '{}' failed: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    /** Unique index on slug — makes the boot-race double-insert impossible. */
    private void ensureUniqueSlugIndex() {
        try {
            mongoTemplate.indexOps(Shop.class)
                    .ensureIndex(new Index().on("slug", Sort.Direction.ASC).unique());
        } catch (Exception e) {
            // Leftover dupes (dedupe failed mid-way) block index creation; the
            // next boot's dedupe pass gets another shot. Never fail startup.
            log.warn("Shop catalog: unique slug index not created yet: {}", e.getMessage());
        }
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
