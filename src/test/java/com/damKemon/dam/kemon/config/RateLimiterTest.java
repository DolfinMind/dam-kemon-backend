package com.damKemon.dam.kemon.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void capacityAndRefillMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(10, 0));
    }

    @Test
    void allowsBurstUpToCapacityThenBlocks() {
        RateLimiter rl = new RateLimiter(3, 0.0001); // effectively no refill
        assertTrue(rl.tryConsume("1.2.3.4"));
        assertTrue(rl.tryConsume("1.2.3.4"));
        assertTrue(rl.tryConsume("1.2.3.4"));
        assertFalse(rl.tryConsume("1.2.3.4"));
    }

    @Test
    void perKeyBucketsAreIndependent() {
        RateLimiter rl = new RateLimiter(1, 0.0001);
        assertTrue(rl.tryConsume("a"));
        assertTrue(rl.tryConsume("b"));
        assertFalse(rl.tryConsume("a"));
        assertFalse(rl.tryConsume("b"));
    }

    @Test
    void refillsOverTime() throws InterruptedException {
        RateLimiter rl = new RateLimiter(1, 50.0); // refill 50/sec → ~20ms each
        assertTrue(rl.tryConsume("k"));
        assertFalse(rl.tryConsume("k"));
        Thread.sleep(60);
        assertTrue(rl.tryConsume("k"));
    }
}
