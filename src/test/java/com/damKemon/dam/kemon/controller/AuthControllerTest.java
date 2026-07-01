package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.damKemon.dam.kemon.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guards owner login against the post-migration failure: a re-import left two
 * "users" rows with the same username, so the single-result findByUsername threw
 * IncorrectResultSizeDataAccessException and login 500'd ("could not sign in").
 * Login now matches the password across all rows with that username.
 */
class AuthControllerTest {

    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

    @Test
    void loginSucceedsDespiteDuplicateUsernameRows() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthController controller = new AuthController(jwt, users);

        String pw = "$Sm7406.2025";
        // A stale duplicate with an old hash + the real owner — both share the username.
        User stale = User.builder().id("a").username("ssm@2026").email("a@owner.local")
                .role("admin").passwordHash(enc.encode("old-different-password")).build();
        User owner = User.builder().id("b").username("ssm@2026").email("b@owner.local")
                .role("admin").passwordHash(enc.encode(pw)).build();
        when(users.findAllByUsername("ssm@2026")).thenReturn(List.of(stale, owner));
        when(jwt.issue(any(), any(), any())).thenReturn("tok-123");

        ResponseEntity<Map<String, Object>> resp =
                controller.login(Map.of("username", "ssm@2026", "password", pw));

        assertEquals(200, resp.getStatusCode().value(), "duplicate rows must not break login");
        assertEquals("tok-123", resp.getBody().get("token"));
    }

    @Test
    void wrongPasswordIs401NotError() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthController controller = new AuthController(jwt, users);

        when(users.findAllByUsername("ssm@2026")).thenReturn(List.of(
                User.builder().id("b").username("ssm@2026").role("admin")
                        .passwordHash(enc.encode("correct-horse")).build()));

        ResponseEntity<Map<String, Object>> resp =
                controller.login(Map.of("username", "ssm@2026", "password", "wrong"));

        assertEquals(401, resp.getStatusCode().value());
        assertEquals("invalid credentials", resp.getBody().get("error"));
    }
}
