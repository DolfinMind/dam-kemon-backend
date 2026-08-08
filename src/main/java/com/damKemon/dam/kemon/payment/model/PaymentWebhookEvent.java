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
@Document(collection = "payment_webhook_events")
public class PaymentWebhookEvent {
    /** SHA-256 of the signed raw payload: idempotent without retaining the sensitive payload. */
    @Id private String payloadSha256;
    private String eventName;
    private String resourceType;
    private String resourceId;
    private String checkoutId;
    private String appId;
    private Boolean testMode;
    private String status;
    private String errorCode;
    private Instant receivedAt;
    private Instant processedAt;
}
