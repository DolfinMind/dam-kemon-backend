package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.AffiliateClick;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface AffiliateClickRepository extends MongoRepository<AffiliateClick, String> {
    long countBySiteSlugAndTsAfter(String siteSlug, Instant after);
    long countByProductIdAndTsAfter(String productId, Instant after);
    /** All outbound clicks since {@code after} — powers "saved this month". */
    long countByTsAfter(Instant after);

    /** Verified-buyer check: did this browser click out to buy this product? */
    boolean existsByAnonIdAndProductId(String anonId, String productId);
    boolean existsByAnonIdAndProductIdAndSiteSlug(String anonId, String productId, String siteSlug);
}
