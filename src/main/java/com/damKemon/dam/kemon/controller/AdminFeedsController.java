package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.FeedSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin trigger for merchant feed sync. Per-shop only (bounded) — the full
 * all-shops pass runs on the worker via {@code feed-sync.enabled}.
 */
@RestController
@RequestMapping("/api/admin/feeds")
public class AdminFeedsController {

    private final FeedSyncService feedSync;

    public AdminFeedsController(FeedSyncService feedSync) {
        this.feedSync = feedSync;
    }

    @PostMapping("/sync/{slug}")
    public ResponseEntity<Map<String, Object>> syncShop(@PathVariable String slug) {
        return ResponseEntity.ok(feedSync.syncShop(slug));
    }
}
