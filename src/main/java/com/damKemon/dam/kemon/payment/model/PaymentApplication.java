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
@Document(collection = "payment_applications")
public class PaymentApplication {
    @Id private String appId;
    private String displayName;
    @Builder.Default private boolean active = true;
    private boolean acceptDamkemonJwt;
    private boolean publicCheckout;
    private boolean publicLicense;
    /** SHA-256 of a high-entropy server key. The plaintext is returned only when created/rotated. */
    private String apiKeySha256;
    private Instant createdAt;
    private Instant updatedAt;
}
