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
@Document(collection = "payment_licenses")
public class PaymentLicense {
    @Id private String id;
    private String provider;
    private String providerLicenseId;
    private String providerOrderId;
    private String checkoutId;
    private String appId;
    private String productCode;
    private String licenseKeyFingerprint;
    private String keyShort;
    private String status;
    private Integer activationLimit;
    private int activationUsage;
    private boolean testMode;
    private Instant expiresAt;
    private Instant createdAt;
    private Instant updatedAt;
}
