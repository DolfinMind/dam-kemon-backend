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
    void neverMergesPrebuiltPcIntoBareComponent() {
        // #2 corruption pattern: a Gaming/Budget PC built around a CPU must never
        // merge onto the bare processor (a ৳60k pre-built becoming the CPU's price).
        assertFalse(BulkIndexer.sameProduct(
                "AMD Ryzen 5 7500F Processor",
                "AMD Ryzen 5 7500F Gaming PC"));
        assertFalse(BulkIndexer.sameProduct(
                "AMD Ryzen 7 7700X Processor",
                "Budget PC with AMD Ryzen 7 7700X"));
        // distinct matchKey too (the exact-key merge path)
        org.junit.jupiter.api.Assertions.assertNotEquals(
                BulkIndexer.productMatchKey("AMD Ryzen 5 7500F Processor"),
                BulkIndexer.productMatchKey("AMD Ryzen 5 7500F Gaming PC"));
        // "Desktop Processor" is a form-factor, NOT a pre-built → must still merge
        assertTrue(BulkIndexer.sameProduct(
                "AMD Ryzen 5 7500F Processor",
                "AMD Ryzen 5 7500F Desktop Processor"));
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
