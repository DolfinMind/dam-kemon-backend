package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.PendingOffer;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface PendingOfferRepository extends MongoRepository<PendingOffer, String> {
    List<PendingOffer> findByStatusOrderBySubmittedAtDesc(String status);
    boolean existsByProductIdAndUrlAndStatus(String productId, String url, String status);
}
