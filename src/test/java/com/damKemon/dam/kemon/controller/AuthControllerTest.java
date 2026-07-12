package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import com.damKemon.dam.kemon.service.JwtService;
import com.damKemon.dam.kemon.service.ResendService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Auth behaviours worth locking down:
 * - owner login survives duplicate username rows (post-migration failure);
 * - regular users can sign up (hashed password, unverified, verify token)
 *   and then sign in BY EMAIL through the same login endpoint;
 * - wrong password is a clean 401, not a 500;
 * - verify flips emailVerified and burns the token; expired tokens are 410.
 */
class AuthControllerTest {

    private final BCryptPasswordEncoder enc = new BCryptPasswordEncoder();

    private AuthController controller(UserRepository users, JwtService jwt) {
        return new AuthController(jwt, users,
                mock(NewsletterSubscriberRepository.class), mock(ResendService.class),
                mock(com.damKemon.dam.kemon.service.AnalyticsService.class));
    }

    @Test
    void loginSucceedsDespiteDuplicateUsernameRows() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthController controller = controller(users, jwt);

        String pw = "$Sm7406.2025";
        // A stale duplicate with an old hash + the real owner — both share the username.
        User stale = User.builder().id("a").username("ssm@2026").email("a@owner.local")
                .role("admin").passwordHash(enc.encode("old-different-password")).build();
        User owner = User.builder().id("b").username("ssm@2026").email("b@owner.local")
                .role("admin").passwordHash(enc.encode(pw)).build();
        when(users.findAllByUsername("ssm@2026")).thenReturn(List.of(stale, owner));
        when(users.findAllByEmail(anyString())).thenReturn(List.of());
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
        AuthController controller = controller(users, jwt);

        when(users.findAllByUsername("ssm@2026")).thenReturn(List.of(
                User.builder().id("b").username("ssm@2026").role("admin")
                        .passwordHash(enc.encode("correct-horse")).build()));
        when(users.findAllByEmail(anyString())).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp =
                controller.login(Map.of("username", "ssm@2026", "password", "wrong"));

