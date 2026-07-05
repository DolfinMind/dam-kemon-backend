package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.Shop;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ShopRepository extends MongoRepository<Shop, String> {
    Optional<Shop> findBySlug(String slug);
    /** Duplicate-safe variant: {@link #findBySlug} THROWS when a slug was ever
     *  double-inserted (pre-unique-index race). Use this on operator paths. */
    List<Shop> findAllBySlug(String slug);
    List<Shop> findByStatus(String status);
    List<Shop> findByCategoriesContaining(String category);
}
