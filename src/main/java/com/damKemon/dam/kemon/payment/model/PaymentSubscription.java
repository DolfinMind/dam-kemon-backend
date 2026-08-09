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
@Document(collection = "payment_subscriptions")
public class PaymentSubscription {
    @Id private String id;
    @Builder.Default private String provider = "lemon_squeezy";
    private String providerSubscriptionId;
    private String providerOrderId;
    private String checkoutId;
    private String appId;
    private String productCode;
    private String subjectType;
    private String subjectId;
    private String status;
    private boolean cancelled;
    private boolean testMode;
    private Instant trialEndsAt;
    private Instant renewsAt;
    private Instant endsAt;
    private Instant providerCreatedAt;
    private Instant providerUpdatedAt;
    private Instant createdAt;
    private Instant updatedAt;
}
