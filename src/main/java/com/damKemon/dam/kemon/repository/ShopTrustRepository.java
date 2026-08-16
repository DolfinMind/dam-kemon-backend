package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.ShopTrust;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ShopTrustRepository extends MongoRepository<ShopTrust, String> {
    Optional<ShopTrust> findByShopSlug(String shopSlug);
    List<ShopTrust> findByShopSlugIn(Collection<String> shopSlugs);
}
