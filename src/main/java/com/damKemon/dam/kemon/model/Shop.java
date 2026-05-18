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
     * Search URL template with {@code {q}} as the query placeholder, used as
     * fallback when {@code sitemapUrl} is null or returned 0 product URLs.
     * Example: {@code https://www.applegadgetsbd.com/?s={q}}.
     */
    private String searchUrlTemplate;

    /** "wordpress" | "shopify" | "magento" | "opencart" | "custom" */
    private String platform;

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

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
