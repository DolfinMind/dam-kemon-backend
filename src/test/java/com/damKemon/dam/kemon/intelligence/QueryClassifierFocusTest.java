package com.damKemon.dam.kemon.intelligence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards the category-focus gate against audio-brand leakage. "Oraimo" is a
 * HEADPHONE keyword, so a bare "...Stick Vacuum" used to tie on score and get
 * shelved under "Headphones & Audio" (an in-scope bucket) — leaking an appliance
 * into a computing+mobile-only catalog. The out-of-scope object override must
 * classify it as APPLIANCE (out of scope → dropped) without over-blocking the
 * brand's real audio products.
 */
class QueryClassifierFocusTest {

    private QueryClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new QueryClassifier();
        classifier.buildAutomata();   // @PostConstruct, called manually in unit test
    }

    @Test
    void audioBrandVacuumIsOutOfScope() {
        ProductCategory cat = classifier
                .classify("Oraimo CordForce Flex Corded 2-in-1 Stick Vacuum")
                .primaryCategory();
        assertEquals(ProductCategory.APPLIANCE, cat,
                "an Oraimo vacuum must classify as an (out-of-scope) appliance, not Headphones & Audio");
    }

    @Test
    void sameBrandAudioStaysInScope() {
        ProductCategory cat = classifier
                .classify("Oraimo FreePods 4 True Wireless Earbuds")
                .primaryCategory();
        assertEquals(ProductCategory.HEADPHONE, cat,
                "the override must not over-block the brand's real audio products");
    }

    // ── The three live-rail misfires of 2026-07-06 ──────────────────────────

    @Test
    void cctvCameraWithAudioInNameIsSecurityNotHeadphones() {
        ProductCategory cat = classifier
                .classify("Hikvision DS-2CE76D0T-EXLPF 2MP ColorVu Audio Fixed Turret Camera")
                .primaryCategory();
        assertEquals(ProductCategory.SECURITY, cat,
                "a Hikvision cam with 'Audio' in the name was headlining the Headphones rail");
    }

    @Test
    void droneToyIsNotASmartphone() {
        ProductCategory cat = classifier
                .classify("GALAXY Drone Toy for Kids")
                .primaryCategory();
        assertEquals(ProductCategory.TOYS, cat,
                "'GALAXY' is a line word, not evidence of a phone");
    }

    @Test
    void mobileRouterIsNetworkingNotASmartphone() {
        assertEquals(ProductCategory.NETWORKING, classifier
                        .classify("Teltonika RUT240 Mobile 4G LTE Router").primaryCategory(),
                "'Mobile … Router' must file under networking");
        assertEquals(ProductCategory.NETWORKING, classifier
                        .classify("IEASUN MF825 4G LTE Advanced Mobile WiFi Pocket Router").primaryCategory());
    }

    @Test
    void plainQueriesStillClassifyAsPhones() {
        // The context-word demotion must not break the two most common searches.
        assertEquals(ProductCategory.SMARTPHONE,
                classifier.classify("mobile").primaryCategory());
        assertEquals(ProductCategory.SMARTPHONE,
                classifier.classify("samsung galaxy s24 ultra").primaryCategory());
    }
}
