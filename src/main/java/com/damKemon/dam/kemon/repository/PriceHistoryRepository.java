package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.PriceHistory;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PriceHistoryRepository extends MongoRepository<PriceHistory, String> {
    List<PriceHistory> findByProductIdOrderByRecordedAtDesc(String productId);
    List<PriceHistory> findByProductIdAndSiteName(String productId, String siteName);
}
