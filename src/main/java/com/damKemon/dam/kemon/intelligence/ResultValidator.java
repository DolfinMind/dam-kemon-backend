package com.damKemon.dam.kemon.intelligence;

import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Strict result filter. A product is KEPT only if:
 *   1. It has a non-blank name and a plausible price for the detected category.
 *   2. Every "significant" query token (length ≥ 3) appears somewhere in the name,
 *      OR a model token (e.g., "s24", "wh-1000xm5") appears in the name.
 *      (This catches related-but-wrong items like a Corsair controller for an iPhone query.)
 *   3. Name does NOT contain an accessory word that wasn't in the query (so a phone
 *      case isn't returned when the user asked for the phone).
 *
 * Each kept result is scored 0..1; we sort descending.
 */
@Service
public class ResultValidator {

    private static final Logger log = LoggerFactory.getLogger(ResultValidator.class);

    private static final Set<String> ACCESSORY_WORDS = Set.of(
            "case", "cover", "skin", "sleeve", "pouch", "tempered", "protector",
            "guard", "holder", "stand", "mount", "tripod", "cable", "adapter",
            "dock", "strap", "screen", "film", "sticker", "ring", "popsocket",
            "carrying", "wallpaper"
    );

    // tokens that are too short / generic to require — articles, brand fillers
    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "and", "or", "for", "with", "of", "in", "on",
            "to", "by", "new", "best", "buy", "shop", "online", "price"
    );

    public static class ScoredResult {
        public final ScrapedProduct product;
        public final double score;
        public ScoredResult(ScrapedProduct p, double s) { product = p; score = s; }
    }

    public List<ScoredResult> validate(List<ScrapedProduct> raw, QueryIntent intent) {
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        ProductCategory primary = intent.primaryCategory();
        Set<String> queryTokens = tokens(intent.getNormalized());
        Set<String> modelTokens = new HashSet<>(intent.getModelTokens());
        Set<String> significantQueryTokens = significant(queryTokens);
        Set<String> allowedAccessoryWords = new HashSet<>();
        for (String tok : queryTokens) if (ACCESSORY_WORDS.contains(tok)) allowedAccessoryWords.add(tok);

        List<ScoredResult> kept = new ArrayList<>();
        int dropped = 0;
        for (ScrapedProduct p : raw) {
            if (p == null || p.getName() == null || p.getName().isBlank()) { dropped++; continue; }
            if (p.getPrice() == null) { dropped++; continue; }
            if (!primary.isPlausiblePrice(p.getPrice())) {
                boolean anyAccepts = intent.getCategories().stream().anyMatch(c -> c.isPlausiblePrice(p.getPrice()));
                if (!anyAccepts) { dropped++; continue; }
            }

            String lname = p.getName().toLowerCase();
            Set<String> nameTokens = tokens(lname);

            // ---- HARD GATE: every significant query token must appear ----
            boolean modelMatch = !modelTokens.isEmpty()
                    && modelTokens.stream().anyMatch(nameTokens::contains);
            if (!modelMatch) {
                boolean allPresent = true;
                for (String qt : significantQueryTokens) {
                    if (!lname.contains(qt)) { allPresent = false; break; }
                }
                if (!allPresent) { dropped++; continue; }
            }

            // ---- Accessory filter ----
            boolean isAccessory = false;
            for (String w : ACCESSORY_WORDS) {
                if (allowedAccessoryWords.contains(w)) continue;
                if (containsWord(lname, w)) { isAccessory = true; break; }
            }
            if (isAccessory) { dropped++; continue; }

            double similarity = similarity(nameTokens, queryTokens, modelTokens, lname);
            double priceScore = priceConfidence(p.getPrice(), primary);
            double finalScore = 0.7 * similarity + 0.2 * priceScore + 0.1 * stockBonus(p);
            kept.add(new ScoredResult(p, finalScore));
        }
        kept.sort((a, b) -> Double.compare(b.score, a.score));
        log.debug("ResultValidator kept {} dropped {} for query={}", kept.size(), dropped, intent.getOriginal());
        return kept;
    }

    private double similarity(Set<String> nameTokens, Set<String> queryTokens, Set<String> modelTokens, String lname) {
        if (nameTokens.isEmpty() || queryTokens.isEmpty()) return 0;
        if (!modelTokens.isEmpty()) {
            for (String m : modelTokens) {
                if (nameTokens.contains(m)) return 1.0;
            }
        }
        Set<String> intersection = new HashSet<>(nameTokens);
        intersection.retainAll(queryTokens);
        Set<String> union = new HashSet<>(nameTokens);
        union.addAll(queryTokens);
        if (union.isEmpty()) return 0;
        double jaccard = (double) intersection.size() / union.size();
        long contained = queryTokens.stream().filter(lname::contains).count();
        double contain = (double) contained / queryTokens.size();
        return Math.min(1.0, 0.55 * jaccard + 0.45 * contain);
    }

    private double priceConfidence(Double price, ProductCategory c) {
        if (price == null) return 0;
        double min = c.getMinPrice(), max = c.getMaxPrice();
        if (price < min || price > max) return 0;
        double mid = Math.sqrt(min * Math.min(max, min * 1000));
        double dist = Math.abs(Math.log(price) - Math.log(mid));
        return Math.max(0.4, 1.0 - dist / 5.0);
    }

    private double stockBonus(ScrapedProduct p) {
        return Boolean.FALSE.equals(p.getInStock()) ? 0 : 1;
    }

    private static Set<String> tokens(String s) {
        if (s == null) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase().split("[^a-z0-9]+")) {
            if (t.length() >= 2) out.add(t);
        }
        return out;
    }

    private static Set<String> significant(Set<String> all) {
        Set<String> out = new HashSet<>();
        for (String t : all) {
            if (t.length() >= 3 && !STOPWORDS.contains(t)) out.add(t);
        }
        return out;
    }

    /** Match whole word, not substring (so "case" doesn't match "casey"). */
    private static boolean containsWord(String haystack, String needle) {
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + needle.length();
            boolean rightOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            idx = end;
        }
        return false;
    }
}
