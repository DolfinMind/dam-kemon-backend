package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A user's saved search. The background alert job compares each saved
 * search against the latest catalog and notifies the user when the
 * cheapest matching product price drops below {@link #lastSeenLowest}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "saved_searches")
public class SavedSearch {

    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private String query;

    /** When the alert fires, emails get sent here (falls back to user email). */
    private String notifyEmail;

    /** Cheapest seller price observed last time we evaluated this search. */
    private Double lastSeenLowest;

    private LocalDateTime lastNotifiedAt;

    private LocalDateTime createdAt;
}
