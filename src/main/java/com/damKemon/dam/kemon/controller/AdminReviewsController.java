package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Review moderation queue. Lives under {@code /api/admin/**}, so it's gated by
 * the admin JWT / X-Admin-Key filter. Spam-flagged community reviews land here
 * for an operator to publish or hide.
 */
@RestController
@RequestMapping("/api/admin/reviews")
public class AdminReviewsController {

    private final ProductService productService;

    public AdminReviewsController(ProductService productService) {
        this.productService = productService;
    }

    /** Reviews awaiting moderation (spam-flagged). */
    @GetMapping("/flagged")
    public ResponseEntity<List<Review>> flagged() {
        return ResponseEntity.ok(productService.flaggedReviews());
    }

    /** Set a review's status: published | flagged | hidden. */
    @PostMapping("/{id}/status")
    public ResponseEntity<Object> setStatus(@PathVariable String id, @RequestBody Map<String, String> body) {
        String status = body == null ? null : body.get("status");
        Review r = status == null ? null : productService.moderateReview(id, status);
        if (r == null) return ResponseEntity.badRequest().body(Map.of("error", "unknown review or invalid status"));
        return ResponseEntity.ok(Map.of("id", r.getId(), "status", r.getStatus()));
    }
}
