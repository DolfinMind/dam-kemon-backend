package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.AdminUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUsersController {

    private static final Logger log = LoggerFactory.getLogger(AdminUsersController.class);

    private final AdminUserService users;

    public AdminUsersController(AdminUserService users) {
        this.users = users;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "30") int days) {
        return ResponseEntity.ok(users.list(Math.max(0, page), clamp(size, 1, 100), q, clamp(days, 1, 90)));
    }

    @GetMapping("/conversion")
    public ResponseEntity<Map<String, Object>> conversion(@RequestParam(defaultValue = "30") int days) {
        try {
            return ResponseEntity.ok(users.conversion(clamp(days, 1, 90)));
        } catch (Exception e) {
            // Admin-only: surface the real cause instead of an opaque 500 so it's diagnosable.
            log.error("users/conversion failed", e);
            return ResponseEntity.status(500).body(Map.of("error",
                    e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "")));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        try {
            return ResponseEntity.ok(users.detail(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
