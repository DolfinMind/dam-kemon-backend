package com.damKemon.dam.kemon.repository;

import com.damKemon.dam.kemon.model.ReviewVote;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;
import java.util.List;

public interface ReviewVoteRepository extends MongoRepository<ReviewVote, String> {
    Optional<ReviewVote> findByReviewIdAndVoterUserId(String reviewId, String voterUserId);
    List<ReviewVote> findByVoterUserIdAndReviewIdIn(String voterUserId, List<String> reviewIds);
}
