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
 * Append-only audit row for every admin endpoint hit. Powers the
 * "who hit which admin endpoint when" view in the admin console.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "audit_log")
public class AuditLogEntry {

    @Id
    private String id;

    private String method;

    @Indexed
    private String path;

    private Integer status;

    /** "admin-key" | "jwt:<userId>" | "anon" */
    private String actor;

    private String ipHash;

    @Indexed(expireAfterSeconds = 60 * 60 * 24 * 90)
    private Instant ts;
}
