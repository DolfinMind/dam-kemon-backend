package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.NewsletterSubscriber;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface NewsletterSubscriberRepository extends MongoRepository<NewsletterSubscriber, String> {
    Optional<NewsletterSubscriber> findByEmail(String email);
}
