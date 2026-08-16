package com.damKemon.dam.kemon.payment.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "payment_checkouts")
public class PaymentCheckout {
    @Id private String id;
    private String appId;
    private String productCode;
    private String entitlementCode;
    private String subjectType;
    /** Opaque server-side subject. External and installation identifiers are HMAC-pseudonymised. */
    private String subjectId;
    private String idempotencyKey;
    private String status;
    private String providerCheckoutId;
    private String checkoutUrl;
    private boolean testMode;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
