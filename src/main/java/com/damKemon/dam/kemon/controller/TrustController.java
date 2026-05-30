package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.MarketplaceSellerService;
import com.damKemon.dam.kemon.service.TrustService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Serves the per-shop "beyond price" decision signals (trust, delivery,
 * returns, genuineness). The frontend collects the shop slugs from a
 * product's sellers and fetches them all in one batched call.
 */
@RestController
@RequestMapping("/api/trust")
public class TrustController {

    private final TrustService trustService;
    private final MarketplaceSellerService sellerService;

    public TrustController(TrustService trustService, MarketplaceSellerService sellerService) {
        this.trustService = trustService;
        this.sellerService = sellerService;
    }

    /** {@code GET /api/trust/shops?slugs=daraz,startech} → slug → trust view. */
    @GetMapping("/shops")
    public ResponseEntity<Map<String, Map<String, Object>>> shops(
            @RequestParam(value = "slugs", required = false) String slugs) {
        if (slugs == null || slugs.isBlank()) return ResponseEntity.ok(Map.of());
        List<String> list = Arrays.stream(slugs.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .distinct().limit(50).toList();
        return ResponseEntity.ok(trustService.viewForSlugs(list));
    }

    /**
     * {@code GET /api/trust/sellers?ids=700508184158,...} → sellerId → reputation
     * view, for marketplace sub-sellers (e.g. Daraz storefronts). Unknown ids are
     * simply omitted so the UI falls back to the marketplace-level trust.
     */
    @GetMapping("/sellers")
    public ResponseEntity<Map<String, Map<String, Object>>> sellers(
            @RequestParam(value = "ids", required = false) String ids) {
        if (ids == null || ids.isBlank()) return ResponseEntity.ok(Map.of());
        List<String> list = Arrays.stream(ids.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .distinct().limit(50).toList();
        return ResponseEntity.ok(sellerService.viewForSellerIds(list));
    }
}
