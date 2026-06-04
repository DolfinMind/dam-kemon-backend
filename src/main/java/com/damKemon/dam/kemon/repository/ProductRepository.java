package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends MongoRepository<Product, String> {
    List<Product> findByNameContainingIgnoreCase(String name);
    List<Product> findByCategory(String category);
    /** Paginated, case-insensitive category browse — backs the Browse page. */
    Page<Product> findByCategoryIgnoreCase(String category, Pageable pageable);
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

    /** Active sponsored placements in a specific category. */
    @Query("{ 'sponsored': true, 'sponsoredUntil': { $gt: ?0 }, 'category': ?1 }")
    List<Product> findActiveSponsoredByCategory(LocalDateTime now, String category, Pageable pageable);

    /** Active sponsored placements regardless of category. */
    @Query("{ 'sponsored': true, 'sponsoredUntil': { $gt: ?0 } }")
    List<Product> findActiveSponsored(LocalDateTime now, Pageable pageable);

    @Query("{ 'sponsored': true }")
    List<Product> findAllSponsored(Pageable pageable);

    /** Lightweight id+name projection so the indexer can warm its dedup index
     *  without pulling every full Product (with its prices array) into heap. */
    @Query(value = "{}", fields = "{ 'name' : 1 }")
    List<NameView> findAllNameViews();

    interface NameView {
        String getId();
        String getName();
    }
}
