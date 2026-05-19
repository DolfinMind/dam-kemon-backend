package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.PendingShop;
import com.damKemon.dam.kemon.repository.PendingShopRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Public "Submit your shop" endpoint. Shop owners paste a base URL +
 * sitemap; we capture the submission for admin review. No automated
 * promotion — every accepted shop is operator-approved.
 */
@RestController
@RequestMapping("/api/shops")
public class SubmitShopController {

    private static final Logger log = LoggerFactory.getLogger(SubmitShopController.class);

    private final PendingShopRepository pendingRepo;
    private final ShopRepository shopRepo;

    public SubmitShopController(PendingShopRepository pendingRepo, ShopRepository shopRepo) {
        this.pendingRepo = pendingRepo;
        this.shopRepo = shopRepo;
    }

    @PostMapping("/submit")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String name = trim((String) body.get("name"));
        String baseUrl = trim((String) body.get("baseUrl"));
        String sitemapUrl = trim((String) body.get("sitemapUrl"));
        String platform = trim((String) body.get("platform"));
        String notes = trim((String) body.get("notes"));
        String contactEmail = trim((String) body.get("contactEmail"));

        if (name == null || name.length() < 2 || name.length() > 80) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required (2–80 chars)"));
        }
        if (baseUrl == null || !isHttpUrl(baseUrl)) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseUrl must be an absolute http(s) URL"));
        }
        if (sitemapUrl != null && !sitemapUrl.isBlank() && !isHttpUrl(sitemapUrl)) {
            return ResponseEntity.badRequest().body(Map.of("error", "sitemapUrl must be an absolute http(s) URL"));
        }
        String host;
        try { host = URI.create(baseUrl).getHost(); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "baseUrl is malformed")); }
        if (host == null || host.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "baseUrl has no host"));
        }

        try {
            String normalised = stripTrailingSlash(baseUrl);
            // Already approved?
            if (shopRepo.findAll().stream()
                    .anyMatch(s -> normalised.equalsIgnoreCase(stripTrailingSlash(s.getBaseUrl())))) {
                return ResponseEntity.status(409).body(Map.of("error", "shop already in our catalog"));
            }
            // Already pending?
            if (pendingRepo.findByBaseUrl(normalised).isPresent()) {
                return ResponseEntity.status(409).body(Map.of("error", "shop already submitted, awaiting review"));
            }

            PendingShop p = PendingShop.builder()
                    .name(name)
                    .baseUrl(normalised)
                    .sitemapUrl(sitemapUrl == null || sitemapUrl.isBlank() ? null : sitemapUrl)
                    .platform(platform)
                    .notes(notes)
                    .contactEmail(contactEmail)
                    .status("pending")
                    .submittedAt(LocalDateTime.now())
                    .build();
            pendingRepo.save(p);
            log.info("Submit-shop: queued '{}' ({}) for review", name, normalised);
            return ResponseEntity.accepted().body(Map.of(
                    "submitted", true,
                    "id", p.getId(),
                    "message", "Thanks! We'll review and email you when it's live."
            ));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save submission"));
        }
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static boolean isHttpUrl(String s) {
        try {
            URI u = URI.create(s);
            return u.getScheme() != null && (u.getScheme().equals("http") || u.getScheme().equals("https"))
                    && u.getHost() != null && !u.getHost().isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return null;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
