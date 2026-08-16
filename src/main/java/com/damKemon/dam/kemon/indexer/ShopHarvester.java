package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;

import java.util.List;

/**
 * A shop whose catalog is pulled from a structured JSON API instead of the
 * usual sitemap → homepage → search-seed URL discovery + per-page extraction.
 *
 * <p>Used for SPA marketplaces (Chaldal, Daraz) that render client-side and
 * expose their own product API. {@link BulkIndexer} checks every registered
 * harvester per shop; the first one that {@link #supports(Shop)} the shop is
 * asked to {@link #harvest(Shop)} the products directly, and the result flows
 * through the normal cross-shop merge/persist path. Every other shop is
 * untouched, so adding a harvester can never regress the URL pipeline.
 */
public interface ShopHarvester {

    /** True if this harvester owns acquisition for the given shop. */
    boolean supports(Shop shop);

    /** Pull products straight from the shop's API. Never null; empty on failure. */
    List<ScrapedProduct> harvest(Shop shop);
}
