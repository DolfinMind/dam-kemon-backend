package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.SavedSearch;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SavedSearchRepository extends MongoRepository<SavedSearch, String> {
    List<SavedSearch> findByUserId(String userId);
}
