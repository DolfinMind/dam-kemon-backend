package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.Seller;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SellerRepository extends MongoRepository<Seller, String> {
    List<Seller> findByCategoriesContaining(String category);
    List<Seller> findByCityIgnoreCase(String city);
    List<Seller> findByVerifiedTrue();
}
