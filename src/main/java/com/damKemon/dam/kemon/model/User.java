package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A signed-in user. We hold only the data that's truly useful to ship
 * features — never anything we'd be embarrassed to lose in a breach.
 *
 * <p>Regular users sign up with email + password (BCrypt) and verify their
 * address via a Resend token link; the owner keeps the fixed
 * username+password path. The {@link #role} field separates regular users
 * from admins (admins also need to clear the {@code X-Admin-Key} gate on
 * admin endpoints).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "users")
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    /**
     * Optional username for fixed-credential operators. Distinct from
     * {@link #email}: the owner signs in with this and a password instead
     * of the magic-link flow regular users get.
     */
    @Indexed(unique = true, sparse = true)
    private String username;

    /** BCrypt hash of the owner's password. Null for magic-link-only users. */
    private String passwordHash;

    private String displayName;

    /** "user" | "admin" */
    @Builder.Default
    private String role = "user";

    /** Optional avatar URL — filled from the Google profile picture on OAuth sign-in. */
    private String avatarUrl;

    /** Google account id (the ID token's {@code sub} claim) once linked. */
    @Indexed(sparse = true)
    private String googleSub;

    /**
     * Email ownership proven via the token link. Null on legacy rows (the
     * owner predates verification) — treat null as verified; only an explicit
     * {@code false} (fresh signup, link not yet clicked) blocks alert emails.
     */
    private Boolean emailVerified;

    /** One-shot email-verification token + expiry (48h). Cleared on use. */
    private String verifyToken;
    private LocalDateTime verifyTokenExpiry;

    /** One-shot password-reset token + expiry (1h). Cleared on use. */
    private String resetToken;
    private LocalDateTime resetTokenExpiry;

    // ── Optional profile (Account → Profile tab; all nullable, user-editable) ──
    /** BD mobile, as typed — normalised lightly, never required. */
    private String phone;
    /** One of Bangladesh's 64 districts. */
    private String district;
    /** "male" | "female" | "other" — free-form string, not an enum. */
    private String gender;
    private Integer birthYear;
    /** Catalog categories the user cares about (phones, laptops, …). */
    private java.util.List<String> interests;
    /** Mirrors a NewsletterSubscriber row; kept in sync by profile updates. */
    private Boolean newsletterOptIn;
    /** Where the account came from: "signup" | "owner-bootstrap". */
    private String signupSource;

    /** Community standing: starts at 1; +10 per review upvote, -2 per downvote. */
    @Builder.Default
    private Integer reputation = 1;

    /** When the user last successfully signed in. */
    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
