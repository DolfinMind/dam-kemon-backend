package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.User;
import com.damKemon.dam.kemon.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Ensures the fixed-credential owner user exists at boot. Idempotent —
 * re-running just refreshes the BCrypt hash if the password env var
 * changed since last boot.
 *
 * <p>Owner login is a separate path from magic-link: the owner uses
 * {@code POST /api/auth/login} with their {@code username + password};
 * the existing magic-link flow keeps working for every other signed-in
 * user.
 */
@Component
public class OwnerBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

    private final UserRepository users;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Value("${owner.username:}")
    private String ownerUsername;

    @Value("${owner.password:}")
    private String ownerPassword;

    @Value("${owner.email:}")
    private String ownerEmail;

    public OwnerBootstrap(UserRepository users) {
        this.users = users;
    }

    // ApplicationReadyEvent, not @PostConstruct — guarantees Mongo is up so the owner
    // user is reliably (re)created on every boot, even on a cold/slow start.
    @EventListener(ApplicationReadyEvent.class)
    public void ensureOwner() {
        if (ownerUsername == null || ownerUsername.isBlank()
                || ownerPassword == null || ownerPassword.isBlank()) {
            log.warn("OwnerBootstrap: owner.username / owner.password not set — "
                    + "fixed-credential sign-in disabled.");
            return;
        }
        try {
            User existing = users.findByUsername(ownerUsername).orElse(null);
            String hash = encoder.encode(ownerPassword);
            String email = (ownerEmail == null || ownerEmail.isBlank())
                    ? ownerUsername + "@owner.local" : ownerEmail.toLowerCase();
            if (existing == null) {
                User owner = User.builder()
                        .username(ownerUsername)
                        .email(email)
                        .passwordHash(hash)
                        .displayName(ownerUsername)
                        .role("admin")
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                users.save(owner);
                log.info("OwnerBootstrap: created owner user '{}'", ownerUsername);
            } else {
                // Refresh hash + ensure admin role
                existing.setPasswordHash(hash);
                existing.setRole("admin");
                if (existing.getEmail() == null || existing.getEmail().isBlank()) {
                    existing.setEmail(email);
                }
                existing.setUpdatedAt(LocalDateTime.now());
                users.save(existing);
                log.info("OwnerBootstrap: refreshed owner '{}'", ownerUsername);
            }
        } catch (DataAccessException e) {
            log.warn("OwnerBootstrap: could not reach Mongo ({}). "
                    + "Owner sign-in will fail until Mongo is up.", e.getMessage());
        }
    }
}
