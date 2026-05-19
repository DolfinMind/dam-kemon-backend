package com.damKemon.dam.kemon.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Issues + verifies HS256 JWTs for user sessions. The signing secret is
 * derived from {@code AUTH_JWT_SECRET}; tokens last {@code AUTH_JWT_TTL_DAYS}
 * (default 30) so the user doesn't have to re-auth every time.
 *
 * <p>Refresh tokens aren't necessary at this scale — a 30-day JWT is a fair
 * trade-off between UX and security. The {@code role} claim is included so
 * routes can authorise without an extra DB lookup.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey signingKey;
    private final long ttlSeconds;

    public JwtService(@Value("${auth.jwt-secret:}") String configuredSecret,
                      @Value("${auth.jwt-ttl-days:30}") int ttlDays) {
        String s = (configuredSecret == null || configuredSecret.isBlank())
                ? "dev-only-do-not-use-in-prod-please-set-AUTH_JWT_SECRET" : configuredSecret;
        byte[] derived;
        try {
            derived = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        this.signingKey = Keys.hmacShaKeyFor(derived);
        this.ttlSeconds = (long) ttlDays * 24 * 3600;

        if (configuredSecret == null || configuredSecret.isBlank()) {
            log.warn("AUTH_JWT_SECRET not set — using a dev fallback. Set this env var in staging+prod.");
        }
    }

    public String issue(String userId, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(signingKey)
                .compact();
    }

    public Claims parse(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
