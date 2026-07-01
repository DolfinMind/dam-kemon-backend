package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the widened accessory lexicon: prod returned "Spigen 3 in 1 Bundle Pack for
 * iPhone 17 Series" ranked ABOVE real phones because bundle/sticker/lens weren't
 * recognised as accessory words. (The model-number hard filter that shipped alongside
 * this was reverted — it zeroed out legitimate fuzzy matches once recall moved to
 * trigram; see CatalogSearchServiceRecallTest for the graceful-recall behaviour that
 * replaces it.)
 */
class CatalogSearchModelMatchTest {

    private static Product named(String n) {
        Product p = new Product();
        p.setName(n);
        return p;
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
