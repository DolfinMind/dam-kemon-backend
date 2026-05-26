package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.SaathiAccount;
import com.damKemon.dam.kemon.repository.SaathiAccountRepository;
import com.damKemon.dam.kemon.service.SaathiService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Operator review queue for Saathi merchants. Sellers submit NID + trade
 * license; an admin sees the queue here and approves / rejects.
 *
 * <p>Verification flips the {@code Seller} directory row on/off
 * automatically (see {@link SaathiService#setVerificationStatus}).
 */
@RestController
@RequestMapping("/api/admin/saathi")
public class AdminSaathiController {

    private final SaathiAccountRepository accounts;
    private final SaathiService saathi;

    public AdminSaathiController(SaathiAccountRepository accounts, SaathiService saathi) {
        this.accounts = accounts;
        this.saathi = saathi;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestParam(value = "status", defaultValue = "pending") String status,
                                  @RequestParam(value = "limit", defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        List<SaathiAccount> rows = accounts.findByVerificationStatus(status, PageRequest.of(0, capped));
        return ResponseEntity.ok(Map.of(
                "status", status,
                "items", rows,
                "pendingCount", accounts.countByVerificationStatus("pending"),
                "verifiedCount", accounts.countByVerificationStatus("verified")
        ));
    }

    /**
     * Body: {@code {"status":"verified|rejected|suspended","note":"..."}}.
     */
    @PostMapping("/{id}/status")
    public ResponseEntity<?> setStatus(@PathVariable String id,
                                       @RequestBody Map<String, String> body) {
        SaathiAccount acc = accounts.findById(id).orElse(null);
        if (acc == null) return ResponseEntity.notFound().build();
        String status = body == null ? null : body.get("status");
        String note = body == null ? null : body.get("note");
        try {
            return ResponseEntity.ok(saathi.setVerificationStatus(acc, status, note));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
