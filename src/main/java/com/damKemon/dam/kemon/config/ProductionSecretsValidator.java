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
    @Value("${payments.enabled:false}") private boolean paymentsEnabled;
    @Value("${payments.fingerprint-secret:}") private String paymentFingerprintSecret;
    @Value("${payments.lemon.test-api-key:${payments.lemon.api-key:}}") private String lemonTestApiKey;
    @Value("${payments.lemon.live-api-key:}") private String lemonLiveApiKey;
    @Value("${payments.lemon.webhook-secret:}") private String lemonWebhookSecret;
    @Value("${payments.lemon.webhook-url:}") private String lemonWebhookUrl;
    @Value("${payments.rewire.enabled:false}") private boolean rewirePaymentsEnabled;
    @Value("${payments.rewire.store-id:0}") private long rewireStoreId;
    @Value("${payments.rewire.product-id:0}") private long rewireProductId;
    @Value("${payments.rewire.variant-id:0}") private long rewireVariantId;
    @Value("${payments.rewire.test-mode:true}") private boolean rewireTestMode;
    private final AppRole role;

    public ProductionSecretsValidator(AppRole role) { this.role = role; }

    @Override public void run(ApplicationArguments args) {
        if (!role.isWeb()) return;
        if (!secret(jwt, 32) || !secret(adminKey, 32) || !secret(ownerUsername, 1) || !secret(ownerPassword, 12) || !secret(resendKey, 1)) {
            throw new IllegalStateException("Production web requires non-placeholder AUTH_JWT_SECRET (32+), ADMIN_API_KEY (32+), OWNER_PASSWORD (12+), and RESEND_API_KEY");
        }
        boolean anyLemonKey = secret(lemonTestApiKey, 1) || secret(lemonLiveApiKey, 1);
        if (paymentsEnabled && (!secret(paymentFingerprintSecret, 32) || !anyLemonKey
                || !secret(lemonWebhookSecret, 32) || lemonWebhookSecret.length() > 40
                || lemonWebhookUrl == null || !lemonWebhookUrl.startsWith("https://"))) {
            throw new IllegalStateException("Enabled payments require PAYMENTS_FINGERPRINT_SECRET (32+), at least one mode-specific Lemon API key, LEMON_SQUEEZY_WEBHOOK_SECRET (32-40), and an HTTPS webhook URL");
        }
        if (paymentsEnabled && rewirePaymentsEnabled
                && (rewireStoreId <= 0 || rewireProductId <= 0 || rewireVariantId <= 0)) {
            throw new IllegalStateException("Enabled Rewire payments require positive Lemon store, product, and variant IDs");
        }
        if (paymentsEnabled && rewirePaymentsEnabled
                && !(rewireTestMode ? secret(lemonTestApiKey, 1) : secret(lemonLiveApiKey, 1))) {
            throw new IllegalStateException("Enabled Rewire payments require the Lemon API key for the configured test/live mode");
        }
    }

    private static boolean secret(String value, int min) {
        return value != null && value.length() >= min && !value.toUpperCase().contains("REPLACE") && !value.contains("${");
    }
}
