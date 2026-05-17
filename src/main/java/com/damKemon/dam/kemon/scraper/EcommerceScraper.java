package com.damKemon.dam.kemon.scraper;

import com.damKemon.dam.kemon.intelligence.ProductCategory;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public interface EcommerceScraper {
    String getSiteName();
    String getSiteSlug();
    String getBaseUrl();
    List<ScrapedProduct> search(String query);
    List<ScrapedReview> getReviews(String productUrl);
    boolean isAvailable();

    /**
     * Categories this site is known to carry. The engine will only route
     * queries to this scraper if the detected category overlaps (or if
     * {@link #handlesGeneralQueries()} returns true).
     *
     * Default: GENERAL only — a site claiming generic coverage but you should
     * override.
     */
    default Set<ProductCategory> getSupportedCategories() {
        return EnumSet.of(ProductCategory.GENERAL);
    }

    /**
     * True for general-purpose marketplaces (Daraz, Pickaboo) which carry
     * almost any product. False for specialized sites (Rokomari — books only,
     * Chaldal — groceries only).
     */
    default boolean handlesGeneralQueries() {
        return false;
    }
}
