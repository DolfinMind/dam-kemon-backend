package com.damKemon.dam.kemon.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key token-bucket rate limiter. No external dependency — Bucket4j would
 * be overkill for the only consumer (the search filter).
 *
 * <p>Bucket holds at most {@code capacity} tokens; refills at {@code refillRate}
 * tokens per second. Each {@link #tryConsume(String)} subtracts one token.
 * If the bucket is empty the call returns false and the caller responds 429.
 *
 * <p>Buckets are pruned lazily: a bucket older than 10 minutes since its last
 * touch is dropped on the next miss. We don't run a sweeper — keys are
 * bounded by the IP space hitting us in a 10-min window, which is fine for
 * this scale.
 */
public class RateLimiter {

    private final long capacity;
    private final double refillPerSec;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(long capacity, double refillPerSec) {
        if (capacity <= 0 || refillPerSec <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSec must be positive");
        }
        this.capacity = capacity;
        this.refillPerSec = refillPerSec;
    }

    public boolean tryConsume(String key) {
        if (key == null) key = "_null";
        long now = System.nanoTime();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity, now));

        synchronized (b) {
            long elapsedNs = now - b.lastRefillNanos;
            if (elapsedNs > 0) {
                double newTokens = (elapsedNs / 1_000_000_000.0) * refillPerSec;
                b.tokens = Math.min(capacity, b.tokens + newTokens);
                b.lastRefillNanos = now;
            }
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                b.lastTouched.set(now);
                return true;
            }
            return false;
        }
    }

    /** Drop buckets that haven't been touched in {@code idleMinutes}. */
    public void evictIdle(int idleMinutes) {
        long cutoff = System.nanoTime() - idleMinutes * 60L * 1_000_000_000L;
        buckets.entrySet().removeIf(e -> e.getValue().lastTouched.get() < cutoff);
    }

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;
        final AtomicLong lastTouched;

        Bucket(double initial, long now) {
            this.tokens = initial;
            this.lastRefillNanos = now;
            this.lastTouched = new AtomicLong(now);
        }
    }
}
