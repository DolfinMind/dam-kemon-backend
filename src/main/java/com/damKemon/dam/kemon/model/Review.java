package com.damKemon.dam.kemon.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
 *   <li>{@code source = "community"} — submitted by a signed-in shopper on Damkemon.
 *       Keyed by {@link #userId}; carries the trust/delivery
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
    /** Signed-in author. Null for imported and clearly-labelled sample rows. */
    @Indexed
    private String userId;
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

    /** Reddit/Stack Overflow style voting. score is always upvotes - downvotes. */
    @Builder.Default
    private Integer upvoteCount = 0;
    @Builder.Default
    private Integer downvoteCount = 0;
    @Builder.Default
    private Integer score = 0;

    /** Author points at the time this review was last voted on. */
    @Builder.Default
    private Integer authorReputation = 1;

    /** Verified purchase, or 5+ net votes from an author with 50+ reputation. */
    @Builder.Default
    private Boolean trusted = false;

    /** "community" | "scraped" | "delivery_report". */
    @Builder.Default
    private String source = "scraped";

    /** Moderation state: "published" | "flagged" | "hidden". */
    @Builder.Default
    private String status = "published";

    /**
     * Internal marker left by the pre-launch synthetic-review seeder. Seeded
     * rows are retained only so the exact rollback remains possible; public
     * product pages and totals must never present them as customer evidence.
     */
    @JsonIgnore
    private String seedBatch;
}
