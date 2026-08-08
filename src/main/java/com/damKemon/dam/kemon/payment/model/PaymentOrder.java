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
@Document(collection = "payment_orders")
public class PaymentOrder {
    @Id private String id;
    private String provider;
    private String providerOrderId;
    private String checkoutId;
    private String appId;
    private String productCode;
    private String subjectType;
    private String subjectId;
    private long providerCustomerId;
    private long providerProductId;
    private long providerVariantId;
    private String status;
    /** Integer minor units in the order currency; never use floating point for money. */
    private long total;
    /** Refunded minor units reported by the provider, including partial refunds. */
    private long refundedAmount;
    private String currency;
    private boolean testMode;
    private Instant providerCreatedAt;
    private Instant providerUpdatedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
