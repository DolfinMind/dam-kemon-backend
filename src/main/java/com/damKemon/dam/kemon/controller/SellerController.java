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
            if (verified != null && verified) return rank(sellerRepository.findByVerifiedTrue());
            if (category != null && !category.isBlank()) return rank(sellerRepository.findByCategoriesContaining(category.toUpperCase()));
            if (city != null && !city.isBlank()) return rank(sellerRepository.findByCityIgnoreCase(city));
            return rank(sellerRepository.findAll());
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    /** Most-clicked sellers first — the directory's default ordering reflects real
     *  outbound engagement (Seller.outboundClicks, recomputed from affiliate_clicks). */
    private static List<Seller> rank(List<Seller> sellers) {
        List<Seller> out = new java.util.ArrayList<>(sellers);
        out.sort(java.util.Comparator.comparingInt(
                (Seller s) -> s.getOutboundClicks() == null ? 0 : s.getOutboundClicks()).reversed());
        return out;
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
