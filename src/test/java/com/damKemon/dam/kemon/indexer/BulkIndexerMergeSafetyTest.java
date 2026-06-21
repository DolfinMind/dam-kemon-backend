package com.damKemon.dam.kemon.indexer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the false-positive guards on {@link BulkIndexer#sameProduct} — the gate
 * the nightly re-merge uses before stacking two rows' sellers onto one product.
 * Over-splitting is harmless (a duplicate listing); over-MERGING corrupts the
 * headline price (a ৳4k case becoming the "lowest seller" of a ৳92k phone), so
 * these cases must never merge even as the catalog grows toward 100k.
 */
class BulkIndexerMergeSafetyTest {

    @Test
    void neverMergesAccessoryIntoDevice() {
        assertFalse(BulkIndexer.sameProduct(
                "Apple iPhone 15 Pro Max 256GB",
                "Spigen Tough Armor Case for iPhone 15 Pro Max"));
        assertFalse(BulkIndexer.sameProduct(
                "Samsung Galaxy S24 Ultra",
                "Tempered Glass Screen Protector for Galaxy S24 Ultra"));
    }

    @Test
    void neverMergesOfficialWithGreyLane() {
        // ~2× price apart in the BD market — not the same purchasable item
        assertFalse(BulkIndexer.sameProduct(
                "Xiaomi Redmi Note 13 Official",
                "Xiaomi Redmi Note 13 Unofficial"));
    }

    @Test
    void neverMergesDifferentModelsOrConsoleWithGame() {
        assertFalse(BulkIndexer.sameProduct("Apple iPhone 15", "Apple iPhone 16"));
        // a PS5 CONSOLE must not merge with a PS5 GAME (the "21" discriminator differs)
        assertFalse(BulkIndexer.sameProduct(
                "Sony PS5 Gaming Console with Wireless Controller",
                "FIFA 21 Standard Edition EA Sports PS5 Game"));
    }

    @Test
    void stillMergesTrueDuplicatesAcrossShops() {
        // storage/colour/year/size-format noise stripped → same model → merge (sellers stack)
        assertTrue(BulkIndexer.sameProduct(
                "MacBook Air M2 13.6 inch (8/256GB) Midnight",
                "MacBook Air M2 13-inch 2022 256GB"));
        assertTrue(BulkIndexer.sameProduct(
                "Apple iPhone 15 128GB Black",
                "Apple iPhone 15 256GB Blue"));
    }
}
