package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.PendingShop;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PendingShopRepository extends MongoRepository<PendingShop, String> {

    Optional<PendingShop> findByBaseUrl(String baseUrl);

    List<PendingShop> findByStatus(String status);
}
