package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.Review;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewRepository extends MongoRepository<Review, String> {
    List<Review> findByProductId(String productId);
    List<Review> findByProductIdAndSiteName(String productId, String siteName);

    /** Newest first — what the product Reviews tab renders. */
    List<Review> findByProductIdOrderByReviewDateDesc(String productId);

    /** Published reviews only (hides flagged/hidden) — public Reviews tab. */
    List<Review> findByProductIdAndStatusOrderByReviewDateDesc(String productId, String status);

    /** One-community-review-per-browser dedup guard. */
    long countByProductIdAndAnonId(String productId, String anonId);

    /** Dedup guard for the lightweight delivery-report path. */
    long countByProductIdAndAnonIdAndSource(String productId, String anonId, String source);

    /** Moderation queue — flagged/hidden reviews for the admin view. */
    List<Review> findByStatusOrderByReviewDateDesc(String status);
}
