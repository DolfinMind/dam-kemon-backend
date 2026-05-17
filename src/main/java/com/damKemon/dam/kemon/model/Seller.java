package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a third-party seller — typically a Facebook page or messenger-based
 * shop, but the type field allows for instagram / website / marketplace later.
 *
 * Real Facebook scraping is hostile and unstable; the recommended way to grow this
 * is a seller-onboarding portal where shop owners self-list. The data model is
 * shaped for both: scraped pages (verified=false, source=fb_scrape) and onboarded
 * pages (verified=true, source=portal).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "sellers")
public class Seller {
    @Id
    private String id;

    private String name;
    private String slug;
    private String type;          // "facebook" | "instagram" | "website" | "marketplace"
    private String url;           // canonical URL: e.g., facebook.com/gadgetloungebd
    private String messengerUrl;
    private String avatarUrl;
    private String coverUrl;

    private String city;          // e.g., "Dhaka", "Chittagong"
    private String area;          // e.g., "Dhanmondi", "Mirpur"

    private Long followers;
    private Double rating;
    private Integer reviewCount;
    private Boolean verified;
    private Boolean codAvailable;
    private Boolean sameDayDelivery;
    private String avgReplyTime;  // "5 min" / "1 hour"

    @Builder.Default
    private List<String> categories = new ArrayList<>();   // ProductCategory.name()
    @Builder.Default
    private List<String> brands = new ArrayList<>();
    @Builder.Default
    private List<String> tags = new ArrayList<>();

    private String source;        // "portal" | "fb_scrape" | "manual"
    private LocalDateTime joinedAt;
    private LocalDateTime lastSeen;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
