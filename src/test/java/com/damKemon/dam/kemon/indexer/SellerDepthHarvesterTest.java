package com.damKemon.dam.kemon.indexer;

import org.junit.jupiter.api.Test;

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
