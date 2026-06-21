package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AffiliateClick;
import com.damKemon.dam.kemon.model.MarketplaceSeller;
import com.damKemon.dam.kemon.model.Seller;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.MarketplaceSellerRepository;
import com.damKemon.dam.kemon.repository.SellerRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the public Seller directory ({@code sellers}) from data we already
 * collect, so the "Sellers" surface reflects the real breadth of the platform:
 *
 * <ul>
 *   <li>every active indexed <b>shop</b> → a {@code website} (or {@code
 *       marketplace} for Daraz/Othoba/etc.) seller;</li>
 *   <li>every captured marketplace storefront (Daraz per-seller offers rolled
 *       up into {@code marketplace_sellers}) → a {@code marketplace} seller.</li>
 * </ul>
 *
 * <p>F-commerce (Facebook/Instagram) sellers are NOT scraped — Facebook's ToS
 * forbids it — they arrive curated or opt-in via {@code POST
 * /api/admin/sellers/bulk} and the f-commerce self-listing form, and are left
 * untouched here.
 *
 * <p>Idempotent (dedupes by slug) and runs after the nightly harvest, so the
 * directory grows automatically as the catalog and marketplace coverage grow.
 */
@Service
public class SellerDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(SellerDirectoryService.class);

    /** Indexed shops that are themselves multi-vendor marketplaces. */
    private static final Set<String> MARKETPLACE_SLUGS =
            Set.of("daraz", "chaldal", "othoba", "priyoshop", "ajkerdeal", "bagdoom", "pickaboo");

    private final ShopRepository shops;
    private final MarketplaceSellerRepository marketplaceSellers;
    private final SellerRepository sellers;
    private final MongoTemplate mongo;

    @Value("${seller-sync.enabled:true}")
    private boolean enabled;

    public SellerDirectoryService(ShopRepository shops,
                                  MarketplaceSellerRepository marketplaceSellers,
                                  SellerRepository sellers,
                                  MongoTemplate mongo) {
        this.shops = shops;
        this.marketplaceSellers = marketplaceSellers;
        this.sellers = sellers;
        this.mongo = mongo;
    }

    /** Daily at 05:30, after the nightly indexer + marketplace harvests: refresh
     *  the directory, then re-rank it by real outbound-click engagement. */
    @Scheduled(cron = "${seller-sync.cron:0 30 5 * * *}")
    public void scheduled() {
        if (!enabled) return;
        syncOnce();
        recomputeOutboundClicks();
    }

    /**
     * Rank the directory by real engagement: total outbound clicks per shop (from
     * {@code affiliate_clicks}, keyed by the SitePrice siteSlug = the shop slug)
     * folded onto each Seller. A Seller's slug is slugify(shop name), so we bridge
     * shop.slug → seller via the shop's name. Returns the number updated; never throws.
     */
    public int recomputeOutboundClicks() {
        int updated = 0;
        try {
            Map<String, Long> byShopSlug = clicksByShopSlug();
            if (byShopSlug.isEmpty()) return 0;
            for (Shop s : shops.findAll()) {
                if (s == null || s.getSlug() == null) continue;
                Long n = byShopSlug.get(s.getSlug().toLowerCase());
                if (n == null || n <= 0) continue;
                String name = (s.getName() != null && !s.getName().isBlank()) ? s.getName() : s.getSlug();
                try {
                    Seller seller = sellers.findBySlug(slugify(name)).orElse(null);
                    if (seller == null) continue;
                    seller.setOutboundClicks(n.intValue());
                    seller.setLastSeen(LocalDateTime.now());
                    sellers.save(seller);
                    updated++;
                } catch (Exception e) {
                    log.debug("SellerDirectory: click-rank save failed for {}: {}", name, e.getMessage());
                }
            }
            log.info("SellerDirectory: outbound-click ranking updated for {} seller(s)", updated);
        } catch (Exception e) {
            log.warn("SellerDirectory: recomputeOutboundClicks failed: {}", e.getMessage());
        }
        return updated;
    }

    /** Total outbound clicks grouped by SitePrice siteSlug (lower-cased). */
    private Map<String, Long> clicksByShopSlug() {
        Map<String, Long> out = new HashMap<>();
        try {
            Aggregation agg = Aggregation.newAggregation(Aggregation.group("siteSlug").count().as("n"));
            for (Document d : mongo.aggregate(agg, AffiliateClick.class, Document.class)) {
                Object id = d.get("_id");
                Object n = d.get("n");
                if (id != null && n instanceof Number num) out.put(id.toString().toLowerCase(), num.longValue());
            }
        } catch (Exception e) {
            log.debug("SellerDirectory: clicksByShopSlug failed: {}", e.getMessage());
        }
        return out;
    }

    /** Upsert shops + marketplace storefronts into the seller directory.
     *  Returns the number of NEW sellers added. Never throws. */
    public int syncOnce() {
        int added = 0;
        try {
            for (Shop s : shops.findAll()) {
                if (s == null || !"active".equalsIgnoreCase(String.valueOf(s.getStatus()))) continue;
                String name = (s.getName() != null && !s.getName().isBlank()) ? s.getName() : s.getSlug();
                String slug = slugify(name);
                if (slug.length() < 2 || exists(slug)) continue;
                String type = MARKETPLACE_SLUGS.contains(String.valueOf(s.getSlug()).toLowerCase())
                        ? "marketplace" : "website";
                save(Seller.builder()
                        .name(name).slug(slug).type(type).url(s.getBaseUrl())
                        .categories(s.getCategories() != null ? s.getCategories() : new ArrayList<>())
                        .verified(true).source("catalog")
                        .joinedAt(LocalDateTime.now()).lastSeen(LocalDateTime.now())
                        .build());
                added++;
            }
            for (MarketplaceSeller m : marketplaceSellers.findAll()) {
                if (m == null || m.getSellerName() == null || m.getSellerName().isBlank()) continue;
                String slug = slugify(m.getSellerName());
                if (slug.length() < 2 || exists(slug)) continue;
                String mk = m.getMarketplace() != null ? m.getMarketplace() : "daraz";
                save(Seller.builder()
                        .name(m.getSellerName().trim()).slug(slug).type("marketplace")
                        .url(m.getSellerId() != null ? "https://www.daraz.com.bd/shop/" + m.getSellerId() : null)
                        .rating(m.getRatingAvg()).reviewCount(m.getReviewTotal())
                        .tags(new ArrayList<>(List.of(mk)))
                        .verified(false).source("marketplace")
                        .joinedAt(LocalDateTime.now()).lastSeen(LocalDateTime.now())
                        .build());
                added++;
            }
            log.info("SellerDirectory: synced — {} new, {} total in directory", added, safeCount());
        } catch (Exception e) {
            log.warn("SellerDirectory sync failed: {}", e.getMessage());
        }
        return added;
    }

    private boolean exists(String slug) {
        try { return sellers.findBySlug(slug).isPresent(); }
        catch (Exception e) { return true; }   // on error, don't risk a dup
    }

    private void save(Seller s) {
        try { sellers.save(s); }
        catch (Exception e) { log.debug("SellerDirectory: save failed for {}: {}", s.getSlug(), e.getMessage()); }
    }

    private long safeCount() {
        try { return sellers.count(); } catch (Exception e) { return -1; }
    }

    private static String slugify(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9\\s-]", " ")
                .replaceAll("\\s+", "-").replaceAll("-+", "-").replaceAll("^-|-$", "");
    }
}
