package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.SaathiAccount;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SaathiAccountRepository extends MongoRepository<SaathiAccount, String> {

    Optional<SaathiAccount> findByUserId(String userId);
    Optional<SaathiAccount> findBySlug(String slug);

    List<SaathiAccount> findByVerificationStatus(String status, Pageable pageable);

    boolean existsBySlug(String slug);
    long countByVerificationStatus(String status);
}
