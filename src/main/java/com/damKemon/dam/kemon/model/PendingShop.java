package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A shop submission awaiting admin review. Created via the public
 * {@code POST /api/shops/submit} endpoint by shop owners; promoted to a
 * real {@link Shop} via {@code POST /api/admin/pending-shops/{id}/approve}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "pending_shops")
public class PendingShop {

    @Id
    private String id;

    private String name;

    @Indexed
    private String baseUrl;

    private String sitemapUrl;

    /** "wordpress" | "shopify" | "magento" | "opencart" | "custom" | null */
    private String platform;

    @Builder.Default
    private List<String> categories = new ArrayList<>();

    /** Free-text "Other notes" from the submitter. */
    private String notes;

    /** Contact email submitted with the form. Stored, never shown publicly. */
    private String contactEmail;

    /** "pending" | "approved" | "rejected" */
    @Builder.Default
    private String status = "pending";

    private String reviewNote;
    private LocalDateTime reviewedAt;

    @Indexed
    private LocalDateTime submittedAt;
}
