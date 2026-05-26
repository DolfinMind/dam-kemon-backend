package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.AffiliateClick;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface AffiliateClickRepository extends MongoRepository<AffiliateClick, String> {
    long countBySiteSlugAndTsAfter(String siteSlug, Instant after);
    long countByProductIdAndTsAfter(String productId, Instant after);
}
