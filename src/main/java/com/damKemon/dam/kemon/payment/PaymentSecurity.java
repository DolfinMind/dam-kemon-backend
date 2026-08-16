package com.damKemon.dam.kemon.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class PaymentSecurity {
    private static final Logger log = LoggerFactory.getLogger(PaymentSecurity.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] fingerprintSecret;
    private final byte[] webhookSecret;

    public PaymentSecurity(@Value("${payments.fingerprint-secret:}") String fingerprintSecret,
                           @Value("${payments.lemon.webhook-secret:}") String webhookSecret) {
        String effective = fingerprintSecret == null || fingerprintSecret.isBlank()
                ? "dev-only-payment-fingerprint-secret" : fingerprintSecret;
        this.fingerprintSecret = effective.getBytes(StandardCharsets.UTF_8);
        this.webhookSecret = webhookSecret == null ? new byte[0] : webhookSecret.getBytes(StandardCharsets.UTF_8);
        if (fingerprintSecret == null || fingerprintSecret.isBlank()) {
            log.warn("PAYMENTS_FINGERPRINT_SECRET is not set; payment endpoints must stay disabled outside local development.");
        }
    }

    public String newApiKey(String appId) {
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        return "pay_sk_" + appId + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public String apiKeyDigest(String apiKey) {
        return sha256(apiKey == null ? new byte[0] : apiKey.getBytes(StandardCharsets.UTF_8));
    }

    public boolean apiKeyMatches(String candidate, String expectedDigest) {
        if (candidate == null || expectedDigest == null || expectedDigest.length() != 64) return false;
        return MessageDigest.isEqual(
                apiKeyDigest(candidate).getBytes(StandardCharsets.US_ASCII),
                expectedDigest.getBytes(StandardCharsets.US_ASCII));
    }

    public String subjectFingerprint(String kind, String value) {
        return kind + ":" + hmac("subject:" + kind + ":" + value);
    }

    public String licenseFingerprint(String licenseKey) {
        return hmac("license:" + licenseKey.trim());
    }

    public boolean licenseMatches(String licenseKey, String expectedFingerprint) {
        if (licenseKey == null || expectedFingerprint == null) return false;
        return MessageDigest.isEqual(licenseFingerprint(licenseKey).getBytes(StandardCharsets.US_ASCII),
                expectedFingerprint.getBytes(StandardCharsets.US_ASCII));
    }

    public String payloadDigest(byte[] payload) {
        return sha256(payload);
    }

    public boolean validWebhook(byte[] payload, String signatureHex) {
        if (webhookSecret.length == 0 || signatureHex == null || signatureHex.length() != 64) return false;
        try {
            byte[] actual = HexFormat.of().parseHex(signatureHex);
            byte[] expected = hmac(webhookSecret, payload);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String hmac(String value) {
        return HexFormat.of().formatHex(hmac(fingerprintSecret, value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
