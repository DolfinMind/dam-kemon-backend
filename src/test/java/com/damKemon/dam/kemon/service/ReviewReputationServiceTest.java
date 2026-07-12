package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import com.damKemon.dam.kemon.repository.ReviewVoteRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ReviewReputationServiceTest {
    @Test
    void fifthNetUpvoteCrossesBothTrustedThresholds() {
        ReviewRepository reviews = mock(ReviewRepository.class);
        ReviewVoteRepository votes = mock(ReviewVoteRepository.class);
        UserRepository users = mock(UserRepository.class);
        Review review = Review.builder().id("r1").userId("author").status("published")
                .upvoteCount(4).downvoteCount(0).score(4).authorReputation(41).build();
        User author = User.builder().id("author").reputation(41).build();
        when(reviews.findById("r1")).thenReturn(Optional.of(review));
        when(votes.findByReviewIdAndVoterUserId("r1", "voter")).thenReturn(Optional.empty());
        when(users.findById("author")).thenReturn(Optional.of(author));
        when(reviews.save(any())).thenAnswer(i -> i.getArgument(0));
        when(users.save(any())).thenAnswer(i -> i.getArgument(0));

        var out = new ReviewReputationService(reviews, votes, users).vote("r1", "voter", 1);

        assertEquals(200, out.status());
        assertEquals(5, review.getScore());
        assertEquals(51, author.getReputation());
        assertTrue(review.getTrusted());
        verify(votes).save(argThat(v -> v.getValue() == 1 && "voter".equals(v.getVoterUserId())));
    }

    @Test
    void authorCannotVoteForOwnReview() {
        ReviewRepository reviews = mock(ReviewRepository.class);
        Review review = Review.builder().id("r1").userId("author").status("published").build();
        when(reviews.findById("r1")).thenReturn(Optional.of(review));
        ReviewVoteRepository votes = mock(ReviewVoteRepository.class);

        var out = new ReviewReputationService(reviews, votes, mock(UserRepository.class))
                .vote("r1", "author", 1);

        assertEquals(403, out.status());
        verifyNoInteractions(votes);
    }

    @Test
    void downvoteAtDisplayFloorCanBeReversedWithoutInflatingPoints() {
        ReviewRepository reviews = mock(ReviewRepository.class);
        ReviewVoteRepository votes = mock(ReviewVoteRepository.class);
        UserRepository users = mock(UserRepository.class);
        Review review = Review.builder().id("r1").userId("author").status("published").build();
        User author = User.builder().id("author").reputation(1).build();
        AtomicReference<com.damKemon.dam.kemon.model.ReviewVote> stored = new AtomicReference<>();
        when(reviews.findById("r1")).thenReturn(Optional.of(review));
        when(users.findById("author")).thenReturn(Optional.of(author));
        when(votes.findByReviewIdAndVoterUserId("r1", "voter"))
                .thenAnswer(i -> Optional.ofNullable(stored.get()));
        when(votes.save(any())).thenAnswer(i -> { stored.set(i.getArgument(0)); return i.getArgument(0); });
        doAnswer(i -> { stored.set(null); return null; }).when(votes).delete(any());
        ReviewReputationService service = new ReviewReputationService(reviews, votes, users);

        assertEquals(200, service.vote("r1", "voter", -1).status());
        assertEquals(-1, author.getReputation(), "raw points retain the reversible -2 delta");
        assertEquals(200, service.vote("r1", "voter", -1).status());
        assertEquals(1, author.getReputation(), "toggling off returns to the exact starting value");
        assertEquals(0, review.getScore());
    }
}
