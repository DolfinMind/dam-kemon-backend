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
@Document(collection = "payment_admin_actions")
public class PaymentAdminAction {
    @Id private String id;
    private String appId;
    private String action;
    private String resourceType;
    private String resourceId;
    private boolean testMode;
    private String actor;
    /** Non-secret, bounded context such as a refund amount or activation limit. */
    private String summary;
    private String status;
    private String errorCode;
    private Instant createdAt;
    private Instant completedAt;
}
