package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.MarketplaceSeller;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketplaceSellerRepository extends MongoRepository<MarketplaceSeller, String> {
    Optional<MarketplaceSeller> findBySellerId(String sellerId);
    List<MarketplaceSeller> findBySellerIdIn(Collection<String> sellerIds);
}
