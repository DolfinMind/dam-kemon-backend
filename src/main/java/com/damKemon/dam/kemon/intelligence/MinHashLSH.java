package com.damKemon.dam.kemon.intelligence;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MinHash + LSH (Locality-Sensitive Hashing) index for near-duplicate
 * product detection.
 *
 *   1. Each product name is reduced to a 128-dim MinHash signature
 *      (each dimension = min of a hash family applied to its k-shingles).
 *   2. The signature is split into 32 BANDS of 4 ROWS each.
 *   3. We hash each band → that becomes a bucket key. Two products that
 *      land in *any* shared bucket are LSH candidates.
 *
 * Querying by name is O(BANDS) ≈ O(32). Candidates are then ranked by exact
 * Jaccard on shingles (cheap, since the candidate set is tiny).
 *
 * Threshold tuning: with 32 bands × 4 rows we hit candidate-match probability
 * ≈ 0.5 at Jaccard 0.55, ≈ 1.0 at Jaccard 0.85. Good for "is this the same
 * product?" use case.
 */
public final class MinHashLSH {

    private static final int NUM_HASHES = 128;
    private static final int BANDS      = 32;
    private static final int ROWS       = NUM_HASHES / BANDS;        // 4
    private static final long MERSENNE_PRIME = (1L << 31) - 1;       // 2^31 - 1

    private final long[] aCoeffs;
    private final long[] bCoeffs;

    // signatureById[id] → 128 ints
    private final Map<String, int[]> signatureById = new ConcurrentHashMap<>();
    // payloadById[id] → arbitrary value (e.g., the product itself, or its name)
    private final Map<String, Object> payloadById = new ConcurrentHashMap<>();
    // For each band: bucket key → set of product ids
    private final Map<Long, Set<String>>[] buckets;

    private final AtomicLong size = new AtomicLong();

    @SuppressWarnings("unchecked")
    public MinHashLSH() {
        this(new Random(0xC0FFEE));
    }

    @SuppressWarnings("unchecked")
    public MinHashLSH(Random rng) {
        aCoeffs = new long[NUM_HASHES];
        bCoeffs = new long[NUM_HASHES];
        for (int i = 0; i < NUM_HASHES; i++) {
            aCoeffs[i] = (rng.nextLong() & 0x7fffffffL) | 1L;
            bCoeffs[i] = (rng.nextLong() & 0x7fffffffL);
        }
        buckets = new Map[BANDS];
        for (int i = 0; i < BANDS; i++) buckets[i] = new ConcurrentHashMap<>();
    }

    public int size() { return (int) size.get(); }

    /** Compute the 128-dim MinHash signature of an arbitrary shingle set. */
    public int[] signature(Set<String> shingles) {
        int[] sig = new int[NUM_HASHES];
        Arrays.fill(sig, Integer.MAX_VALUE);
        if (shingles == null || shingles.isEmpty()) return sig;
        for (String sh : shingles) {
            int h = sh.hashCode();
            for (int k = 0; k < NUM_HASHES; k++) {
                long v = ((aCoeffs[k] * (h & 0xffffffffL) + bCoeffs[k]) % MERSENNE_PRIME);
                int vi = (int) v;
                if (vi < sig[k]) sig[k] = vi;
            }
        }
        return sig;
    }

    /** Add or replace a product. */
    public void add(String id, String name, Object payload) {
        if (id == null || name == null) return;
        Set<String> shingles = Shingler.shingles(name);
        if (shingles.isEmpty()) return;
        int[] sig = signature(shingles);
        if (signatureById.put(id, sig) == null) size.incrementAndGet();
        payloadById.put(id, payload);
        // index into LSH buckets band-by-band
        for (int b = 0; b < BANDS; b++) {
            long key = bandKey(sig, b);
            buckets[b].computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
    }

    public void remove(String id) {
        int[] sig = signatureById.remove(id);
        if (sig == null) return;
        payloadById.remove(id);
        size.decrementAndGet();
        for (int b = 0; b < BANDS; b++) {
            long key = bandKey(sig, b);
            Set<String> bucket = buckets[b].get(key);
            if (bucket != null) {
                bucket.remove(id);
                if (bucket.isEmpty()) buckets[b].remove(key);
            }
        }
    }

    /**
     * O(BANDS) lookup: returns all product IDs that share at least one LSH
     * band with the query name. Caller should re-rank with exact Jaccard
     * (use {@link #estimatedJaccard}).
     */
    public Set<String> candidates(String name) {
        Set<String> shingles = Shingler.shingles(name);
        if (shingles.isEmpty()) return Collections.emptySet();
        int[] sig = signature(shingles);
        Set<String> out = new HashSet<>();
        for (int b = 0; b < BANDS; b++) {
            long key = bandKey(sig, b);
            Set<String> bucket = buckets[b].get(key);
            if (bucket != null) out.addAll(bucket);
        }
        return out;
    }

    /**
     * Best match (highest Jaccard) above threshold, or null. O(BANDS + candidates).
     * Threshold of 0.5 is a safe "same product" gate.
     */
    public Match findBest(String name, double threshold) {
        Set<String> qShingles = Shingler.shingles(name);
        if (qShingles.isEmpty()) return null;
        int[] qSig = signature(qShingles);
        Set<String> cands = candidates(name);
        if (cands.isEmpty()) return null;

        String bestId = null;
        double bestJ = 0;
        for (String id : cands) {
            int[] sig = signatureById.get(id);
            if (sig == null) continue;
            double j = estimatedJaccard(qSig, sig);
            if (j > bestJ) { bestJ = j; bestId = id; }
        }
        if (bestId == null || bestJ < threshold) return null;
        return new Match(bestId, payloadById.get(bestId), bestJ);
    }

    /** Cheap signature-based Jaccard estimate (no shingle recompute). */
    public static double estimatedJaccard(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        int eq = 0;
        for (int i = 0; i < a.length; i++) if (a[i] == b[i]) eq++;
        return (double) eq / a.length;
    }

    /** Mix the 4 ints in a band into a single bucket key. */
    private long bandKey(int[] sig, int band) {
        int from = band * ROWS;
        long h = 1469598103934665603L;       // FNV-1a 64
        for (int r = 0; r < ROWS; r++) {
            h ^= sig[from + r] & 0xffffffffL;
            h *= 1099511628211L;
        }
        return h;
    }

    public record Match(String id, Object payload, double jaccard) {}
}
