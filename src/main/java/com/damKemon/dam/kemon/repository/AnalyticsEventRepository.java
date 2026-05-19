package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.AnalyticsEvent;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AnalyticsEventRepository extends MongoRepository<AnalyticsEvent, String> {

    List<AnalyticsEvent> findByTypeAndTsAfter(String type, Instant after);

    long countByTypeAndTsAfter(String type, Instant after);
}
