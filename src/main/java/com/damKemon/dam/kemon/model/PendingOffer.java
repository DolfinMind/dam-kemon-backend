package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * A community- or seller-submitted price for an existing product ("I sell this
 * for ৳X here"). Held for operator review; on approval it becomes a
 * {@link SitePrice} row on the product, raising sellers-per-product without a
 * crawl. Anonymous submissions are never trusted live — moderation is the gate.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "pending_offers")
public class PendingOffer {

    @Id
    private String id;

    private String productId;
    private String productName;   // denormalised for the admin list
    private String shopName;      // seller / shop the offer is from
    private String url;           // product URL at that shop
    private Double price;         // submitted price (BDT)
    private String contactEmail;  // optional, for sellers who want a reply

    private String submittedByAnon;
    private String status;        // "pending" | "approved" | "rejected"
    private String reviewNote;
    private LocalDateTime submittedAt;
    private LocalDateTime reviewedAt;
}
