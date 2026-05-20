package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.IndexerRunRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface IndexerRunRepository extends MongoRepository<IndexerRunRecord, String> {
    List<IndexerRunRecord> findAllByOrderByStartedAtDesc(Pageable pageable);
}
