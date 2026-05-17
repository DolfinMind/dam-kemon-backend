package com.damKemon.dam.kemon.intelligence;

import java.util.*;

/**
 * Aho-Corasick multi-pattern string matcher.
 *
 * Builds a single trie + failure links from N patterns and matches them all
 * against a haystack in O(|haystack| + total matches) — a single pass, no
 * matter how many patterns are in the dictionary.
 *
 * Replaces the QueryClassifier's previous O(categories × keywords) scan:
 * we register every category-keyword pair as a (pattern, payload) and
 * scan each query exactly once.
 *
 * Word-boundary aware: a pattern only "matches" if it's surrounded by
 * non-alphanumeric chars (so "iphone" doesn't fire on "iphones-case").
 */
public final class AhoCorasick<T> {

    private static final class Node<T> {
        Map<Character, Node<T>> next = new HashMap<>();
        Node<T> fail;
        List<Output<T>> outputs;
    }

    public record Output<T>(String pattern, T payload) {}
    public record Hit<T>(int start, int end, String pattern, T payload) {}

    private final Node<T> root = new Node<>();
    private final boolean caseInsensitive;
    private boolean built = false;

    public AhoCorasick() { this(true); }
    public AhoCorasick(boolean caseInsensitive) { this.caseInsensitive = caseInsensitive; }

    /** Add a pattern with an associated payload. Call before build(). */
    public void add(String pattern, T payload) {
        if (pattern == null || pattern.isEmpty()) return;
        String p = caseInsensitive ? pattern.toLowerCase(Locale.ROOT) : pattern;
        Node<T> cur = root;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            cur = cur.next.computeIfAbsent(c, k -> new Node<>());
        }
        if (cur.outputs == null) cur.outputs = new ArrayList<>(1);
        cur.outputs.add(new Output<>(p, payload));
        built = false;
    }

    /** Build failure links. Call once after all patterns are added. */
    public void build() {
        Deque<Node<T>> q = new ArrayDeque<>();
        for (Node<T> child : root.next.values()) {
            child.fail = root;
            q.add(child);
        }
        while (!q.isEmpty()) {
            Node<T> u = q.poll();
            for (Map.Entry<Character, Node<T>> e : u.next.entrySet()) {
                char c = e.getKey();
                Node<T> v = e.getValue();
                Node<T> f = u.fail;
                while (f != null && !f.next.containsKey(c)) f = f.fail;
                v.fail = (f == null) ? root : f.next.get(c);
                if (v.fail.outputs != null) {
                    if (v.outputs == null) v.outputs = new ArrayList<>(v.fail.outputs.size());
                    v.outputs.addAll(v.fail.outputs);
                }
                q.add(v);
            }
        }
        built = true;
    }

    /** Find every pattern occurrence in text. Word-boundary checked. */
    public List<Hit<T>> findAll(String text) {
        if (!built) build();
        if (text == null || text.isEmpty()) return Collections.emptyList();
        String t = caseInsensitive ? text.toLowerCase(Locale.ROOT) : text;
        List<Hit<T>> hits = new ArrayList<>();
        Node<T> cur = root;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            while (cur != root && !cur.next.containsKey(c)) cur = cur.fail;
            Node<T> nxt = cur.next.get(c);
            if (nxt != null) cur = nxt;
            if (cur.outputs != null) {
                for (Output<T> o : cur.outputs) {
                    int end = i + 1;
                    int start = end - o.pattern.length();
                    if (isWordBounded(t, start, end)) {
                        hits.add(new Hit<>(start, end, o.pattern, o.payload));
                    }
                }
            }
        }
        return hits;
    }

    private static boolean isWordBounded(String t, int s, int e) {
        boolean leftOk  = s == 0 || !isWordChar(t.charAt(s - 1));
        boolean rightOk = e == t.length() || !isWordChar(t.charAt(e));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }
}
