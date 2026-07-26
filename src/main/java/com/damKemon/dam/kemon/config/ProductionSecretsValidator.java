package com.damKemon.dam.kemon.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Stops the production web process before it can run with example credentials. */
@Component
@Profile("production")
public class ProductionSecretsValidator implements ApplicationRunner {
    @Value("${auth.jwt-secret}") private String jwt;
    @Value("${admin.api-key}") private String adminKey;
    @Value("${owner.password}") private String ownerPassword;
    @Value("${owner.username}") private String ownerUsername;
    @Value("${resend.api-key}") private String resendKey;
    private final AppRole role;

    public ProductionSecretsValidator(AppRole role) { this.role = role; }

    @Override public void run(ApplicationArguments args) {
        if (!role.isWeb()) return;
        if (!secret(jwt, 32) || !secret(adminKey, 32) || !secret(ownerUsername, 1) || !secret(ownerPassword, 12) || !secret(resendKey, 1)) {
            throw new IllegalStateException("Production web requires non-placeholder AUTH_JWT_SECRET (32+), ADMIN_API_KEY (32+), OWNER_PASSWORD (12+), and RESEND_API_KEY");
        }
    }

    private static boolean secret(String value, int min) {
        return value != null && value.length() >= min && !value.toUpperCase().contains("REPLACE") && !value.contains("${");
    }
}
