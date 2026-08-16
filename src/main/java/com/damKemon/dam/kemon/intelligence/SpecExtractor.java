package com.damKemon.dam.kemon.intelligence;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls structured variant specs — RAM, Storage, Display — out of a product NAME,
 * the way BD shops write them: "Galaxy A55 8/256GB", "Redmi Note 13 8GB/256GB",
 * "iPhone 15 128GB", "ASUS VivoBook 16GB/512GB SSD 15.6 inch".
 *
 * <p>Phones + computing first (our category focus). Deliberately conservative:
 * it only emits a value it's confident about (RAM/Storage restricted to the real
 * capacity ladder), so the facets a shopper filters on are trustworthy. Used at
 * SEARCH time over the candidate pool, so it needs no data migration.
 */
public final class SpecExtractor {

    private SpecExtractor() {}

    public static final String RAM = "RAM";
    public static final String STORAGE = "Storage";
    public static final String DISPLAY = "Display";

    /** "8/256GB", "8GB/256GB", "8 GB / 256 GB", "6+128GB" — RAM/Storage combo. */
    private static final Pattern COMBO = Pattern.compile(
            "\\b(\\d{1,2})\\s*(?:gb)?\\s*[/+]\\s*(\\d{2,4})\\s*(gb|tb)\\b", Pattern.CASE_INSENSITIVE);
    /** "8GB RAM" / "RAM 8GB". */
    private static final Pattern RAM_EXPLICIT = Pattern.compile(
            "\\b(\\d{1,2})\\s*gb\\s*ram\\b|\\bram\\s*(\\d{1,2})\\s*gb\\b", Pattern.CASE_INSENSITIVE);
    /** "256GB ROM/SSD/Storage", "1TB SSD". */
    private static final Pattern STORAGE_EXPLICIT = Pattern.compile(
            "\\b(\\d{2,4})\\s*(gb|tb)\\s*(?:rom|storage|ssd|emmc|ufs|hdd)\\b", Pattern.CASE_INSENSITIVE);
    /** Bare capacity with no RAM context: "128GB", "512GB", "1TB". */
    private static final Pattern STORAGE_BARE = Pattern.compile(
            "\\b(\\d{2,4})\\s*(gb|tb)\\b", Pattern.CASE_INSENSITIVE);
    /** Display size: "6.7 inch", "6.7\"", "15.6 inches". */
    private static final Pattern DISPLAY_INCH = Pattern.compile(
            "\\b(\\d{1,2}(?:\\.\\d)?)\\s*(?:inch|inches|\"|”)\\b", Pattern.CASE_INSENSITIVE);

    private static final Set<Integer> RAM_GB = Set.of(2, 3, 4, 6, 8, 12, 16, 18, 24, 32);
    private static final Set<Integer> STORAGE_GB = Set.of(16, 32, 64, 128, 256, 512, 1024, 2048);

    /** Extract {RAM, Storage, Display} from a product name. Missing keys omitted. */
    public static Map<String, String> extract(String name) {
        Map<String, String> out = new LinkedHashMap<>();
        if (name == null || name.isBlank()) return out;
        String s = name.toLowerCase();

        Matcher combo = COMBO.matcher(s);
        if (combo.find()) {
            int ram = parseInt(combo.group(1));
            if (RAM_GB.contains(ram)) out.put(RAM, ram + "GB");
            putStorage(out, parseInt(combo.group(2)), combo.group(3));
        }

        if (!out.containsKey(RAM)) {
            Matcher m = RAM_EXPLICIT.matcher(s);
            if (m.find()) {
                int ram = parseInt(m.group(1) != null ? m.group(1) : m.group(2));
                if (RAM_GB.contains(ram)) out.put(RAM, ram + "GB");
            }
        }

        if (!out.containsKey(STORAGE)) {
            Matcher m = STORAGE_EXPLICIT.matcher(s);
            if (m.find()) putStorage(out, parseInt(m.group(1)), m.group(2));
        }
        if (!out.containsKey(STORAGE)) {
            String ramNum = out.containsKey(RAM) ? out.get(RAM).replace("GB", "") : null;
            Matcher m = STORAGE_BARE.matcher(s);
            while (m.find()) {
                // don't mistake the RAM number for storage
                if (ramNum != null && ramNum.equals(m.group(1)) && "gb".equalsIgnoreCase(m.group(2))) continue;
                if (putStorage(out, parseInt(m.group(1)), m.group(2))) break;
            }
        }

        Matcher d = DISPLAY_INCH.matcher(s);
        if (d.find()) {
            try {
                double v = Double.parseDouble(d.group(1));
                if (v >= 4.0 && v <= 20.0) out.put(DISPLAY, trimDouble(v) + "\"");
            } catch (NumberFormatException ignored) { /* leave display unset */ }
        }
        return out;
    }

    private static boolean putStorage(Map<String, String> out, int value, String unit) {
        if (unit == null || value < 0) return false;
        if (unit.equalsIgnoreCase("tb")) {
            if (value >= 1 && value <= 8) { out.put(STORAGE, value + "TB"); return true; }
            return false;
        }
        if (STORAGE_GB.contains(value)) {
            out.put(STORAGE, value == 1024 ? "1TB" : value == 2048 ? "2TB" : value + "GB");
            return true;
        }
        return false;
    }

    /** Sort key so "8GB" < "12GB" and "256GB" < "1TB" order naturally in facets. */
    public static double numericOf(String specValue) {
        if (specValue == null) return Double.MAX_VALUE;
        try {
            double n = Double.parseDouble(specValue.replaceAll("[^0-9.]", ""));
            if (specValue.toUpperCase().contains("TB")) n *= 1024;
            return n;
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private static String trimDouble(double v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.valueOf(v);
    }
}
