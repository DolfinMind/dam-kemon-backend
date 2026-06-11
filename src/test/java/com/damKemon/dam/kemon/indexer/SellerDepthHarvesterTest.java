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
    void remergeNoiseCollapsesButVariantsStayDistinct() {
        // The real fragmentation cases found in the live catalog — these MUST
        // now be recognised as the same product so the re-merge stacks sellers.
        assertTrue(BulkIndexer.sameProduct(
                "AMD Ryzen 9 9900X3D Gaming Processor",
                "AMD Ryzen 9 9900X3D 12 Core 24 Thread AM5 Gaming Processor"));
        assertTrue(BulkIndexer.sameProduct(
                "MacBook Air M2 13-inch 2022",
                "Apple Macbook Air M2 13.6 inch"));
        assertTrue(BulkIndexer.sameProduct(
                "Apple iPhone 15 Pro Max",
                "Apple iPhone 15 Pro Max | With Apple International Warranty Claim Support"));
        // …but true variants stay separate:
        assertFalse(BulkIndexer.sameProduct(
                "MacBook Air M2 13-inch 2022", "MacBook Air M2 15-inch 2023")); // size
        assertFalse(BulkIndexer.sameProduct(
                "AMD Ryzen 9 9900X Gaming Processor", "AMD Ryzen 9 9900X3D Gaming Processor")); // X vs X3D
        // …and an accessory can never merge into the device:
        assertFalse(BulkIndexer.sameProduct(
                "Apple iPhone 15 Pro Max", "Spigen Ultra Hybrid MagFit Case for iPhone 15 Pro Max"));
    }

    @Test
    void coarseKeyGroupsFragmentsAndSeparatesModels() {
        assertEquals(
                CatalogRemergeService.coarseKey("AMD Ryzen 9 9900X3D Gaming Processor"),
                CatalogRemergeService.coarseKey("AMD Ryzen 9 9900X3D 12 Core 24 Thread AM5 Gaming Processor"));
        assertEquals(
                CatalogRemergeService.coarseKey("MacBook Air M2 13-inch 2022"),
                CatalogRemergeService.coarseKey("Apple Macbook Air M2 13.6 inch"));
        assertFalse(CatalogRemergeService.coarseKey("MacBook Air M2 13-inch 2022")
                .equals(CatalogRemergeService.coarseKey("MacBook Air M2 15-inch 2023")));
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
