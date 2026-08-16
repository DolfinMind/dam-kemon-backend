package com.damKemon.dam.kemon.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deployment role of this process.
 *
 * <p>The API ("web") serves traffic and <b>never crawls</b>; a separate
 * "worker" process owns all heavy scraping/indexing so a runaway crawl can
 * never spike CPU/memory inside — and take down — the request-serving JVM.
 *
 * <p>Set with {@code app.role=worker} (the worker systemd unit passes
 * {@code --app.role=worker}; the {@code worker} Spring profile also sets it).
 * Defaults to {@code web} so the API node is crawl-free unless told otherwise.
 */
@Component
public class AppRole {

    private final String role;

    public AppRole(@Value("${app.role:web}") String role) {
        this.role = (role == null || role.isBlank()) ? "web" : role.trim().toLowerCase();
    }

    public String role() { return role; }

    public boolean isWorker() { return "worker".equals(role); }

    public boolean isWeb() { return !isWorker(); }
}
