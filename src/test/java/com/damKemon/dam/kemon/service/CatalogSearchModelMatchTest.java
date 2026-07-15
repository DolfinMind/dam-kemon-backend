package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
        assertTrue(CatalogSearchService.isAccessoryProduct(named("For iPhone 11 12 13 14 Magnetic Attraction Holder")));
        assertTrue(CatalogSearchService.isAccessoryProduct(named("iPhone 14 Pro Dummy Non-Working Display Model")));
        assertTrue(CatalogSearchService.isAccessoryProduct(named("iPhone Magnetic Attraction Bracket")));
        // a real phone is NOT an accessory
        assertFalse(CatalogSearchService.isAccessoryProduct(named("iPhone 16 Price in Bangladesh")));
    }

    @Test
    void newInventoryBeatsRefurbishedUnlessConditionIsRequested() {
        Product fresh = named("Apple iPhone 14 128GB");
        Product refurbished = named("Apple iPhone 14 128GB Certified Refurbished");
        Set<String> expanded = Set.of("iphone", "14");
        Set<String> brands = Set.of("apple");

        assertTrue(
                CatalogSearchService.hybridScore(fresh, List.of("iphone", "14"), expanded, brands, "smartphones")
                        > CatalogSearchService.hybridScore(refurbished, List.of("iphone", "14"), expanded, brands, "smartphones"));

        assertTrue(
                CatalogSearchService.hybridScore(
                        refurbished, List.of("iphone", "14", "refurbished"), expanded, brands, "smartphones")
                        > CatalogSearchService.hybridScore(
                                fresh, List.of("iphone", "14", "refurbished"), expanded, brands, "smartphones"));
    }
}
