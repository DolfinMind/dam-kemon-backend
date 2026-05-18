package com.damKemon.dam.kemon.scraper;

/**
 * Extracts a {@link ScrapedProduct} from a single product-page URL.
 *
 * <p>Two flavors of implementation:
 * <ul>
 *   <li>Site-specific extractors (Daraz, Pickaboo, Startech) hand-tuned with
 *       CSS selectors for the layout. {@link #supports(String)} matches by
 *       host.</li>
 *   <li>{@link GenericProductExtractor} that reads schema.org / Open Graph
 *       metadata and works on any well-formed e-commerce page. Used as a
 *       fallback for hosts no specific extractor claims.</li>
 * </ul>
 */
public interface ProductExtractor {

    /** Friendly site name, shown in the UI ("Daraz", "Pickaboo"). */
    String getSiteName();

    /** URL-safe slug ("daraz", "pickaboo"). For domain-unknown sites the
     *  generic extractor uses the host. */
    String getSiteSlug();

    /** True if this extractor wants to handle the given URL (e.g. by domain). */
    boolean supports(String url);

    /**
     * Extract a product, or {@code null} if the URL doesn't yield one
     * (404, bot block, missing price, etc).
     */
    ScrapedProduct extract(String url);
}
