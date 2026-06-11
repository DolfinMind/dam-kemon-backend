package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.CatalogRemergeService;
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
    private final CatalogRemergeService remerge;

    public AdminMaintenanceController(ProductService productService,
                                      CatalogRemergeService remerge) {
        this.productService = productService;
        this.remerge = remerge;
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

    /**
     * Consolidate duplicate product rows (same model, fragmented by name noise)
     * so their sellers stack onto one product — the fastest sellers-per-product
     * lever. {@code dryRun=true} (default) previews; {@code dryRun=false} applies.
     */
    @PostMapping("/remerge")
    public ResponseEntity<Map<String, Object>> remergeCatalog(
            @RequestParam(value = "dryRun", defaultValue = "true") boolean dryRun) {
        return ResponseEntity.ok(remerge.remerge(dryRun));
    }
}
