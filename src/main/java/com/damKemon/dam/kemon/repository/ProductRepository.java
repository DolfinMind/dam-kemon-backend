package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.Product;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {
    List<Product> findByNameContainingIgnoreCase(String name);
    List<Product> findByCategory(String category);
    Optional<Product> findBySlug(String slug);

    /** True Mongo text search over the indexed {@code name + description} fields. */
    @Query("{ $text: { $search: ?0 } }")
    List<Product> textSearch(String text, Pageable pageable);

    /** Existence-by-URL lookup the indexer uses to upsert per-shop prices. */
    @Query("{ 'prices.productUrl': ?0 }")
    Optional<Product> findByPriceUrl(String url);

    long countByCategory(String category);

    /** Used by autosuggest — prefix match on name. Case-insensitive via regex. */
    @Query("{ 'name': { $regex: ?0, $options: 'i' } }")
    List<Product> findByNamePrefix(String prefix, Pageable pageable);
}
