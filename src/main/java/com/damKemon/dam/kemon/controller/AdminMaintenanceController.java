package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * One-off catalog maintenance. Gated by the admin filter (path under
 * {@code /api/admin/**}).
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class AdminMaintenanceController {

    private final ProductService productService;

    public AdminMaintenanceController(ProductService productService) {
        this.productService = productService;
    }

    /** Re-run the classifier over the whole catalog to fix stale categories. */
    @PostMapping("/reclassify")
    public ResponseEntity<Map<String, Object>> reclassify() {
        return ResponseEntity.ok(productService.reclassifyAll());
    }
}
