package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Seller;
import com.damKemon.dam.kemon.repository.SellerRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/sellers")
public class SellerController {

    private final SellerRepository sellerRepository;

    public SellerController(SellerRepository sellerRepository) {
        this.sellerRepository = sellerRepository;
    }

    @GetMapping
    public List<Seller> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) Boolean verified) {
        try {
            if (verified != null && verified) return sellerRepository.findByVerifiedTrue();
            if (category != null && !category.isBlank()) return sellerRepository.findByCategoriesContaining(category.toUpperCase());
            if (city != null && !city.isBlank()) return sellerRepository.findByCityIgnoreCase(city);
            return sellerRepository.findAll();
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Seller> getOne(@PathVariable String id) {
        try {
            return sellerRepository.findById(id)
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (DataAccessException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
