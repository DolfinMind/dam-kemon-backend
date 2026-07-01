package com.damKemon.dam.kemon.intelligence;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory trigram inverted index. Each indexed name is split into 3-char
 * shingles ("padded" with spaces so prefixes/suffixes still match). The
 * index is trigram → set of doc IDs.
 *
 * Query time is O(|q-trigrams|) candidates pulled from postings, then we
 * top-K by overlap using a {@link java.util.PriorityQueue} (min-heap, size K).
 *
 * This is what powers typo-tolerant search ("ipone" → "iphone") and
 * autocomplete-style prefix matching.
 */
public final class TrigramIndex {

    private final Map<String, Set<String>> postings = new ConcurrentHashMap<>();
    private final Map<String, Integer> docTrigramCount = new ConcurrentHashMap<>();
    private final Map<String, Object> payload = new ConcurrentHashMap<>();

    public int size() { return docTrigramCount.size(); }

    public void add(String id, String name, Object pay) {
        if (id == null || name == null) return;
        Set<String> tri = trigrams(name);
        if (tri.isEmpty()) return;
        docTrigramCount.put(id, tri.size());
        if (pay != null) payload.put(id, pay);
        for (String t : tri) {
            postings.computeIfAbsent(t, k -> ConcurrentHashMap.newKeySet()).add(id);
        }
    }

    public void remove(String id) {
        Integer cnt = docTrigramCount.remove(id);
        payload.remove(id);
        if (cnt == null) return;
        // Lazy GC: leave dead IDs in postings; size grows linearly with churn.
        // Acceptable for our use-case (no high-churn delete pattern).
    }

    /**
     * Top-K matches by trigram overlap, ranked using a min-heap (so we never
     * sort the whole candidate list).
     *
     * @return list of {id, score, payload} sorted desc by score
     */
    public List<Hit> topK(String query, int k) {
        Set<String> qTri = trigrams(query);
        if (qTri.isEmpty()) return Collections.emptyList();
        // Count overlap per doc
        Map<String, Integer> overlap = new HashMap<>();
        for (String t : qTri) {
            Set<String> ids = postings.get(t);
            if (ids == null) continue;
            for (String id : ids) overlap.merge(id, 1, Integer::sum);
        }
        if (overlap.isEmpty()) return Collections.emptyList();
        // Min-heap of size K, ranked by max(Jaccard, query-coverage). Coverage is the
        // fraction of the QUERY's trigrams present in a name and — unlike Jaccard —
        // does NOT decay with name length. Ranking on the max keeps a correct match on
        // a long BD product name ("Oraimo CordForce … Vacuum" for "oramio cord flex",
        // Jaccard ~0.14 but coverage ~0.56) from being crowded out of the top-K by
        // short names that merely share one common trigram.
        PriorityQueue<Hit> heap = new PriorityQueue<>(k + 1,
                Comparator.comparingDouble(h -> Math.max(h.score, h.coverage)));
        int qSize = qTri.size();
        for (Map.Entry<String, Integer> e : overlap.entrySet()) {
            int inter = e.getValue();
            int docSize = docTrigramCount.getOrDefault(e.getKey(), 1);
            // Jaccard-ish: intersection / (qSize + docSize - intersection)
            double union = qSize + docSize - inter;
            double score = union == 0 ? 0 : inter / union;
            double coverage = qSize == 0 ? 0 : (double) inter / qSize;
            Hit hit = new Hit(e.getKey(), score, coverage, payload.get(e.getKey()));
            double key = Math.max(score, coverage);
            if (heap.size() < k) heap.offer(hit);
            else if (heap.peek() != null && key > Math.max(heap.peek().score, heap.peek().coverage)) {
                heap.poll();
                heap.offer(hit);
            }
        }
        List<Hit> out = new ArrayList<>(heap);
        out.sort((a, b) -> Double.compare(Math.max(b.score, b.coverage), Math.max(a.score, a.coverage)));
        return out;
    }

    /** Best single match above threshold, or null. */
    public Hit bestMatch(String query, double threshold) {
        List<Hit> top = topK(query, 1);
        if (top.isEmpty()) return null;
        Hit h = top.get(0);
        return h.score >= threshold ? h : null;
    }

    public static Set<String> trigrams(String text) {
        String s = Shingler.normalize(text);
        if (s.isBlank()) return Collections.emptySet();
        String padded = " " + s + " ";
        Set<String> out = new HashSet<>();
        for (int i = 0; i <= padded.length() - 3; i++) {
            out.add(padded.substring(i, i + 3));
        }
        return out;
    }

    /**
     * @param score    Jaccard similarity — good for ranking similar-length names,
     *                 but decays as the indexed name grows longer.
     * @param coverage fraction of the QUERY's trigrams found in the name (0..1) —
     *                 length-independent, the reliable "is this name about the typed
     *                 query" signal for typo recall.
     */
    public record Hit(String id, double score, double coverage, Object payload) {}
}
