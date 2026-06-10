package com.damKemon.dam.kemon.indexer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same-model gate is the safety boundary of the fanout: it decides whether a
 * search hit really is the model we asked for before we attach it as a seller.
 * False positives glue accessories / sibling models onto a product, which is the
 * one thing this harvester must never do.
 */
class SellerDepthHarvesterTest {

    @Test
    void acceptsTheSameModelAcrossShopNamingVariance() {
        // Storage / colour / region noise varies per shop but is the same product.
        assertTrue(SellerDepthHarvester.isSameModel(
                "Samsung Galaxy A55 5G", "Samsung Galaxy A55 5G 8/256GB (Awesome Navy)"));
        assertTrue(SellerDepthHarvester.isSameModel(
                "iPhone 15 Pro Max", "Apple iPhone 15 Pro Max 256GB - Official"));
        assertTrue(SellerDepthHarvester.isSameModel(
                "MacBook Air M3 13", "MacBook Air 13 inch M3 (2024)"));
    }

    @Test
    void rejectsAccessoriesForTheQueriedModel() {
        // A phone search routinely surfaces its case/glass first — must not merge.
        assertFalse(SellerDepthHarvester.isSameModel(
                "iPhone 15 Pro Max", "iPhone 15 Pro Max Tempered Glass Screen Protector"));
        assertFalse(SellerDepthHarvester.isSameModel(
                "Samsung Galaxy S24 Ultra", "Samsung Galaxy S24 Ultra Back Cover Case"));
        assertFalse(SellerDepthHarvester.isSameModel(
                "Apple Watch Series 10", "Apple Watch Series 10 Silicone Strap Band"));
    }

    @Test
    void relevanceFilterDropsNavLinksAndKeepsRealResults() {
        // A startech search page lists its mega-menu first; the real results sit
        // deep. Only the slugs carrying the model number should survive.
        List<String> urls = List.of(
                "https://www.startech.com.bd/1stplayer-casing-cooler",   // nav
                "https://www.startech.com.bd/4k-monitor",                 // nav
                "https://www.startech.com.bd/gigabyte-geforce-rtx-4060-eagle-oc-8g-graphics-card",
                "https://www.startech.com.bd/msi-geforce-rtx-4060-gaming-x-8g-graphics-card");
        List<String> ranked = SellerDepthHarvester.rankByRelevance(urls, "RTX 4060 Graphics Card");
        assertEquals(2, ranked.size());
        assertTrue(ranked.stream().allMatch(u -> u.contains("4060")));
    }

    @Test
    void relevanceFilterMatchesPhoneModelTokens() {
        List<String> urls = List.of(
                "https://shop.com/product/phone-case-universal",
                "https://shop.com/product/samsung-galaxy-a55-5g-8-256gb",
                "https://shop.com/product/samsung-galaxy-a15");
        List<String> ranked = SellerDepthHarvester.rankByRelevance(urls, "Samsung Galaxy A55 5G");
        assertFalse(ranked.isEmpty());
        assertTrue(ranked.get(0).contains("a55"));
    }

    @Test
    void rejectsSiblingAndDifferentModels() {
        // Different discriminators = different product, even if very close.
        assertFalse(SellerDepthHarvester.isSameModel(
                "Samsung Galaxy A55 5G", "Samsung Galaxy A35 5G"));
        assertFalse(SellerDepthHarvester.isSameModel(
                "iPhone 15 Pro", "iPhone 15 Pro Max"));
        assertFalse(SellerDepthHarvester.isSameModel(
                "POCO X6 Pro", "POCO M6 Pro"));
    }
}
