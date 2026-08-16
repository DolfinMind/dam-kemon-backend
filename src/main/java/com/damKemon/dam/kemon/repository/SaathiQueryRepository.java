package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.SaathiQuery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface SaathiQueryRepository extends MongoRepository<SaathiQuery, String> {

    List<SaathiQuery> findBySaathiIdOrderByTsDesc(String saathiId, Pageable pageable);
    long countBySaathiIdAndTsAfter(String saathiId, Instant after);
}
