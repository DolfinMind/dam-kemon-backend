package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Seller;
import com.damKemon.dam.kemon.repository.SellerRepository;
import org.springframework.web.bind.annotation.*;

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
        if (verified != null && verified) return sellerRepository.findByVerifiedTrue();
        if (category != null && !category.isBlank()) return sellerRepository.findByCategoriesContaining(category.toUpperCase());
        if (city != null && !city.isBlank()) return sellerRepository.findByCityIgnoreCase(city);
        return sellerRepository.findAll();
    }

    @GetMapping("/{id}")
    public Seller getOne(@PathVariable String id) {
        return sellerRepository.findById(id).orElse(null);
    }
}
