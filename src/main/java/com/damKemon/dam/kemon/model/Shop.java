package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A Bangladesh e-commerce shop we crawl nightly. Seeded from
 * {@code resources/shops.json} at boot; rows can be updated by hand from
 * the admin endpoints.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "shops")
public class Shop {

    @Id
    private String id;

    /** Display name, e.g. "Apple Gadgets BD". */
    @Indexed(unique = true)
    private String slug;

    private String name;

    /** Root URL, e.g. {@code https://www.applegadgetsbd.com}. */
    private String baseUrl;

    /**
     * Sitemap entry-point. Either a single {@code sitemap.xml} or a
     * {@code sitemap_index.xml}. Null means "discover via the search
     * fallback" (slower, narrower coverage).
     */
    private String sitemapUrl;

    /**
     * Merchant-published product feed URL — Shopify {@code /products.json},
     * WooCommerce Store API, or a Google Merchant / RSS XML feed. When set,
     * {@code FeedSyncService} pulls it on a schedule (no scraping) and merges the
     * items into the catalog, adding products + sellers.
     */
    private String feedUrl;

    /**
     * Search URL template with {@code {q}} as the query placeholder, used as
     * fallback when {@code sitemapUrl} is null or returned 0 product URLs.
     * Example: {@code https://www.applegadgetsbd.com/?s={q}}.
     */
    private String searchUrlTemplate;

    /** "wordpress" | "shopify" | "magento" | "opencart" | "custom" */
    private String platform;

    /**
     * True for SPA shops (Daraz, Pickaboo, Chaldal, Aarong) where prices /
     * product cards are hydrated by JavaScript. The crawler/extractor uses
     * Playwright (BrowserFetcher) for these instead of jsoup.
     */
    @Builder.Default
    private Boolean requiresJs = false;

    /**
     * Categories the shop is known to carry, lower-case. Used by the search
     * router to prefer shops matching the detected query category.
     */
    @Builder.Default
    private List<String> categories = new ArrayList<>();

    /** "active" | "draft" | "blocked" | "dormant" */
    @Builder.Default
    private String status = "active";

    /** Last successful indexer run, null if never indexed. */
    private LocalDateTime lastIndexedAt;

    /** Products extracted from the most recent successful run. */
    @Builder.Default
    private Integer lastIndexedCount = 0;

    /** Most recent error message, if {@code status == "blocked"}. */
    private String lastError;

    /**
     * Sliding window of the last 7 runs (most recent first). Drives the
     * health score and auto-disable rule. Capped at 7 entries.
     */
    @Builder.Default
    private List<RunStat> recentRuns = new ArrayList<>();

    /**
     * Rolled-up health from {@link #recentRuns}: "active", "degraded",
     * "dormant", "blocked". {@link #status} stays operator-controlled;
     * this field is recomputed every run.
     */
    @Builder.Default
    private String health = "active";

    /**
     * Consecutive failed runs. Reset to 0 on any success. Used to trigger
     * the retry queue + auto-disable after 3 in a row.
     */
    @Builder.Default
    private Integer consecutiveFailures = 0;

    /** True when this shop should be retried in the next retry pass. */
    @Builder.Default
    private Boolean needsRetry = false;

    /**
     * Slug of the extractor the auto-learning service decided works best
     * for this shop's product pages. Set by {@code ScraperLearningService}
     * after a probe run; honored by {@code ExtractorRegistry.pickForShop}.
     * Null means "fall back to URL-based routing in the registry".
     *
     * <p>Lets the engine self-heal: when a previously broken shop suddenly
     * works because a competing extractor (Generic JSON-LD, WooCommerce
     * theme path) now matches its HTML, future runs lock onto that
     * extractor without a redeploy.
     */
    private String preferredExtractor;

    /** Product-feed endpoint auto-discovered by ApiSniffer; replayed cheaply (no browser) by DiscoveredApiHarvester. */
    private String discoveredApiUrl;

    /**
     * Auto-detected platform fingerprint from a learning probe. Distinct
     * from the operator-set {@link #platform} — this is "what the page
     * actually looks like" (e.g. WordPress body class, Shopify JS globals,
     * OpenCart route= URLs). Used for diagnostics and to flag mismatches
     * between {@code shops.json} and reality.
     */
    private String detectedPlatform;

    /**
     * Last time {@code ScraperLearningService} ran a probe on this shop.
     * Throttled to once per 24h to avoid burning the same shop's quota
     * on every retry pass.
     */
    private LocalDateTime lastLearnedAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** One row of the sliding window in {@link #recentRuns}. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RunStat {
        private LocalDateTime at;
        private Integer count;
        private String error;
    }
}
