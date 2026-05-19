package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.MagicLinkToken;
import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.MagicLinkTokenRepository;
import com.damKemon.dam.kemon.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository users;
    private MagicLinkTokenRepository tokens;
    private AuthService auth;

    private final Map<String, User> userStore = new HashMap<>();
    private final List<MagicLinkToken> tokenStore = new ArrayList<>();

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        tokens = mock(MagicLinkTokenRepository.class);
        auth = new AuthService(users, tokens);
        ReflectionTestUtils.setField(auth, "devExposeToken", true);
        userStore.clear();
        tokenStore.clear();

        when(users.findByEmail(anyString())).thenAnswer(inv ->
                Optional.ofNullable(userStore.get(((String) inv.getArgument(0)).toLowerCase())));
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getId() == null) u.setId(UUID.randomUUID().toString());
            userStore.put(u.getEmail(), u);
            return u;
        });
        when(users.count()).thenAnswer(inv -> (long) userStore.size());
        when(tokens.findByEmail(anyString())).thenAnswer(inv -> {
            String e = inv.getArgument(0);
            return tokenStore.stream().filter(t -> e.equals(t.getEmail())).toList();
        });
        when(tokens.save(any(MagicLinkToken.class))).thenAnswer(inv -> {
            MagicLinkToken t = inv.getArgument(0);
            if (t.getId() == null) t.setId(UUID.randomUUID().toString());
            tokenStore.add(t);
            return t;
        });
    }

    @Test
    void rejectsInvalidEmail() {
        Map<String, Object> r = auth.requestLink("not-an-email");
        assertEquals(false, r.get("ok"));
    }

    @Test
    void normalisesEmailToLower() {
        Map<String, Object> r = auth.requestLink("Alice@Example.com");
        assertEquals(true, r.get("ok"));
        assertEquals("alice@example.com", r.get("email"));
    }

    @Test
    void verifyConsumesTokenAndPromotesFirstUserToAdmin() {
        Map<String, Object> r = auth.requestLink("first@example.com");
        String token = (String) r.get("_internalToken");
        assertNotNull(token);

        Optional<User> u = auth.verifyLink("first@example.com", token);
        assertTrue(u.isPresent());
        assertEquals("admin", u.get().getRole());

        // Second verify with same token must fail
        Optional<User> u2 = auth.verifyLink("first@example.com", token);
        assertTrue(u2.isEmpty());
    }

    @Test
    void secondUserIsRegularRole() {
        // First sign-in
        Map<String, Object> r1 = auth.requestLink("admin@example.com");
        auth.verifyLink("admin@example.com", (String) r1.get("_internalToken"));

        // Second sign-in by different user
        Map<String, Object> r2 = auth.requestLink("bob@example.com");
        Optional<User> bob = auth.verifyLink("bob@example.com", (String) r2.get("_internalToken"));
        assertTrue(bob.isPresent());
        assertEquals("user", bob.get().getRole());
    }

    @Test
    void rejectsBogusToken() {
        auth.requestLink("alice@example.com");
        Optional<User> u = auth.verifyLink("alice@example.com", "totally-wrong-token");
        assertTrue(u.isEmpty());
    }
}
