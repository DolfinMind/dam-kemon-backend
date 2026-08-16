package com.damKemon.dam.kemon.scraper;

import com.damKemon.dam.kemon.model.Shop;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Routes a discovered URL to the right {@link ProductExtractor}. Site-specific
 * extractors (Daraz, Pickaboo, Startech) get first dibs via
 * {@link ProductExtractor#supports(String)}; the {@link GenericProductExtractor}
 * is the universal fallback.
 *
 * <p>When a {@link Shop} has a {@code preferredExtractor} set by the
 * auto-learning service, {@link #pickForShop} short-circuits the
 * URL-supports loop and uses that extractor directly. That is how the
 * engine "remembers" what works for each shop across runs.
 */
@Service
public class ExtractorRegistry {

    private final List<ProductExtractor> siteSpecific;
    private final GenericProductExtractor generic;

    public ExtractorRegistry(List<ProductExtractor> all, GenericProductExtractor generic) {
        this.generic = generic;
        // Site-specific = anything that isn't the generic fallback
        this.siteSpecific = all.stream()
                .filter(e -> e != generic)
                .collect(Collectors.toList());
    }

    /** Pick the extractor for this URL. Never null. */
    public ProductExtractor pick(String url) {
        if (url == null) return generic;
        for (ProductExtractor e : siteSpecific) {
            if (e.supports(url)) return e;
        }
        return generic;
    }

    /**
     * URL routing with a shop-specific override. Used by the indexer when
     * the shop has a learned {@link Shop#getPreferredExtractor()} — that
     * extractor wins over URL-pattern routing for any URL on this shop's
     * domain. If the preferred extractor isn't registered (e.g. removed
     * between deploys) we silently fall back to {@link #pick}.
     */
    public ProductExtractor pickForShop(String url, Shop shop) {
        if (shop != null && shop.getPreferredExtractor() != null && !shop.getPreferredExtractor().isBlank()) {
            String pref = shop.getPreferredExtractor();
            for (ProductExtractor e : all()) {
                if (pref.equalsIgnoreCase(e.getSiteSlug())) return e;
            }
        }
        return pick(url);
    }

    /** All registered extractors (site-specific + generic). Used by the dashboard. */
    public List<ProductExtractor> all() {
        return java.util.stream.Stream.concat(siteSpecific.stream(), java.util.stream.Stream.of(generic)).collect(Collectors.toList());
    }

    /** Distinct site names contributed by the site-specific extractors. */
    public Set<String> knownSiteNames() {
        return siteSpecific.stream().map(ProductExtractor::getSiteName).collect(Collectors.toSet());
    }
}
