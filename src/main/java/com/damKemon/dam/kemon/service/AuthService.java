package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.MagicLinkToken;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.MagicLinkTokenRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Email-magic-link sign-in. Two-step:
 * <ol>
 *   <li>{@link #requestLink(String)} — generate a one-shot token, store its
 *       BCrypt hash + TTL, return the raw token + email envelope to the
 *       caller (controller emails it).</li>
 *   <li>{@link #verifyLink(String, String)} — find a non-consumed, non-expired
 *       token whose hash matches; mark consumed; upsert the user; return
 *       the User.</li>
 * </ol>
 *
 * <p>The first user to sign in is automatically promoted to {@code admin}.
 * Subsequent users get the {@code user} role.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
    private static final SecureRandom RNG = new SecureRandom();
    private static final int TOKEN_LENGTH_BYTES = 24;
    private static final long TOKEN_TTL_MINUTES = 15;
    private static final int MAX_LINKS_PER_HOUR = 5;

    private final UserRepository userRepository;
    private final MagicLinkTokenRepository tokenRepository;
    private final BCryptPasswordEncoder hasher = new BCryptPasswordEncoder();

    @Value("${auth.dev-expose-token:false}")
    private boolean devExposeToken;

    public AuthService(UserRepository userRepository, MagicLinkTokenRepository tokenRepository) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
    }

    /**
     * Issue a fresh magic-link token for {@code email}. Returns a map with
     * "ok": true, "token" (only in dev), and "email". Rate-limited to
     * {@value #MAX_LINKS_PER_HOUR} requests per hour per email.
     */
    public Map<String, Object> requestLink(String email) {
        Map<String, Object> result = new HashMap<>();
        if (email == null || !EMAIL_PATTERN.matcher(email.trim()).matches()) {
            result.put("ok", false);
            result.put("error", "invalid email");
            return result;
        }
        String normalised = email.trim().toLowerCase();

        try {
            long recent = tokenRepository.findByEmail(normalised).stream()
                    .filter(t -> t.getCreatedAt() != null
                            && t.getCreatedAt().isAfter(Instant.now().minus(1, ChronoUnit.HOURS)))
                    .count();
            if (recent >= MAX_LINKS_PER_HOUR) {
                result.put("ok", false);
                result.put("error", "too many sign-in requests, try again later");
                return result;
            }
        } catch (DataAccessException ignored) { /* fall through */ }

        String rawToken = generateToken();
        String tokenHash = hasher.encode(rawToken);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(TOKEN_TTL_MINUTES, ChronoUnit.MINUTES);
        try {
            MagicLinkToken doc = MagicLinkToken.builder()
                    .email(normalised)
                    .tokenHash(tokenHash)
                    .consumed(false)
                    .createdAt(now)
                    .expiresAt(expiresAt)
                    .expireAt(expiresAt)
                    .build();
            tokenRepository.save(doc);
        } catch (DataAccessException e) {
            log.warn("AuthService: could not persist magic link ({})", e.getMessage());
            result.put("ok", false);
            result.put("error", "could not create sign-in link");
            return result;
        }

        result.put("ok", true);
        result.put("email", normalised);
        result.put("expiresInMinutes", TOKEN_TTL_MINUTES);
        if (devExposeToken) {
            result.put("token", rawToken);
        }
        // Always returned so the controller can dispatch the email.
        result.put("_internalToken", rawToken);
        return result;
    }

    /**
     * Match an emailed token against any non-expired, non-consumed records
     * for that email. On success: mark consumed, upsert the user, return User.
     */
    public Optional<User> verifyLink(String email, String rawToken) {
        if (email == null || rawToken == null) return Optional.empty();
        String normalised = email.trim().toLowerCase();
        try {
            for (MagicLinkToken t : tokenRepository.findByEmail(normalised)) {
                if (Boolean.TRUE.equals(t.getConsumed())) continue;
                if (t.getExpiresAt() == null || t.getExpiresAt().isBefore(Instant.now())) continue;
                if (!hasher.matches(rawToken, t.getTokenHash())) continue;

                t.setConsumed(true);
                tokenRepository.save(t);

                return Optional.of(findOrCreateUser(normalised));
            }
        } catch (DataAccessException e) {
            log.warn("AuthService: verifyLink lookup failed ({})", e.getMessage());
        }
        return Optional.empty();
    }

    private User findOrCreateUser(String email) {
        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            existing.setLastLoginAt(LocalDateTime.now());
            existing.setUpdatedAt(LocalDateTime.now());
            try { return userRepository.save(existing); }
            catch (DataAccessException e) { return existing; }
        }

        // First user gets admin so the operator can bootstrap without an
        // out-of-band role flip.
        boolean isFirst = false;
        try { isFirst = userRepository.count() == 0; } catch (DataAccessException ignored) {}

        User u = User.builder()
                .email(email)
                .displayName(email.substring(0, Math.min(email.indexOf('@'), 30)))
                .role(isFirst ? "admin" : "user")
                .lastLoginAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        try { return userRepository.save(u); }
        catch (DataAccessException e) {
            log.warn("AuthService: could not persist new user ({}): {}", email, e.getMessage());
            return u;
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_LENGTH_BYTES];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
