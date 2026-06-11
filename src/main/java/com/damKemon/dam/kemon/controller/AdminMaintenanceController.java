package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /**
     * Enforce the category focus on the existing catalog: re-classify the
     * out-of-scope rows and keep only those that resolve to an allowed category,
     * deleting the rest. Pass {@code dryRun=true} (the default) to preview the
     * counts first; {@code dryRun=false} actually applies the deletes.
     */
    @PostMapping("/focus-cleanup")
    public ResponseEntity<Map<String, Object>> focusCleanup(
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(productService.focusCleanup(dryRun));
    }
}
