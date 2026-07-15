package com.damKemon.dam.kemon.indexer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopDiscoveryServiceTest {

    @Test
    void keepsCommerceHostsAndRejectsPublisherNoise() {
        assertTrue(ShopDiscoveryService.isCandidateHost("realshop.com.bd"));
        assertTrue(ShopDiscoveryService.isCandidateHost("localgadget.com"));
        assertFalse(ShopDiscoveryService.isCandidateHost("prnewswire.com"));
        assertFalse(ShopDiscoveryService.isCandidateHost("example.org"));
        assertFalse(ShopDiscoveryService.isCandidateHost("facebook.com"));
    }
}
