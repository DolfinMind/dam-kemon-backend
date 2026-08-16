package com.damKemon.dam.kemon.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "newsletter_subscribers")
public class NewsletterSubscriber {
    @Id
    private String id;
    private String email;
    
    @CreatedDate
    private Instant subscribedAt;
}
