package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.ScrapingJob;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScrapingJobRepository extends MongoRepository<ScrapingJob, String> {
    List<ScrapingJob> findByStatus(String status);
    List<ScrapingJob> findTop10ByOrderByStartedAtDesc();
}
