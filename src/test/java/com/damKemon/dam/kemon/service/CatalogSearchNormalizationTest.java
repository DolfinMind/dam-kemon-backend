package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.intelligence.QueryExpander;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Guards query canonicalisation: "iphone17" and "iphone 17" must tokenise to the
 * same thing (the bug where they returned different result sets), while short
 * model codes the catalog writes glued (ps5/s24/m3) stay intact.
 */
class CatalogSearchNormalizationTest {

    @Test
    void splitsGluedLetterDigitRunsWhenLetterRunIsLongEnough() {
        // long letter run + digits → split, so it matches the spaced catalog name
        assertEquals("iphone 17", CatalogSearchService.splitAlphaNum("iphone17"));
        assertEquals("iphone 17", CatalogSearchService.splitAlphaNum("iphone 17")); // idempotent
        assertEquals("playstation 5", CatalogSearchService.splitAlphaNum("playstation5"));
        assertEquals("windows 11", CatalogSearchService.splitAlphaNum("windows11"));
        assertEquals("iphone 17 pro", CatalogSearchService.splitAlphaNum("iphone17pro"));
    }

    @Test
    void keepsShortModelCodesGlued() {
        // catalog writes these without a space — splitting to a 1–2 char stub loses the match
        assertEquals("ps5", CatalogSearchService.splitAlphaNum("ps5"));
        assertEquals("s24", CatalogSearchService.splitAlphaNum("s24"));
        assertEquals("m3", CatalogSearchService.splitAlphaNum("m3"));
        assertEquals("5g", CatalogSearchService.splitAlphaNum("5g"));
        assertEquals("256gb", CatalogSearchService.splitAlphaNum("256gb"));
        assertEquals("galaxy s24", CatalogSearchService.splitAlphaNum("galaxy s24"));
    }

    @Test
    void collapsesSpacedConsolePhrases() {
        QueryExpander ex = new QueryExpander();
        assertEquals("playstation 5", ex.collapsePhrases("play station 5"));
        assertEquals("playstation", ex.collapsePhrases("PLAY STATION"));
        assertEquals("xbox", ex.collapsePhrases("x box"));
        assertEquals("docking station", ex.collapsePhrases("docking station")); // unrelated "station" untouched
    }
}
