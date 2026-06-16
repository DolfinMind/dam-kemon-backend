package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.ProtectedOrder;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProtectedOrderRepository extends MongoRepository<ProtectedOrder, String> {
    Optional<ProtectedOrder> findByProtectionCode(String protectionCode);
    boolean existsByProtectionCode(String protectionCode);
    List<ProtectedOrder> findByAnonIdOrderByCreatedAtDesc(String anonId);
    List<ProtectedOrder> findBySellerIdentifierAndStatus(String sellerIdentifier, String status);
}
