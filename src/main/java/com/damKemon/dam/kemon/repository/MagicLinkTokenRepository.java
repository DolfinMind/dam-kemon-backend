package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.MagicLinkToken;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface MagicLinkTokenRepository extends MongoRepository<MagicLinkToken, String> {
    List<MagicLinkToken> findByEmail(String email);
}