        assertEquals(401, resp.getStatusCode().value());
        assertEquals("invalid credentials", resp.getBody().get("error"));
    }

    @Test
    void signupCreatesUnverifiedUserAndEmailLoginWorks() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthController controller = controller(users, jwt);

        when(users.findAllByEmail("rima@example.com")).thenReturn(List.of());
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(jwt.issue(any(), any(), any())).thenReturn("tok-new");

        ResponseEntity<Map<String, Object>> resp = controller.signup(Map.of(
                "name", "Rima", "email", "Rima@Example.com", "password", "s3cret-pass",
                "phone", "01712345678"));

        assertEquals(200, resp.getStatusCode().value());
        assertEquals("tok-new", resp.getBody().get("token"));
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(users).save(cap.capture());
        User saved = cap.getValue();
        assertEquals("rima@example.com", saved.getEmail(), "email is lower-cased");
        assertEquals("user", saved.getRole());
        assertEquals(Boolean.FALSE, saved.getEmailVerified());
        assertNotNull(saved.getVerifyToken(), "verification token issued");
        assertTrue(enc.matches("s3cret-pass", saved.getPasswordHash()), "password is BCrypt-hashed");
        assertFalse(saved.getPasswordHash().contains("s3cret"), "never stored in the clear");

        // …and the same login endpoint accepts the EMAIL as identifier.
        when(users.findAllByUsername("rima@example.com")).thenReturn(List.of());
        when(users.findAllByEmail("rima@example.com")).thenReturn(List.of(saved));
        ResponseEntity<Map<String, Object>> login =
                controller.login(Map.of("email", "rima@example.com", "password", "s3cret-pass"));
        assertEquals(200, login.getStatusCode().value());
    }

    @Test
    void duplicateEmailSignupIs409() {
        UserRepository users = mock(UserRepository.class);
        AuthController controller = controller(users, mock(JwtService.class));
        when(users.findAllByEmail("rima@example.com"))
                .thenReturn(List.of(User.builder().id("x").email("rima@example.com").build()));

        ResponseEntity<Map<String, Object>> resp = controller.signup(Map.of(
                "name", "Rima", "email", "rima@example.com", "password", "s3cret-pass"));
        assertEquals(409, resp.getStatusCode().value());
    }

    @Test
    void verifyFlipsFlagAndBurnsToken_expiredIs410() {
        UserRepository users = mock(UserRepository.class);
        AuthController controller = controller(users, mock(JwtService.class));

        User fresh = User.builder().id("u1").email("rima@example.com").emailVerified(false)
                .verifyToken("tok-ok").verifyTokenExpiry(LocalDateTime.now().plusHours(1)).build();
        when(users.findByVerifyToken("tok-ok")).thenReturn(Optional.of(fresh));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        assertEquals(200, controller.verify(Map.of("token", "tok-ok")).getStatusCode().value());
        assertEquals(Boolean.TRUE, fresh.getEmailVerified());
        assertNull(fresh.getVerifyToken(), "token is one-shot");

        User stale = User.builder().id("u2").email("old@example.com").emailVerified(false)
                .verifyToken("tok-old").verifyTokenExpiry(LocalDateTime.now().minusMinutes(1)).build();
        when(users.findByVerifyToken("tok-old")).thenReturn(Optional.of(stale));
        assertEquals(410, controller.verify(Map.of("token", "tok-old")).getStatusCode().value());
    }

    @Test
    void googleLoginRejectsForeignAudienceAndCreatesVerifiedUser() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthController controller = controller(users, jwt);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "googleClientId", "our-client-id");
        org.springframework.web.client.RestTemplate http = mock(org.springframework.web.client.RestTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "http", http);

        // token minted for someone ELSE's app → 401, no account touched
        when(http.getForObject(anyString(), org.mockito.ArgumentMatchers.eq(Map.class), anyString()))
                .thenReturn(Map.of("aud", "attacker-client-id", "iss", "https://accounts.google.com",
                        "email", "rima@gmail.com", "email_verified", "true", "sub", "g-123"));
        assertEquals(401, controller.google(Map.of("credential", "tok")).getStatusCode().value());
        org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).save(any());

        // our audience → user created, email pre-verified, google linked
        when(http.getForObject(anyString(), org.mockito.ArgumentMatchers.eq(Map.class), anyString()))
                .thenReturn(Map.of("aud", "our-client-id", "iss", "https://accounts.google.com",
                        "email", "Rima@Gmail.com", "email_verified", "true", "sub", "g-123", "name", "Rima"));
        when(users.findAllByEmail("rima@gmail.com")).thenReturn(List.of());
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(jwt.issue(any(), any(), any())).thenReturn("tok-g");

        ResponseEntity<Map<String, Object>> resp = controller.google(Map.of("credential", "tok"));
        assertEquals(200, resp.getStatusCode().value());
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(users).save(cap.capture());
        assertEquals("rima@gmail.com", cap.getValue().getEmail());
        assertEquals(Boolean.TRUE, cap.getValue().getEmailVerified(), "Google email counts as verified");
        assertEquals("g-123", cap.getValue().getGoogleSub());
        assertNull(cap.getValue().getPasswordHash(), "no password on a Google-only account");
    }

    @Test
    void googleLinkToUnverifiedAccountWipesAttackerPassword() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthController controller = controller(users, jwt);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "googleClientId", "our-client-id");
        org.springframework.web.client.RestTemplate http = mock(org.springframework.web.client.RestTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "http", http);
        when(http.getForObject(anyString(), org.mockito.ArgumentMatchers.eq(Map.class), anyString()))
                .thenReturn(Map.of("aud", "our-client-id", "iss", "https://accounts.google.com",
                        "email", "victim@gmail.com", "email_verified", "true", "sub", "g-1", "name", "Victim"));
        // Attacker pre-registered victim's email, never verified it.
        User prereg = User.builder().id("v").email("victim@gmail.com").emailVerified(false)
                .passwordHash(enc.encode("attacker-password")).build();
        when(users.findAllByEmail("victim@gmail.com")).thenReturn(List.of(prereg));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(jwt.issue(any(), any(), any())).thenReturn("tok");

        controller.google(Map.of("credential", "tok"));

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(users).save(cap.capture());
        assertNull(cap.getValue().getPasswordHash(),
                "the attacker's pre-set password must be wiped when Google proves ownership");
        assertEquals(Boolean.TRUE, cap.getValue().getEmailVerified());
        assertEquals("g-1", cap.getValue().getGoogleSub());
    }

    @Test
    void googleLinkToVerifiedAccountKeepsPassword() {
        UserRepository users = mock(UserRepository.class);
        JwtService jwt = mock(JwtService.class);
        AuthController controller = controller(users, jwt);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "googleClientId", "our-client-id");
        org.springframework.web.client.RestTemplate http = mock(org.springframework.web.client.RestTemplate.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "http", http);
        when(http.getForObject(anyString(), org.mockito.ArgumentMatchers.eq(Map.class), anyString()))
                .thenReturn(Map.of("aud", "our-client-id", "iss", "https://accounts.google.com",
                        "email", "real@gmail.com", "email_verified", "true", "sub", "g-2", "name", "Real"));
        // A legitimately-verified email+password user keeps their password on link.
        String hash = enc.encode("my-real-password");
        User verified = User.builder().id("r").email("real@gmail.com").emailVerified(true).passwordHash(hash).build();
        when(users.findAllByEmail("real@gmail.com")).thenReturn(List.of(verified));
        when(users.save(any(User.class))).thenAnswer(i -> i.getArgument(0));
        when(jwt.issue(any(), any(), any())).thenReturn("tok");

        controller.google(Map.of("credential", "tok"));

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        org.mockito.Mockito.verify(users).save(cap.capture());
        assertEquals(hash, cap.getValue().getPasswordHash(), "a verified user's own password survives Google linking");
    }
}
