package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.NewsletterSubscriber;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.damKemon.dam.kemon.service.JwtService;
import com.damKemon.dam.kemon.service.ResendService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Auth surface. Two populations share it:
 *
 * <ul>
 *   <li><b>Owner/operators</b> — fixed username + password (OwnerBootstrap),
 *       unchanged.</li>
 *   <li><b>Regular users</b> — email + password signup with a Resend
 *       verification link, forgot/reset flow, and an optional profile
 *       (phone / district / gender / birth year / interests) that powers
 *       personalisation later. Login accepts email or username.</li>
 * </ul>
 *
 * All tokens are one-shot UUIDs stored on the user row; responses on the
 * forgot path are deliberately opaque (no account enumeration).
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MIN_PASSWORD = 8;

    private final JwtService jwt;
    private final UserRepository users;
    private final NewsletterSubscriberRepository newsletter;
    private final ResendService resend;
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder();
    // non-final so tests can swap in a stub instead of calling Google for real
    private org.springframework.web.client.RestTemplate http =
            new org.springframework.web.client.RestTemplate();

    /** Base URL used in verification / reset links inside emails. */
    @Value("${app.site-url:https://damkemon.com}")
    private String siteUrl;

    /** Google OAuth web client id; blank = Google sign-in disabled. */
    @Value("${auth.google-client-id:}")
    private String googleClientId;

    public AuthController(JwtService jwt, UserRepository users,
                          NewsletterSubscriberRepository newsletter,
                          ResendService resend) {
        this.jwt = jwt;
        this.users = users;
        this.newsletter = newsletter;
        this.resend = resend;
    }

    // ─────────────────────────────── Sign up ───────────────────────────────

    @PostMapping("/signup")
    public ResponseEntity<Map<String, Object>> signup(@RequestBody Map<String, Object> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        String name = trim(str(body.get("name")));
        String email = lower(trim(str(body.get("email"))));
        String password = str(body.get("password"));
        String phone = trim(str(body.get("phone")));
        boolean newsletterOptIn = !Boolean.FALSE.equals(body.get("newsletterOptIn"));

        if (name == null || name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (email == null || !EMAIL.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "a valid email is required"));
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            return ResponseEntity.badRequest().body(Map.of("error", "password must be at least " + MIN_PASSWORD + " characters"));
        }
        try {
            if (!users.findAllByEmail(email).isEmpty()) {
                return ResponseEntity.status(409).body(Map.of("error", "an account with this email already exists — sign in instead"));
            }
            LocalDateTime now = LocalDateTime.now();
            User u = User.builder()
                    .email(email)
                    .displayName(name)
                    .passwordHash(hasher.encode(password))
                    .role("user")
                    .emailVerified(false)
                    .verifyToken(UUID.randomUUID().toString())
                    .verifyTokenExpiry(now.plusHours(48))
                    .phone(phone == null || phone.isEmpty() ? null : phone)
                    .newsletterOptIn(newsletterOptIn)
                    .signupSource("signup")
                    .lastLoginAt(now)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            User saved;
            try {
                saved = users.save(u);
            } catch (org.springframework.dao.DuplicateKeyException race) {
                return ResponseEntity.status(409).body(Map.of("error", "an account with this email already exists — sign in instead"));
            }

            sendVerificationMailAsync(saved);
            if (newsletterOptIn) subscribeNewsletter(email);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", jwt.issue(saved.getId(), saved.getEmail(), saved.getRole()));
            out.put("user", publicProfile(saved));
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            log.warn("signup failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "could not create the account — try again"));
        }
    }

    /** Marks the email verified. Public: the token IS the proof. */
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verify(@RequestBody Map<String, String> body) {
        String token = body == null ? null : trim(body.get("token"));
        if (token == null || token.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token required"));
        }
        try {
            User u = users.findByVerifyToken(token).orElse(null);
            if (u == null || u.getVerifyTokenExpiry() == null
                    || u.getVerifyTokenExpiry().isBefore(LocalDateTime.now())) {
                return ResponseEntity.status(410).body(Map.of("error", "this link has expired — request a new one from your account page"));
            }
            u.setEmailVerified(true);
            u.setVerifyToken(null);
            u.setVerifyTokenExpiry(null);
            u.setUpdatedAt(LocalDateTime.now());
            users.save(u);
            return ResponseEntity.ok(Map.of("ok", true, "email", u.getEmail()));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not verify — try again"));
        }
    }

    /** Re-issues the verification mail for the signed-in user. */
    @PostMapping("/resend-verification")
    public ResponseEntity<Map<String, Object>> resendVerification(HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "not signed in"));
        try {
            User u = users.findById(userId).orElse(null);
            if (u == null) return ResponseEntity.status(401).body(Map.of("error", "user no longer exists"));
            if (Boolean.TRUE.equals(u.getEmailVerified())) {
                return ResponseEntity.ok(Map.of("ok", true, "alreadyVerified", true));
            }
            u.setVerifyToken(UUID.randomUUID().toString());
            u.setVerifyTokenExpiry(LocalDateTime.now().plusHours(48));
            users.save(u);
            sendVerificationMailAsync(u);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not resend — try again"));
        }
    }

    // ─────────────────────────── Forgot / reset ────────────────────────────

    /** Always answers ok — whether the email exists is never revealed. */
    @PostMapping("/forgot")
    public ResponseEntity<Map<String, Object>> forgot(@RequestBody Map<String, String> body) {
        String email = body == null ? null : lower(trim(body.get("email")));
        if (email == null || !EMAIL.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "a valid email is required"));
        }
        try {
            List<User> matches = users.findAllByEmail(email);
            if (!matches.isEmpty()) {
                User u = matches.get(0);
                u.setResetToken(UUID.randomUUID().toString());
                u.setResetTokenExpiry(LocalDateTime.now().plusHours(1));
                users.save(u);
                String link = siteUrl + "/reset-password?token=" + u.getResetToken();
                sendMailAsync(u.getEmail(), "Reset your Damkemon password",
                        emailShell("Reset your password",
                                "Someone (hopefully you) asked to reset the password for this account. "
                                        + "The link works once and expires in 1 hour.",
                                link, "Set a new password",
                                "If you didn't ask for this, ignore this email — your password is unchanged."));
            }
        } catch (DataAccessException ignored) { /* opaque — same response either way */ }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/reset")
    public ResponseEntity<Map<String, Object>> reset(@RequestBody Map<String, String> body) {
        String token = body == null ? null : trim(body.get("token"));
        String password = body == null ? null : body.get("password");
        if (token == null || token.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "token required"));
        }
        if (password == null || password.length() < MIN_PASSWORD) {
            return ResponseEntity.badRequest().body(Map.of("error", "password must be at least " + MIN_PASSWORD + " characters"));
        }
        try {
            User u = users.findByResetToken(token).orElse(null);
            if (u == null || u.getResetTokenExpiry() == null
                    || u.getResetTokenExpiry().isBefore(LocalDateTime.now())) {
                return ResponseEntity.status(410).body(Map.of("error", "this link has expired — request a new one"));
            }
            u.setPasswordHash(hasher.encode(password));
            u.setResetToken(null);
            u.setResetTokenExpiry(null);
            // Proving control of the inbox verifies the email as a side effect.
            u.setEmailVerified(true);
            u.setUpdatedAt(LocalDateTime.now());
            users.save(u);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not reset — try again"));
        }
    }

    // ───────────────────────────── Google sign-in ──────────────────────────

    /**
     * Google Identity Services flow: the browser hands us a Google-signed ID
     * token; we let Google's tokeninfo endpoint validate the signature/expiry,
     * then check the token was minted for OUR client id. A verified Google
     * email counts as a verified email (alerts activate immediately).
     * Existing accounts with the same email get LINKED, not duplicated.
     */
    @PostMapping("/google")
    public ResponseEntity<Map<String, Object>> google(@RequestBody Map<String, String> body) {
        if (googleClientId == null || googleClientId.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("error", "Google sign-in isn't set up yet"));
        }
        String credential = body == null ? null : trim(body.get("credential"));
        if (credential == null || credential.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "credential required"));
        }
        Map<?, ?> info;
        try {
            info = http.getForObject(
                    "https://oauth2.googleapis.com/tokeninfo?id_token={t}", Map.class, credential);
        } catch (Exception e) {
            // tokeninfo 400s invalid/expired tokens — that lands here.
            return ResponseEntity.status(401).body(Map.of("error", "Google didn't accept that sign-in — try again"));
        }
        if (info == null
                || !googleClientId.equals(String.valueOf(info.get("aud")))
                || !java.util.Set.of("accounts.google.com", "https://accounts.google.com")
                        .contains(String.valueOf(info.get("iss")))) {
            return ResponseEntity.status(401).body(Map.of("error", "Google didn't accept that sign-in — try again"));
        }
        String email = lower(str(info.get("email")));
        if (email == null || !"true".equals(String.valueOf(info.get("email_verified")))) {
            return ResponseEntity.status(401).body(Map.of("error", "this Google account has no verified email"));
        }
        String sub = str(info.get("sub"));
        String name = str(info.get("name"));
        String picture = str(info.get("picture"));
        try {
            LocalDateTime now = LocalDateTime.now();
            List<User> matches = users.findAllByEmail(email);
            User u;
            if (matches.isEmpty()) {
                u = User.builder()
                        .email(email)
                        .displayName(name != null && !name.isBlank() ? name : email)
                        .role("user")
                        .emailVerified(true)
                        .googleSub(sub)
                        .avatarUrl(picture)
                        // no consent checkbox in this flow — digest stays opt-in via profile
                        .newsletterOptIn(false)
                        .signupSource("google")
                        .createdAt(now)
                        .build();
            } else {
                u = matches.get(0);
                if (u.getGoogleSub() == null) u.setGoogleSub(sub);
                u.setEmailVerified(true);       // Google proved the inbox
                if (u.getAvatarUrl() == null) u.setAvatarUrl(picture);
                if (u.getDisplayName() == null || u.getDisplayName().isBlank()) u.setDisplayName(name);
            }
            u.setLastLoginAt(now);
            u.setUpdatedAt(now);
            try {
                u = users.save(u);
            } catch (org.springframework.dao.DuplicateKeyException race) {
                // two first-time Google sign-ins raced — the other one won; use its row
                List<User> again = users.findAllByEmail(email);
                if (again.isEmpty()) throw race;
                u = again.get(0);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("token", jwt.issue(u.getId(), u.getEmail(), u.getRole()));
            out.put("user", publicProfile(u));
            return ResponseEntity.ok(out);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not sign in"));
        }
    }

    // ───────────────────────────── Sign in / me ────────────────────────────

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        // One field serves both populations: owners type a username, users an email.
        String identifier = trim(body.get("username") != null ? body.get("username") : body.get("email"));
        String password = body.get("password");
        if (identifier == null || password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "email/username and password required"));
        }
        try {
            // Duplicate-tolerant: a re-import can leave >1 row, which makes the
            // single-result finders throw and 500 the login. Match the password
            // against whichever row carries the right hash instead.
            User u = null;
            List<User> candidates = new java.util.ArrayList<>(users.findAllByUsername(identifier));
            candidates.addAll(users.findAllByEmail(lower(identifier)));
            for (User cand : candidates) {
                if (cand.getPasswordHash() != null && hasher.matches(password, cand.getPasswordHash())) {
                    u = cand;
                    break;
                }
            }
            if (u == null) {
                // Opaque error — no user enumeration.
                return ResponseEntity.status(401).body(Map.of("error", "invalid credentials"));
            }
            u.setLastLoginAt(LocalDateTime.now());
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

    /** Partial profile update — the "give us more data when you feel like it" surface. */
    @PatchMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody Map<String, Object> body,
                                                             HttpServletRequest req) {
        String userId = (String) req.getAttribute("authUserId");
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "not signed in"));
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "missing body"));
        try {
            User u = users.findById(userId).orElse(null);
            if (u == null) return ResponseEntity.status(401).body(Map.of("error", "user no longer exists"));

            if (body.containsKey("displayName")) {
                String v = trim(str(body.get("displayName")));
                if (v != null && !v.isEmpty()) u.setDisplayName(v);
            }
            if (body.containsKey("phone")) u.setPhone(emptyToNull(trim(str(body.get("phone")))));
            if (body.containsKey("district")) u.setDistrict(emptyToNull(trim(str(body.get("district")))));
            if (body.containsKey("gender")) u.setGender(emptyToNull(trim(str(body.get("gender")))));
            if (body.containsKey("birthYear")) {
                Integer y = asYear(body.get("birthYear"));
                u.setBirthYear(y);
            }
            if (body.containsKey("interests") && body.get("interests") instanceof List<?> raw) {
                u.setInterests(raw.stream().map(String::valueOf).map(String::trim)
                        .filter(s -> !s.isEmpty()).limit(20).toList());
            }
            if (body.containsKey("newsletterOptIn")) {
                boolean opt = Boolean.TRUE.equals(body.get("newsletterOptIn"));
                u.setNewsletterOptIn(opt);
                if (opt) subscribeNewsletter(u.getEmail());
                else unsubscribeNewsletter(u.getEmail());
            }
            u.setUpdatedAt(LocalDateTime.now());
            users.save(u);
            return ResponseEntity.ok(publicProfile(u));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not update profile"));
        }
    }

    @PostMapping("/sign-out")
    public ResponseEntity<Map<String, Object>> signOut() {
        // JWTs are self-contained; client just drops the token.
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ─────────────────────────────── Helpers ───────────────────────────────

    private void sendVerificationMailAsync(User u) {
        if (u.getEmail() == null || u.getVerifyToken() == null) return;
        String link = siteUrl + "/verify?token=" + u.getVerifyToken();
        sendMailAsync(u.getEmail(), "Verify your email — Damkemon",
                emailShell("Welcome to Damkemon" + (u.getDisplayName() != null ? ", " + u.getDisplayName() : "") + "!",
                        "Confirm this email address to activate price-drop alerts for your wishlist. "
                                + "The link expires in 48 hours.",
                        link, "Verify my email",
                        "If you didn't create this account, you can safely ignore this email."));
    }

    /** Off the request thread — Resend's HTTP call must never slow signup down. */
    private void sendMailAsync(String to, String subject, String html) {
        CompletableFuture.runAsync(() -> {
            boolean ok = resend.sendEmail(to, subject, html);
            if (!ok) log.warn("auth mail not sent to {} ({}) — Resend unavailable/unconfigured", to, subject);
        });
    }

    /** Minimal inline-styled shell matching the newsletter's look. */
    private static String emailShell(String heading, String intro, String link, String cta, String footer) {
        return "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;max-width:520px;margin:0 auto;"
                + "background:#ffffff;border:1px solid #ecebe6;border-radius:16px;padding:28px;\">"
                + "<div style=\"font-size:20px;font-weight:800;color:#15131a;\">" + heading + "</div>"
                + "<p style=\"color:#4a4a4a;font-size:14px;line-height:1.6;\">" + intro + "</p>"
                + "<p style=\"margin:24px 0;\"><a href=\"" + link + "\" style=\"background:#9FE231;color:#15131a;"
                + "text-decoration:none;font-weight:700;font-size:14px;padding:12px 22px;border-radius:999px;"
                + "display:inline-block;\">" + cta + "</a></p>"
                + "<p style=\"color:#8a8a8a;font-size:12px;line-height:1.6;\">" + footer
                + "<br>Or paste this link into your browser:<br>"
                + "<a href=\"" + link + "\" style=\"color:#15131a;word-break:break-all;\">" + link + "</a></p>"
                + "</div>";
    }

    private void subscribeNewsletter(String email) {
        if (email == null) return;
        try {
            if (newsletter.findByEmail(email).isEmpty()) {
                NewsletterSubscriber sub = new NewsletterSubscriber();
                sub.setEmail(email);
                sub.setSubscribedAt(Instant.now());
                newsletter.save(sub);
            }
        } catch (Exception ignored) { /* newsletter is best-effort */ }
    }

    private void unsubscribeNewsletter(String email) {
        if (email == null) return;
        try {
            newsletter.findByEmail(email).ifPresent(newsletter::delete);
        } catch (Exception ignored) { /* best-effort */ }
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }
    private static String lower(String s) { return s == null ? null : s.toLowerCase(); }
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    private static Integer asYear(Object v) {
        if (v == null) return null;
        try {
            int y = (v instanceof Number n) ? n.intValue() : Integer.parseInt(v.toString().trim());
            return (y >= 1920 && y <= LocalDateTime.now().getYear()) ? y : null;
        } catch (NumberFormatException e) { return null; }
    }

    private static Map<String, Object> publicProfile(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("email", u.getEmail());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("avatarUrl", u.getAvatarUrl());
        // null (legacy/owner rows) counts as verified — only a fresh, unclicked
        // signup is explicitly false.
        m.put("emailVerified", !Boolean.FALSE.equals(u.getEmailVerified()));
        m.put("phone", u.getPhone());
        m.put("district", u.getDistrict());
        m.put("gender", u.getGender());
        m.put("birthYear", u.getBirthYear());
        m.put("interests", u.getInterests());
        m.put("newsletterOptIn", u.getNewsletterOptIn());
        m.put("createdAt", u.getCreatedAt());
        return m;
    }
}
