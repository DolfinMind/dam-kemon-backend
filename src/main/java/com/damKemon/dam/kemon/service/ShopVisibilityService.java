package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The public-visibility switch for shops. A shop whose {@code status} is not
 * "active" (operator hide, auto health-block, dormant, draft) has its offers
 * stripped from every public product response — search, browse, product
 * detail. This is what makes the admin console's per-shop Power button
 * actually SHOW/HIDE the shop's products, instead of only stopping the crawl.
 *
 * <p>The non-active slug set is tiny (shops collection is ~60 rows) and read
 * on a 30s in-memory TTL, so per-request cost is a set lookup.
 */
@Service
public class ShopVisibilityService {

    private static final long TTL_MS = 30_000;

    private final ShopRepository shops;

    private volatile Set<String> hidden = Set.of();
    private volatile long loadedAt = 0;

    public ShopVisibilityService(ShopRepository shops) {
        this.shops = shops;
    }

    /** Lower-cased slugs of every non-active shop. Empty set on DB errors. */
    public Set<String> hiddenSlugs() {
        long now = System.currentTimeMillis();
        if (now - loadedAt > TTL_MS) {
            try {
                Set<String> next = new HashSet<>();
                for (Shop s : shops.findAll()) {
                    if (s.getStatus() != null && !"active".equals(s.getStatus()) && s.getSlug() != null) {
                        next.add(s.getSlug().toLowerCase());
                    }
                }
                hidden = next;
            } catch (Exception ignored) {
                // keep the previous set — visibility must never break search
            }
            loadedAt = now;
        }
        return hidden;
    }

    private static boolean isHidden(SitePrice sp, Set<String> hidden) {
        if (sp == null) return true;
        String slug = sp.getSiteSlug() != null ? sp.getSiteSlug() : sp.getSiteName();
        return slug != null && hidden.contains(slug.toLowerCase());
    }

    /**
     * Search-path filter. Returns the input list untouched when nothing is
     * hidden (the common case). Otherwise: products whose EVERY offer is from a
     * hidden shop are dropped; mixed products are replaced with a pruned COPY —
     * never mutated, because search candidates can be shared in-memory index
     * payloads (trigram) that must stay pristine for the next request.
     */
    public List<Product> filterForPublic(List<Product> products) {
        Set<String> hide = hiddenSlugs();
        if (hide.isEmpty() || products == null || products.isEmpty()) return products;
        List<Product> out = new ArrayList<>(products.size());
        for (Product p : products) {
            List<SitePrice> prices = p.getPrices();
            if (prices == null || prices.isEmpty()) { out.add(p); continue; }
            int visible = 0;
            for (SitePrice sp : prices) if (!isHidden(sp, hide)) visible++;
            if (visible == prices.size()) { out.add(p); continue; }
            if (visible == 0) continue;                       // fully hidden — drop
            Product copy = new Product();
            BeanUtils.copyProperties(p, copy);
            List<SitePrice> kept = new ArrayList<>(visible);
            for (SitePrice sp : prices) if (!isHidden(sp, hide)) kept.add(sp);
            copy.setPrices(kept);
            BulkIndexer.recomputeAggregates(copy);
            out.add(copy);
        }
        return out;
    }

    /**
     * Detail/browse-path strip for a product FRESHLY read from Mongo (per-request
     * instance, never saved back — same contract as ProductService#dedupeOffers).
     */
    public void stripInPlace(Product p) {
        Set<String> hide = hiddenSlugs();
        if (hide.isEmpty() || p == null || p.getPrices() == null || p.getPrices().isEmpty()) return;
        List<SitePrice> kept = new ArrayList<>(p.getPrices().size());
        for (SitePrice sp : p.getPrices()) if (!isHidden(sp, hide)) kept.add(sp);
        if (kept.size() == p.getPrices().size()) return;
        p.setPrices(kept);
        if (kept.isEmpty()) {
            p.setLowestPrice(null);
            p.setHighestPrice(null);
            p.setPriceVerdict(null);
        } else {
            BulkIndexer.recomputeAggregates(p);
        }
    }

    /** True when a product would be entirely invisible (every offer hidden). */
    public boolean fullyHidden(Product p) {
        Set<String> hide = hiddenSlugs();
        if (hide.isEmpty() || p == null || p.getPrices() == null || p.getPrices().isEmpty()) return false;
        for (SitePrice sp : p.getPrices()) if (!isHidden(sp, hide)) return false;
        return true;
    }
}
