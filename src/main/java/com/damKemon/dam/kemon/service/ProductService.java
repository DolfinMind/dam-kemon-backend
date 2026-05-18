package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;

    public ProductService(ProductRepository productRepository,
                          ReviewRepository reviewRepository,
                          PriceHistoryRepository priceHistoryRepository) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
    }

    public Page<Product> getAllProducts(Pageable pageable) {
        try { return productRepository.findAll(pageable); }
        catch (DataAccessException e) { return new PageImpl<>(Collections.emptyList(), pageable, 0); }
    }

    /**
     * Look up by Mongo {@code _id} first, then by {@code slug}. Returns
     * empty (not a 5xx) if MongoDB is unreachable, so the caller can show
     * a clean "not found" rather than "scraper unreachable".
     */
    public Optional<Product> findByIdOrSlug(String idOrSlug) {
        if (idOrSlug == null || idOrSlug.isBlank()) return Optional.empty();
        try {
            Optional<Product> byId = productRepository.findById(idOrSlug);
            if (byId.isPresent()) return byId;
        } catch (DataAccessException ignored) {}
        try {
            return productRepository.findBySlug(idOrSlug);
        } catch (DataAccessException e) {
            return Optional.empty();
        }
    }

    public List<PriceHistory> getPriceHistory(String productIdOrSlug) {
        try { return priceHistoryRepository.findByProductIdOrderByRecordedAtDesc(productIdOrSlug); }
        catch (DataAccessException e) { return Collections.emptyList(); }
    }

    public List<Review> getReviews(String productIdOrSlug) {
        try { return reviewRepository.findByProductId(productIdOrSlug); }
        catch (DataAccessException e) { return Collections.emptyList(); }
    }
}
