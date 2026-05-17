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
        // Min-heap of size K
        PriorityQueue<Hit> heap = new PriorityQueue<>(k + 1, Comparator.comparingDouble(h -> h.score));
        int qSize = qTri.size();
        for (Map.Entry<String, Integer> e : overlap.entrySet()) {
            int inter = e.getValue();
            int docSize = docTrigramCount.getOrDefault(e.getKey(), 1);
            // Jaccard-ish: intersection / (qSize + docSize - intersection)
            double union = qSize + docSize - inter;
            double score = union == 0 ? 0 : inter / union;
            if (heap.size() < k) heap.offer(new Hit(e.getKey(), score, payload.get(e.getKey())));
            else if (heap.peek() != null && score > heap.peek().score) {
                heap.poll();
                heap.offer(new Hit(e.getKey(), score, payload.get(e.getKey())));
            }
        }
        List<Hit> out = new ArrayList<>(heap);
        out.sort((a, b) -> Double.compare(b.score, a.score));
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

    public record Hit(String id, double score, Object payload) {}
}
