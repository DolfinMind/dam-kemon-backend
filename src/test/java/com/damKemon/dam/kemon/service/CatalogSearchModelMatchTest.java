package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the real "iphone 17" failure from prod: the query returned iPhone 13/15/16,
 * accessory bundles, and even an "Apple" pickle. Names below are the ACTUAL product names
 * from that response. The model-number constraint must drop every product that doesn't
 * carry the queried model, and the widened accessory filter must catch bundle/sticker/lens.
 */
class CatalogSearchModelMatchTest {

    private static Product named(String n) {
        Product p = new Product();
        p.setName(n);
        return p;
    }

    @Test
    void modelTokensPickModelNumbersOnly() {
        assertEquals(Set.of("17"), CatalogSearchService.queryModelTokens(List.of("iphone", "17")));
        // storage / network / colour are NOT model identifiers
        assertEquals(Set.of("17"),
                CatalogSearchService.queryModelTokens(List.of("iphone", "17", "256gb", "5g", "natural", "titanium")));
        assertEquals(Set.of("s24"), CatalogSearchService.queryModelTokens(List.of("galaxy", "s24", "ultra")));
        // no digit token → no constraint (family/category browse still works)
        assertTrue(CatalogSearchService.queryModelTokens(List.of("macbook", "air")).isEmpty());
    }

    @Test
    void iphone17DropsWrongModelsAndOffBrandJunk() {
        Set<String> m = CatalogSearchService.queryModelTokens(List.of("iphone", "17"));
        assertFalse(CatalogSearchService.nameHasAnyModel("Apple iPhone 13 - Price in Bangladesh", m));
        assertFalse(CatalogSearchService.nameHasAnyModel("Used iPhone 14 Plus (Apple Replacement Unit) Price In Bangladesh", m));
        assertFalse(CatalogSearchService.nameHasAnyModel("Apple iPhone 16 Pro Max | With Apple International Warranty Claim Support", m));
        assertFalse(CatalogSearchService.nameHasAnyModel("Chalta (Elephant Apple) Pickle 215g", m));
        assertFalse(CatalogSearchService.nameHasAnyModel("Anker 25W USB C Supports PPS Fast Charging Samsung and iPhone", m));
    }

    @Test
    void keepsTheQueriedModelAndNoRegressionOnColourSpecQuery() {
        Set<String> m17full = CatalogSearchService.queryModelTokens(
                List.of("iphone", "17", "pro", "max", "256gb", "natural", "titanium"));
        assertTrue(CatalogSearchService.nameHasAnyModel("Apple iPhone 17 Pro Max", m17full));

        Set<String> m16 = CatalogSearchService.queryModelTokens(List.of("iphone", "16"));
        assertTrue(CatalogSearchService.nameHasAnyModel("iPhone 16 Price in Bangladesh", m16));
        assertFalse(CatalogSearchService.nameHasAnyModel("Apple iPhone 13 - Price in Bangladesh", m16));
        // "16" must not leak into "16e" (a different model)
        assertFalse(CatalogSearchService.nameHasAnyModel("iPhone 16e | 256 GB", m16));
    }

    @Test
    void widenedAccessoryFilterCatchesBundleStickerLens() {
        assertTrue(CatalogSearchService.isAccessoryProduct(named("Spigen 3 in 1 Bundle Pack for iPhone 17 Series")));
        assertTrue(CatalogSearchService.isAccessoryProduct(
                named("Apple iPhone 15 Pro MAX / iPhone 15 / iPhone 15 PRO Hydrogel Clear Back Poly Sticker")));
        assertTrue(CatalogSearchService.isAccessoryProduct(
                named("GlastR EZ fit Optik pro Camera Lens for iPhone 16 Pro Max")));
        assertTrue(CatalogSearchService.isAccessoryProduct(named("Green Lion 4 In 1 Defender Pack for iPhone 16")));
        // a real phone is NOT an accessory
        assertFalse(CatalogSearchService.isAccessoryProduct(named("iPhone 16 Price in Bangladesh")));
    }
}
