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

    /** Deterministic same-product lookup for cross-seller grouping (robust across
     *  indexer runs / concurrency, unlike the per-run in-memory fuzzy index). */
    Optional<Product> findFirstByMatchKey(String matchKey);

    /** True Mongo text search over the indexed {@code name + description} fields. */
    @Query("{ $text: { $search: ?0 } }")
    List<Product> textSearch(String text, Pageable pageable);

    /** Existence-by-URL lookup the indexer uses to upsert per-shop prices. */
    @Query("{ 'prices.productUrl': ?0 }")
    Optional<Product> findByPriceUrl(String url);

    long countByCategory(String category);

    /** How many catalog products carry an offer from this shop. Used to protect
     *  a proven shop from auto-disable: a week of 0-yield runs against a shop that
     *  still has live offers means our EXTRACTOR regressed, not that the shop died. */
    @Query(value = "{ 'prices.siteSlug': ?0 }", count = true)
    long countBySiteSlug(String siteSlug);

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

    /**
     * Known products in the given categories that still carry fewer than
     * {@code maxSellers} offers — the catalog rows the {@link com.damKemon.dam.kemon.indexer.SellerDepthHarvester}
     * actively hunts across more shops to raise sellers-per-product. id+name
     * projection only (heap-safe); page/sort supplied by the caller (e.g. most
     * reviewed first, so we deepen the products shoppers actually compare).
     */
    @Query(value = "{ 'category': { $in: ?0 }, $expr: { $lt: [ { $size: { $ifNull: ['$prices', []] } }, ?1 ] } }",
           fields = "{ 'name' : 1 }")
    List<NameView> findShallowByCategoryIn(List<String> categories, int maxSellers, Pageable pageable);

    interface NameView {
        String getId();
        String getName();
    }

    /** Lightweight id+slug projection for /sitemap.xml — capped via Pageable and
     *  carrying no prices array, so the sitemap never loads the whole catalog
     *  into heap (which OOM-crashed the app once the catalog grew large). */
    @Query(value = "{}", fields = "{ 'slug' : 1 }")
    List<SlugView> findAllSlugViews(Pageable pageable);

    interface SlugView {
        String getId();
        String getSlug();
    }
}
