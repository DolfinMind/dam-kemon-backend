package com.damKemon.dam.kemon.service;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {

    @Test
    void roundTripsClaims() {
        JwtService jwt = new JwtService("a-test-secret-32-chars-or-more!!!", 30);
        String token = jwt.issue("user-1", "alice@example.com", "user");
        Claims c = jwt.parse(token);
        assertNotNull(c);
        assertEquals("user-1", c.getSubject());
        assertEquals("alice@example.com", c.get("email", String.class));
        assertEquals("user", c.get("role", String.class));
    }

    @Test
    void rejectsTokenSignedByDifferentSecret() {
        JwtService a = new JwtService("secret-aaaa-aaaa-aaaa-aaaa-aaaa", 30);
        JwtService b = new JwtService("secret-bbbb-bbbb-bbbb-bbbb-bbbb", 30);
        String tokenA = a.issue("u", "x@y.com", "user");
        assertNotNull(a.parse(tokenA));
        assertNull(b.parse(tokenA));
    }

    @Test
    void rejectsGarbage() {
        JwtService j = new JwtService("any-secret-any-secret-any-secret", 30);
        assertNull(j.parse("not-a-jwt"));
        assertNull(j.parse(""));
        assertNull(j.parse(null));
    }
}
