package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One-shot, short-lived token a user clicks from their email to sign in.
 * BCrypt-hashed at rest so a database leak doesn't grant logins. TTL is
 * enforced both at the application layer ({@link #expiresAt}) and as a
 * Mongo TTL index ({@link #expireAt}) to auto-clean expired tokens.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "magic_links")
public class MagicLinkToken {

    @Id
    private String id;

    @Indexed
    private String tokenHash;

    @Indexed
    private String email;

    /** Token consumed flag — single-use only. */
    @Builder.Default
    private Boolean consumed = false;

    private Instant createdAt;

    private Instant expiresAt;

    /** Mongo TTL: auto-delete the document at this Instant. */
    @Indexed(expireAfterSeconds = 0)
    private Instant expireAt;
}
