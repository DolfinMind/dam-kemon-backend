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
@Document(collection = "payment_products")
public class PaymentProduct {
    @Id private String id;
    private String appId;
    /** Stable client-facing identifier such as lifetime or monthly. */
    private String code;
    /** Capability granted after a valid activation. */
    private String entitlementCode;
    @Builder.Default private String provider = "lemon_squeezy";
    private long storeId;
    private long productId;
    private long variantId;
    private boolean testMode;
    @Builder.Default private boolean active = true;
    private String redirectUrl;
    private Instant createdAt;
    private Instant updatedAt;
}
