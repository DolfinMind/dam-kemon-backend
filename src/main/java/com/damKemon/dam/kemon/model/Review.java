package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A product review. Two origins share this collection:
 * <ul>
 *   <li>{@code source = "scraped"} — pulled from a shop's product page by the
 *       indexer. Has {@link #siteName} but no community fields.</li>
 *   <li>{@code source = "community"} — submitted by a shopper on Damkemon.
 *       Anonymous (keyed by {@link #anonId}); carries the trust/delivery
 *       signals that feed {@code ShopTrust} via {@code TrustService}.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "reviews")
public class Review {
    @Id
    private String id;

    @Indexed
    private String productId;
    private String siteName;
    private String reviewerName;
    private Integer rating;
    private String title;
    private String content;
    private LocalDateTime reviewDate;
    private Boolean verified;

    // ─── Community-review fields ───

    /** Shop the reviewer bought from — joins to {@code ShopTrust.shopSlug}. */
    private String shopSlug;

    /** Anonymous browser id used for one-review-per-product dedup. */
    private String anonId;

    /** Days the reviewer waited for delivery (feeds the delivery estimate). */
    private Integer deliveryDaysReported;

    /** Whether the reviewer would recommend this seller. */
    private Boolean wouldRecommend;

    /** Seller trust vote: "up" | "down" | null. */
    private String trustVote;

    @Builder.Default
    private Integer helpfulCount = 0;

    /** "community" | "scraped" | "delivery_report". */
    @Builder.Default
    private String source = "scraped";

    /** Moderation state: "published" | "flagged" | "hidden". */
    @Builder.Default
    private String status = "published";
}
