package com.damKemon.dam.kemon.intelligence;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Robust BDT price extraction.
 *
 * Real-world prices on BD ecommerce look like:
 *   "৳1,250"  "Tk. 1250"  "TK 12,500.00"  "BDT 1250"  "1250 Taka"
 *   "৳ 1,250 ৳ 1,500"  (current + original)
 *
 * Rules:
 *   - Strip Bengali numerals to ASCII
 *   - Extract the largest reasonable integer number from the text
 *   - Reject decimal-only / fractional / tiny numbers (< 1 BDT for goods)
 *   - Treat the comma as thousands separator (don't keep as decimal)
 */
public final class PriceParser {

    private static final Pattern PRICE_NUMBER = Pattern.compile("(\\d{1,3}(?:[,]\\d{3})+|\\d+)(?:\\.(\\d{1,2}))?");
    private static final char[] BN_DIGITS = {'০','১','২','৩','৪','৫','৬','৭','৮','৯'};

    private PriceParser() {}

    /** Convert Bengali numerals to ASCII digits in-place. */
    public static String toAscii(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int idx = -1;
            for (int j = 0; j < BN_DIGITS.length; j++) {
                if (c == BN_DIGITS[j]) { idx = j; break; }
            }
            sb.append(idx >= 0 ? (char) ('0' + idx) : c);
        }
        return sb.toString();
    }

    /**
     * Parse a price string into a BDT amount, or null if the text is not a real price.
     * Heuristics:
     *   - If multiple numbers, prefer the largest (handles "৳1,250 ৳1,500" by returning 1500
     *     but the caller typically wants the FIRST; see parseCurrentPrice below).
     */
    public static Double parseLargest(String text) {
        if (text == null) return null;
        String s = toAscii(text);
        Matcher m = PRICE_NUMBER.matcher(s);
        double best = -1;
        while (m.find()) {
            Double v = parseMatch(m);
            if (v != null && v > best) best = v;
        }
        return best > 0 ? best : null;
    }

    /** Parse the first plausible price (usually the current/sale price). */
    public static Double parseFirst(String text) {
        if (text == null) return null;
        String s = toAscii(text);
        Matcher m = PRICE_NUMBER.matcher(s);
        while (m.find()) {
            Double v = parseMatch(m);
            // Only accept "real" prices: must be >= 5 BDT, < 100M BDT
            if (v != null && v >= 5 && v < 100_000_000) return v;
        }
        return null;
    }

    private static Double parseMatch(Matcher m) {
        try {
            String intPart = m.group(1).replace(",", "");
            // Reject leading zeros that aren't a single 0 — likely IDs/version codes ("0101", "0.101")
            if (intPart.length() > 1 && intPart.charAt(0) == '0') return null;
            long whole = Long.parseLong(intPart);
            String fracStr = m.group(2);
            if (fracStr == null) return (double) whole;
            // Has decimal — only accept if the integer part itself is >= 10 (rules out "0.101")
            if (whole < 10) return null;
            double frac = Double.parseDouble("0." + fracStr);
            return whole + frac;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
