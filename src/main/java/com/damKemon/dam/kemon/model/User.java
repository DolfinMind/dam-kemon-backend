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
 * <p>Passwordless: sign-in is via email magic links. The {@link #role}
 * field separates regular users from admins (admins also need to clear
 * the {@code X-Admin-Key} gate on admin endpoints).
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

    private String displayName;

    /** "user" | "admin" */
    @Builder.Default
    private String role = "user";

    /** Optional avatar URL — null for now; populated when we add OAuth. */
    private String avatarUrl;

    /** When the user last successfully signed in. */
    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
