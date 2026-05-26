package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.TextIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "products")
public class Product {
    @Id
    private String id;

    /** Display name, eg "Samsung Galaxy S24 Ultra 12/256GB". Indexed for text search. */
    @TextIndexed(weight = 3)
    private String name;

    @Indexed
    private String slug;

    /** Detected primary category (lower-case), eg "smartphone". */
    @Indexed
    private String category;

    private String imageUrl;

    @TextIndexed
    private String description;

    /** Detected brand tokens, eg ["samsung", "galaxy"]. Faceted. */
    @Builder.Default
    private List<String> brands = new ArrayList<>();

    /** Per-shop prices and URLs. Each entry corresponds to one Shop. */
    @Builder.Default
    private List<SitePrice> prices = new ArrayList<>();

    @Indexed
    private Double lowestPrice;
    private Double highestPrice;
    private Double averageRating;
    private Integer totalReviews;

    /** Last time a per-shop scrape refreshed this product. */
    private LocalDateTime lastScraped;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * True when an advertiser has paid for placement. Sponsored products are
     * still ranked alongside organic results but the API surfaces them
     * separately so the UI can render a "Sponsored" chip.
     */
    @Indexed
    private Boolean sponsored;

    /**
     * UTC instant the sponsorship expires. We cheaply gate visibility on
     * read instead of running a cleanup job — Mongo TTL would also work but
     * we want to keep the historical record for billing reconciliation.
     */
    private LocalDateTime sponsoredUntil;

    /** Sponsor tier (1=top of all, 2=top of category, 3=anywhere). Higher = lower priority. */
    private Integer sponsorTier;
}
