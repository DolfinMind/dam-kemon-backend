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
}
