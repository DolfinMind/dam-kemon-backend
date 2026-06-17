package com.damKemon.dam.kemon.model;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Document(collection = "feedbacks")
public class Feedback {
    @Id
    private String id;
    private String name;
    private String email;
    private String message;
    
    @CreatedDate
    private Instant submittedAt;
}
