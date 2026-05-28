package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public review actions that aren't scoped to a product path. Currently just
 * "helpful" voting; the dedup is best-effort on the client (one tap per
 * browser) — abuse is bounded by the global rate limiter.
 */
@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    private final ProductService productService;

    public ReviewController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping("/{id}/helpful")
    public ResponseEntity<Object> helpful(@PathVariable String id) {
        Review r = productService.markHelpful(id);
        if (r == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of("id", r.getId(), "helpfulCount", r.getHelpfulCount()));
    }
}
