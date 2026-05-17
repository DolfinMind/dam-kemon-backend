package com.damKemon.dam.kemon.intelligence;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Builds k-shingles (length-k character n-grams) from a normalized product
 * name. Shingles are the primitive units fed into MinHash for similarity.
 *
 * "iPhone 15 Pro Max"  →  normalize  →  "iphone 15 pro max"
 *                      →  k=4 shingles  →  {"ipho","phon","hone","one ","ne 1", ...}
 *
 * Two products that share many shingles are very likely the same product
 * regardless of token order, punctuation, or minor spelling differences.
 */
public final class Shingler {

    public static final int DEFAULT_K = 4;

    private Shingler() {}

    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /** Set of k-shingles. Bounded by max name length × 1, very fast. */
    public static Set<String> shingles(String text, int k) {
        String s = normalize(text);
        Set<String> out = new HashSet<>();
        if (s.length() < k) {
            if (!s.isEmpty()) out.add(s);
            return out;
        }
        for (int i = 0; i <= s.length() - k; i++) {
            out.add(s.substring(i, i + k));
        }
        return out;
    }

    public static Set<String> shingles(String text) {
        return shingles(text, DEFAULT_K);
    }
}
