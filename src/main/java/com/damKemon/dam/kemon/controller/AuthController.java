package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.damKemon.dam.kemon.service.AuthService;
import com.damKemon.dam.kemon.service.JwtService;
import com.damKemon.dam.kemon.service.MagicLinkMailer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Email-magic-link sign-in. Three endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/auth/request-link} {"email": "x"} → emails a link.</li>
 *   <li>{@code POST /api/auth/verify} {"email", "token"} → consumes the
 *       token, issues a 30-day JWT, returns the user profile.</li>
 *   <li>{@code GET  /api/auth/me} → current user inferred from the
 *       {@code Authorization: Bearer} header.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final JwtService jwt;
    private final MagicLinkMailer mailer;
    private final UserRepository users;
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder();

    public AuthController(AuthService auth, JwtService jwt, MagicLinkMailer mailer, UserRepository users) {
        this.auth = auth;
        this.jwt = jwt;
        this.mailer = mailer;
        this.users = users;
    }

    /**
     * Fixed-credential sign-in for the owner. Magic-link is still available
     * for regular users; this is the dedicated operator path.
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        String username = trim(body.get("username"));
        String password = body.get("password");
        if (username == null || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password required"));
        }
        try {
            User u = users.findByUsername(username).orElse(null);
            if (u == null || u.getPasswordHash() == null || !hasher.matches(password, u.getPasswordHash())) {
                // Constant-ish response time + opaque message — no user enumeration leak.
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

    private static String trim(String s) { return s == null ? null : s.trim(); }

    @PostMapping("/request-link")
    public ResponseEntity<Map<String, Object>> requestLink(@RequestBody Map<String, String> body) {
        Map<String, Object> result = auth.requestLink(body == null ? null : body.get("email"));
        if (Boolean.TRUE.equals(result.get("ok"))) {
            String token = (String) result.remove("_internalToken");
            String email = (String) result.get("email");
            if (token != null) mailer.send(email, token);
        } else {
            result.remove("_internalToken");
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        Optional<User> u = auth.verifyLink(body.get("email"), body.get("token"));
        if (u.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid or expired link"));
        }
        User user = u.get();
        String token = jwt.issue(user.getId(), user.getEmail(), user.getRole());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("user", publicProfile(user));
        return ResponseEntity.ok(out);
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
        // JWTs are self-contained; we don't keep a revocation list. The client
        // drops its token. Returning OK so the frontend has a clear hook.
        return ResponseEntity.ok(Map.of("ok", true));
    }

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
