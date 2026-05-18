package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @GetMapping
    public ResponseEntity<Page<Product>> getAllProducts(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(productService.getAllProducts(pageable));
    }

    /** Accepts either a Mongo {@code _id} or a {@code slug}. */
    @GetMapping("/{idOrSlug}")
    public ResponseEntity<Product> getProductById(@PathVariable String idOrSlug) {
        return productService.findByIdOrSlug(idOrSlug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{idOrSlug}/history")
    public ResponseEntity<List<PriceHistory>> getPriceHistory(@PathVariable String idOrSlug) {
        return ResponseEntity.ok(productService.getPriceHistory(idOrSlug));
    }

    @GetMapping("/{idOrSlug}/reviews")
    public ResponseEntity<List<Review>> getReviews(@PathVariable String idOrSlug) {
        return ResponseEntity.ok(productService.getReviews(idOrSlug));
    }
}
