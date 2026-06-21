package com.damKemon.dam.kemon.intelligence;

import java.util.Map;
import java.util.Set;

/**
 * Tiny, conservative English plural→singular folder for SEARCH QUERIES.
 *
 * <p>The classifier + keyword dictionary are <b>word-boundary aware</b>
 * ({@link AhoCorasick#findAll}), so a singular keyword like {@code smartwatch}
 * never fires on the plural a shopper types ({@code smartwatches}). That is why
 * "smart watch"/"smartwatch" returned ~300 results but "smart watches"/
 * "smartwatches" collapsed to a handful. Singularizing the query before
 * classification + tokenization collapses all four forms onto the same path.
 *
 * <p>Deliberately rule-based, not a Porter stemmer: we only fold regular plurals
 * and never over-stem. Acronyms/false-plurals (os, gps, asus, plus…) and any
 * token bearing a digit (model numbers like "s24") are left untouched.
 */
public final class Stemmer {

    private Stemmer() {}

    /** Tokens that end in 's' but are NOT plurals — never strip these. */
    private static final Set<String> KEEP = Set.of(
            "os", "ios", "gps", "tws", "dts", "abs", "sms", "cms", "pos", "ups",
            "bias", "lens", "bus", "plus", "asus", "vivos", "news", "iris", "axis");

    /** Irregular / acronym plurals worth handling explicitly (checked first, so
     *  even short ones like "tvs" fold before the length guard). */
    private static final Map<String, String> IRREGULAR = Map.ofEntries(
            Map.entry("accessories", "accessory"),
            Map.entry("batteries", "battery"),
            Map.entry("tvs", "tv"),
            Map.entry("ssds", "ssd"),
            Map.entry("gpus", "gpu"),
            Map.entry("cpus", "cpu"),
            Map.entry("pcs", "pc"),
            Map.entry("mice", "mouse"),
            Map.entry("watches", "watch"),
            Map.entry("glasses", "glass"));

    /** Singularize one token. Returns it unchanged when no safe rule applies
     *  (digit-bearing, on the keep-list, too short, or already singular). */
    public static String singularize(String token) {
        if (token == null || token.isEmpty()) return token;
        String lower = token.toLowerCase();

        String irr = IRREGULAR.get(lower);
        if (irr != null) return irr;

        if (lower.length() < 4) return token;                 // too short to strip safely
        for (int i = 0; i < lower.length(); i++)
            if (Character.isDigit(lower.charAt(i))) return token;   // model number — leave alone
        if (KEEP.contains(lower)) return token;
        if (!lower.endsWith("s")) return token;               // not a plural
        if (lower.endsWith("ss")) return token;               // glass, wireless, class — not a plural

        if (lower.endsWith("ies") && lower.length() > 4)      // batteries → battery
            return token.substring(0, token.length() - 3) + "y";
        if (lower.endsWith("ches") || lower.endsWith("shes") || lower.endsWith("xes"))
            return token.substring(0, token.length() - 2);    // watches → watch, boxes → box
        return token.substring(0, token.length() - 1);        // phones → phone, cases → case
    }

    /** Singularize every whitespace-separated word in a phrase, preserving spacing. */
    public static String singularizePhrase(String phrase) {
        if (phrase == null || phrase.isBlank()) return phrase;
        String[] parts = phrase.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(phrase.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(singularize(parts[i]));
        }
        return sb.toString();
    }
}
