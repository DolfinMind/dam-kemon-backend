package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.damKemon.dam.kemon.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fixed-credential sign-in. The owner POSTs {@code {username, password}}
 * to {@code /api/auth/login}; on match we issue a 30d JWT signed with the
 * same secret the JwtAuthFilter verifies against.
 *
 * <p>No email, no OAuth, no magic links — this is the entire auth surface.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final JwtService jwt;
    private final UserRepository users;
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder();

    public AuthController(JwtService jwt, UserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        String username = trim(body.get("username"));
        String password = body.get("password");
        if (username == null || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password required"));
        }
        try {
            // Duplicate-tolerant: a re-import can leave >1 row with this username, which
            // makes the single-result findByUsername throw and 500 the login. Match the
            // password against whichever row carries the right hash instead.
            User u = null;
            for (User cand : users.findAllByUsername(username)) {
                if (cand.getPasswordHash() != null && hasher.matches(password, cand.getPasswordHash())) {
                    u = cand;
                    break;
                }
            }
            if (u == null) {
                // Opaque error — no user enumeration.
                return ResponseEntity.status(401).body(Map.of("error", "invalid credentials"));
            }
            u.setLastLoginAt(java.time.LocalDateTime.now());
            try { users.save(u); } catch (DataAccessException ignored) {}

            String token = jwt.issue(u.getId(), u.getEmail(), u.getRole());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", token);
            out.put("user", publicProfile(u));
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not sign in"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "not signed in"));
        try {
            User u = users.findById(userId).orElse(null);
            if (u == null) return ResponseEntity.status(401).body(Map.of("error", "user no longer exists"));
            return ResponseEntity.ok(publicProfile(u));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not load profile"));
        }
    }

    @PostMapping("/sign-out")
    public ResponseEntity<Map<String, Object>> signOut() {
        // JWTs are self-contained; client just drops the token.
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static Map<String, Object> publicProfile(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("avatarUrl", u.getAvatarUrl());
        return m;
    }
}
