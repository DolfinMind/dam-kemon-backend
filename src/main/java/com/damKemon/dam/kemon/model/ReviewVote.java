package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/** One validated vote per signed-in user and review. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "review_votes")
@CompoundIndex(name = "review_voter_unique", def = "{'reviewId': 1, 'voterUserId': 1}", unique = true)
public class ReviewVote {
    @Id
    private String id;
    @Indexed
    private String reviewId;
    @Indexed
    private String voterUserId;
    /** +1 or -1. */
    private Integer value;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
