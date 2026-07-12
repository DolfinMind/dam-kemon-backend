package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.model.ReviewVote;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import com.damKemon.dam.kemon.repository.ReviewVoteRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates review votes and keeps review score and author reputation in sync. */
@Service
public class ReviewReputationService {
    private final ReviewRepository reviews;
    private final ReviewVoteRepository votes;
    private final UserRepository users;

    public ReviewReputationService(ReviewRepository reviews, ReviewVoteRepository votes, UserRepository users) {
        this.reviews = reviews;
        this.votes = votes;
        this.users = users;
    }

    public record Outcome(int status, Object body) {}

    /** Same vote toggles off; opposite vote switches. One JVM lock avoids local lost updates. */
    public synchronized Outcome vote(String reviewId, String voterUserId, Integer requested) {
        if (voterUserId == null) return out(401, "sign in to vote");
        if (requested == null || (requested != 1 && requested != -1)) return out(400, "vote must be 1 or -1");
        try {
            Review review = reviews.findById(reviewId).orElse(null);
            if (review == null || !"published".equals(review.getStatus())) return out(404, "review not found");
            if (review.getUserId() == null) {
                return out(400, "only member reviews can receive reputation votes");
            }
            if (voterUserId.equals(review.getUserId())) return out(403, "you cannot vote on your own review");

            ReviewVote existing = votes.findByReviewIdAndVoterUserId(reviewId, voterUserId).orElse(null);
            int oldValue = existing == null || existing.getValue() == null ? 0 : existing.getValue();
            int nextValue = oldValue == requested ? 0 : requested;
            LocalDateTime now = LocalDateTime.now();
            if (nextValue == 0) {
                if (existing != null) votes.delete(existing);
            } else {
                ReviewVote row = existing == null
                        ? ReviewVote.builder().reviewId(reviewId).voterUserId(voterUserId).createdAt(now).build()
                        : existing;
                row.setValue(nextValue);
                row.setUpdatedAt(now);
                votes.save(row);
            }

            int ups = nonNegative(review.getUpvoteCount()) + indicator(nextValue, 1) - indicator(oldValue, 1);
            int downs = nonNegative(review.getDownvoteCount()) + indicator(nextValue, -1) - indicator(oldValue, -1);
            review.setUpvoteCount(Math.max(0, ups));
            review.setDownvoteCount(Math.max(0, downs));
            review.setHelpfulCount(review.getUpvoteCount()); // backwards-compatible API field
            review.setScore(review.getUpvoteCount() - review.getDownvoteCount());

            int reputation = Math.max(1, review.getAuthorReputation() == null ? 1 : review.getAuthorReputation());
            User author = users.findById(review.getUserId()).orElse(null);
            if (author != null) {
                // Keep the raw total so a downvote at the public floor of 1 can
                // be reversed exactly; API/UI clamp the displayed value to 1.
                int current = author.getReputation() == null ? 1 : author.getReputation();
                int rawReputation = current + reputationValue(nextValue) - reputationValue(oldValue);
                author.setReputation(rawReputation);
                reputation = Math.max(1, rawReputation);
                author.setUpdatedAt(now);
                users.save(author);
            }
            review.setAuthorReputation(reputation);
            review.setTrusted(Boolean.TRUE.equals(review.getVerified())
                    || (review.getScore() >= 5 && reputation >= 50));
            reviews.save(review);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", review.getId());
            body.put("vote", nextValue);
            body.put("upvoteCount", review.getUpvoteCount());
            body.put("downvoteCount", review.getDownvoteCount());
            body.put("score", review.getScore());
            body.put("trusted", review.getTrusted());
            body.put("authorReputation", reputation);
            return new Outcome(200, body);
        } catch (DataAccessException e) {
            return out(500, "could not save vote");
        }
    }

    public List<Review> contributions(String userId) {
        if (userId == null) return List.of();
        try { return reviews.findByUserIdOrderByReviewDateDesc(userId); }
        catch (DataAccessException e) { return List.of(); }
    }

    public Map<String, Integer> votesFor(String userId, List<String> reviewIds) {
        if (userId == null || reviewIds == null || reviewIds.isEmpty()) return Map.of();
        Set<String> ids = reviewIds.stream().filter(java.util.Objects::nonNull).limit(100).collect(Collectors.toSet());
        try {
            return votes.findByVoterUserIdAndReviewIdIn(userId, List.copyOf(ids)).stream()
                    .collect(Collectors.toMap(ReviewVote::getReviewId, ReviewVote::getValue, (a, b) -> b));
        } catch (DataAccessException e) {
            return Map.of();
        }
    }

    private static Outcome out(int status, String error) { return new Outcome(status, Map.of("error", error)); }
    private static int nonNegative(Integer n) { return n == null ? 0 : Math.max(0, n); }
    private static int indicator(int value, int target) { return value == target ? 1 : 0; }
    private static int reputationValue(int vote) { return vote == 1 ? 10 : vote == -1 ? -2 : 0; }
}
