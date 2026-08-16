package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.ShopDiagnostic;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ShopDiagnosticRepository extends MongoRepository<ShopDiagnostic, String> {

    /** Most recent probe row for one shop — what the dashboard shows. */
    Optional<ShopDiagnostic> findTopByShopSlugOrderByTsDesc(String shopSlug);

    /** Full history (rare, for the "trend" tab). */
    List<ShopDiagnostic> findByShopSlugOrderByTsDesc(String shopSlug, Pageable pageable);
}
