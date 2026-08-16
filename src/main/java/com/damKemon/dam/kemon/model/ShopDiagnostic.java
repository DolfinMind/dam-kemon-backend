package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One record per learning probe — what the auto-learner saw when it tried
 * to diagnose a 0-product shop. Persisted so operators can answer "why is
 * Walton still empty?" without re-running the probe by hand:
 *
 * <ul>
 *   <li>How many sample URLs the probe fetched.</li>
 *   <li>Which extractors (if any) returned a valid product for each URL.</li>
 *   <li>Whether the pages emit JSON-LD/OG product schema at all (telltale
 *       of "this site doesn't actually have prices" vs "we just need
 *       better selectors").</li>
 *   <li>Whether JS rendering changed anything (auto-detect SPA shops).</li>
 *   <li>Detected platform fingerprint.</li>
 *   <li>Final recommendation: which extractor to lock in via
 *       {@code Shop.preferredExtractor}, or {@code null} if no extractor
 *       worked — the human-actionable signal.</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "shop_diagnostics")
public class ShopDiagnostic {

    @Id
    private String id;

    @Indexed
    private String shopSlug;

    private String detectedPlatform;
    private Boolean hasJsonLd;
    private Boolean hasOgProduct;
    private Boolean jsImprovedExtraction;

    /** Slug of the extractor we now recommend for this shop. Null = none worked. */
    private String recommendedExtractor;

    /** Per-URL probe results — the audit trail. */
    @Builder.Default
    private List<UrlProbe> samples = new ArrayList<>();

    /** Short human summary: "JSON-LD present, generic extractor works" / "no prices on page". */
    private String summary;

    @Indexed
    private Instant ts;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class UrlProbe {
        private String url;
        /** Title of the page we fetched. Empty if fetch failed. */
        private String pageTitle;
        /** Extractor slug → boolean (true = returned a valid product). */
        @Builder.Default
        private java.util.Map<String, Boolean> extractorResults = new java.util.HashMap<>();
        /** Bytes returned. Lets us spot tiny error pages quickly. */
        private Integer htmlBytes;
    }
}
