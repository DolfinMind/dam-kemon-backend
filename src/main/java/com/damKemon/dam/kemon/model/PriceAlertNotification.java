package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A single fired price-drop alert. We persist these for three reasons:
 *   1. Powers the in-app "Notifications" bell — the user reads them via
 *      {@code GET /api/account/notifications}.
 *   2. Lets us debounce: never fire twice for the same crossing within a day.
 *   3. Bookkeeping for the alert engine — we replay {@code unread=true} rows
 *      after an SMTP outage instead of dropping them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "price_alert_notifications")
@CompoundIndex(name = "user_unread_idx", def = "{'userId': 1, 'unread': 1, 'createdAt': -1}")
public class PriceAlertNotification {

    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private String productId;

    private String productName;
    private String productImageUrl;

    /** Price when the user added the item — anchor for "you save X" display. */
    private Double priceAtAdd;
    /** Price right before this notification was generated. */
    private Double previousPrice;
    /** The lowestPrice that crossed the threshold. */
    private Double currentPrice;
    /** "drop_pct" / "hit_target". */
    private String reason;
    /** Channel(s) we *attempted* to send. Audit-only. */
    private String sentVia;

    @Indexed
    @Builder.Default
    private Boolean unread = true;

    @Indexed
    private LocalDateTime createdAt;
}
