package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.service.ReviewReputationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Signed-in review voting and the current user's contribution history.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ReviewReputationService reputation;

    public ReviewController(ReviewReputationService reputation) {
        this.reputation = reputation;
    }

    @PostMapping("/{id}/vote")
    public ResponseEntity<Object> vote(@PathVariable String id,
                                       @RequestBody(required = false) Map<String, Object> body,
                                       HttpServletRequest req) {
        Integer value = null;
        Object raw = body == null ? null : body.get("value");
        if (raw instanceof Number n) value = n.intValue();
        ReviewReputationService.Outcome out = reputation.vote(
                id, (String) req.getAttribute("authUserId"), value);
        return ResponseEntity.status(out.status()).body(out.body());
    }

    /** Backwards-compatible helpful action, now server-validated as an upvote. */
    @PostMapping("/{id}/helpful")
    public ResponseEntity<Object> helpful(@PathVariable String id, HttpServletRequest req) {
        ReviewReputationService.Outcome out = reputation.vote(
                id, (String) req.getAttribute("authUserId"), 1);
        return ResponseEntity.status(out.status()).body(out.body());
    }

    @GetMapping("/me")
    public ResponseEntity<?> mine(HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "sign in to view contributions"));
        List<Review> rows = reputation.contributions(userId);
        return ResponseEntity.ok(rows);
    }

    /** Current user's vote state for the visible review cards (one batched read). */
    @GetMapping("/votes")
    public ResponseEntity<?> myVotes(@RequestParam("ids") String ids, HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "sign in to view votes"));
        List<String> reviewIds = java.util.Arrays.stream(ids == null ? new String[0] : ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).limit(100).toList();
        return ResponseEntity.ok(reputation.votesFor(userId, reviewIds));
    }
}
