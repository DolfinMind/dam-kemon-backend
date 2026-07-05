package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the relevance-gate recall fix: a real, in-stock product whose catalog name
 * is a SHORTER form of the query (the shopper / UI pasted the full name with colour +
 * storage) must still survive the gate via its brand/family word — while unrelated
 * single-word overlaps stay rejected (the "formal pants" → baby "Pants" diaper case).
 *
 * <p>isRelevant uses only the {@link QueryExpander}, so the service is constructed with
 * null repos — no Mongo, no Spring context.
 */
class CatalogSearchRelevanceTest {

    private final CatalogSearchService svc =
            new CatalogSearchService(null, null, new QueryExpander(), null, null, null);

    private static Product named(String n) {
        Product p = new Product();
        p.setName(n);
        return p;
    }

    @Test
    void keepsFamilyWhenQueryHasExtraColourAndSpecTokens() {
        // only 4/7 tokens hit "Apple iPhone 17 Pro Max" → was under the 60% gate and dropped.
        assertTrue(svc.isRelevant(named("Apple iPhone 17 Pro Max"),
                List.of("iphone", "17", "pro", "max", "256gb", "natural", "titanium"), Set.of(), null));
    }

    @Test
    void keepsFamilyEvenWhenExactModelNotStocked() {
        // "iphone 17" when only iPhone 16 is carried — show the family, don't return empty.
        assertTrue(svc.isRelevant(named("Apple iPhone 16 128GB"),
                List.of("iphone", "17"), Set.of(), null));
    }

    @Test
    void keepsDetectedBrand() {
        assertTrue(svc.isRelevant(named("Samsung Galaxy S24 Ultra"),
                List.of("galaxy", "s24"), Set.of("samsung"), null));
    }

    @Test
    void rejectsGenericSingleTokenOverlap() {
        // precision preserved: "formal pants" must NOT match a baby "Pants" diaper
        // (distinctive word "formal" misses; only 1/2 generic coverage, under the gate).
        assertFalse(svc.isRelevant(named("Baby Pants Diaper XL"),
                List.of("formal", "pants"), Set.of(), null));
    }

    @Test
    void distinctiveTokenIgnoresColour() {
        // the family word drives the keep, not the longest colour token.
        assertEquals("iphone",
                CatalogSearchService.distinctiveToken(List.of("iphone", "17", "natural", "titanium")));
    }
}
