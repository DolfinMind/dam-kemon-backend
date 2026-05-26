package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.SaathiProduct;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SaathiProductRepository extends MongoRepository<SaathiProduct, String> {

    List<SaathiProduct> findBySaathiId(String saathiId);
    Optional<SaathiProduct> findBySaathiIdAndProductId(String saathiId, String productId);
    void deleteBySaathiIdAndProductId(String saathiId, String productId);
    long countBySaathiId(String saathiId);
}
