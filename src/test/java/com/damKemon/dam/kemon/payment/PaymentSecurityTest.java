package com.damKemon.dam.kemon.payment;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class PaymentSecurityTest {
    private static final String FINGERPRINT_SECRET = "f".repeat(32);
    private static final String WEBHOOK_SECRET = "w".repeat(32);

    @Test void hashesServerKeysAndComparesWithoutStoringPlaintext() {
        PaymentSecurity security = new PaymentSecurity(FINGERPRINT_SECRET, WEBHOOK_SECRET);
        String key = security.newApiKey("rewire");
        String digest = security.apiKeyDigest(key);

        assertTrue(key.startsWith("pay_sk_rewire_"));
        assertEquals(64, digest.length());
        assertFalse(digest.contains(key));
        assertTrue(security.apiKeyMatches(key, digest));
        assertFalse(security.apiKeyMatches(key + "x", digest));
    }

    @Test void rejectsTamperedWebhookPayload() throws Exception {
        PaymentSecurity security = new PaymentSecurity(FINGERPRINT_SECRET, WEBHOOK_SECRET);
        byte[] signed = "{\"data\":1}".getBytes(StandardCharsets.UTF_8);
        String signature = hmac(WEBHOOK_SECRET, signed);

        assertTrue(security.validWebhook(signed, signature));
        assertFalse(security.validWebhook("{\"data\":2}".getBytes(StandardCharsets.UTF_8), signature));
        assertFalse(security.validWebhook(signed, "not-hex"));
    }

    private static String hmac(String secret, byte[] payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload));
    }
}
