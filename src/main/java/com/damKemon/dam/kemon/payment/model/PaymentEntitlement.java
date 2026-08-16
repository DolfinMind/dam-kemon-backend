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
@Document(collection = "payment_entitlements")
public class PaymentEntitlement {
    @Id private String id;
    private String appId;
    private String productCode;
    private String entitlementCode;
    private String subjectType;
    private String subjectId;
    private String checkoutId;
    private String providerOrderId;
    private String providerLicenseId;
    private String providerSubscriptionId;
    private String providerInstanceId;
    private String status;
    private boolean testMode;
    private Instant expiresAt;
    private Instant lastValidatedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
