package com.damKemon.dam.kemon.intelligence;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.*;

/**
 * Maps free-text user queries to a richer set of equivalent tokens so the
 * downstream regex/text/trigram passes can hit product names that don't
 * use the *exact* word the user typed.
 *
 * Three classes of expansion:
 *   1. Brand ↔ flagship line  (apple ↔ iphone, samsung ↔ galaxy, xiaomi ↔ redmi/mi)
 *   2. Bengali script & romanized Bangla   (ফোন → phone, mobail → mobile)
 *   3. Common misspellings the support inbox sees    (ipone, samsng, walt)
 *
 * The expander is intentionally a static table — small, fast, no model
 * dependency. The cost is updating it when a new brand line gains traction;
 * the benefit is search that doesn't silently fail on the most common
 * user-typed phrases in Bangladesh's market.
 */
@Component
public final class QueryExpander {

    private static final Map<String, List<String>> SYNONYMS = new HashMap<>();
    private static final Map<String, String> BENGALI_TO_LATIN = new HashMap<>();

    static {
        bidir("apple", "iphone");
        bidir("apple", "ipad");
        bidir("apple", "macbook");
        bidir("samsung", "galaxy");
        bidir("xiaomi", "redmi");
        bidir("xiaomi", "mi");
        bidir("xiaomi", "poco");
        bidir("oneplus", "1+");
        bidir("oneplus", "op");
        bidir("realme", "narzo");
        bidir("oppo", "reno");
        bidir("oppo", "find");
        bidir("vivo", "iqoo");
        bidir("vivo", "y");
        bidir("huawei", "honor");
        bidir("huawei", "nova");
        bidir("google", "pixel");
        bidir("nokia", "hmd");
        bidir("infinix", "hot");
        bidir("tecno", "spark");
        bidir("tecno", "camon");
        bidir("itel", "vision");

        bidir("laptop", "notebook");
        bidir("laptop", "ultrabook");
        bidir("computer", "pc");
        bidir("computer", "desktop");

        bidir("ac", "aircon");
        bidir("ac", "air conditioner");
        bidir("ac", "air-conditioner");
        bidir("fridge", "refrigerator");
        bidir("tv", "television");
        bidir("headphone", "headphones");
        bidir("headphone", "earphone");
        bidir("earbud", "tws");
        bidir("airpod", "airpods");

        bidir("walton", "walt");
        bidir("walton", "wal");
        bidir("singer", "singar");

        misspell("iphone", "ipone", "ifone", "iphne", "ihpone", "iphoen");
        misspell("samsung", "samsng", "samsong", "samusng", "samusng");
        misspell("xiaomi", "xiomi", "siaomi", "shaomi", "shyaomi");
        misspell("redmi", "redmy", "redme", "remdi");
        misspell("realme", "relme", "realmi");
        misspell("oneplus", "1plus", "1+");
        misspell("huawei", "huwawi", "hauwei", "huwei");
        misspell("macbook", "macbok", "mackbook");
        misspell("airpods", "airpod", "airpodd");

        bengaliMap("ফোন", "phone");
        bengaliMap("মোবাইল", "mobile");
        bengaliMap("ল্যাপটপ", "laptop");
        bengaliMap("টিভি", "tv");
        bengaliMap("এসি", "ac");
        bengaliMap("ফ্রিজ", "fridge");
        bengaliMap("ঘড়ি", "watch");
        bengaliMap("ক্যামেরা", "camera");
        bengaliMap("চার্জার", "charger");
        bengaliMap("পাওয়ার ব্যাংক", "power bank");
        bengaliMap("হেডফোন", "headphone");
        bengaliMap("কাপড়", "clothes");
        bengaliMap("জুতা", "shoes");
        bengaliMap("বই", "book");
        bengaliMap("ওয়ালটন", "walton");
        bengaliMap("স্যামসাং", "samsung");
        bengaliMap("আইফোন", "iphone");

        // ── more category + accessory synonyms ──
        bidir("powerbank", "power bank");
        bidir("smartwatch", "smart watch");
        bidir("monitor", "display");
        bidir("charger", "adapter");
        bidir("pendrive", "pen drive");
        bidir("pendrive", "usb drive");
        bidir("ssd", "solid state");
        bidir("router", "wifi router");
        bidir("cctv", "security camera");
        bidir("cctv", "ip camera");
        bidir("tablet", "tab");
        bidir("speaker", "soundbar");
        bidir("trimmer", "shaver");
        bidir("motorola", "moto");
        bidir("nothing", "cmf");

        // ── more misspellings the inbox sees ──
        misspell("mobile", "moblie", "mobil", "mobail");
        misspell("laptop", "labtop", "leptop", "laptp");
        misspell("charger", "charer", "charjer", "chargar");
        misspell("bluetooth", "blutooth", "bluetuth", "blututh");
        misspell("headphone", "hedphone", "headfone", "hedfone");
        misspell("speaker", "spekar", "speker");
        misspell("powerbank", "powerbnk", "powrbank");
        misspell("router", "rauter", "router wifi");
        misspell("camera", "camra", "kamera");

        // ── more Bengali → Latin ──
        bengaliMap("রাউটার", "router");
        bengaliMap("প্রিন্টার", "printer");
        bengaliMap("সিসিটিভি", "cctv");
        bengaliMap("মনিটর", "monitor");
        bengaliMap("পেনড্রাইভ", "pendrive");
        bengaliMap("মেমোরি কার্ড", "memory card");
        bengaliMap("স্পিকার", "speaker");
        bengaliMap("স্মার্টওয়াচ", "smartwatch");
        bengaliMap("কভার", "cover");
        bengaliMap("কেস", "case");
        bengaliMap("ব্যাগ", "bag");
        bengaliMap("চশমা", "sunglass");
        bengaliMap("জামা", "shirt");
        bengaliMap("প্যান্ট", "pant");
        bengaliMap("পাঞ্জাবি", "panjabi");
        bengaliMap("শাড়ি", "saree");
        bengaliMap("ওষুধ", "medicine");
        bengaliMap("খেলনা", "toy");
        bengaliMap("কলম", "pen");
        bengaliMap("খাতা", "exercise book");
        bengaliMap("ফ্যান", "fan");
        bengaliMap("ওভেন", "oven");
    }

    private static void bidir(String a, String b) {
        SYNONYMS.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
        SYNONYMS.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
    }

    private static void misspell(String correct, String... wrongs) {
        for (String w : wrongs) {
            SYNONYMS.computeIfAbsent(w, k -> new ArrayList<>()).add(correct);
        }
    }

    private static void bengaliMap(String bn, String en) {
        BENGALI_TO_LATIN.put(bn, en);
    }

    public Set<String> expandTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>(tokens);
        for (String t : tokens) {
            List<String> syns = SYNONYMS.get(t);
            if (syns != null) out.addAll(syns);
        }
        return out;
    }

    /**
     * Replace Bengali-script tokens with their Latin equivalent and lowercase.
     * Returns the query unchanged when no transliteration applies.
     */
    public String normalizeBengali(String query) {
        if (query == null || query.isBlank()) return query;
        String out = query;
        for (Map.Entry<String, String> e : BENGALI_TO_LATIN.entrySet()) {
            if (out.contains(e.getKey())) out = out.replace(e.getKey(), e.getValue());
        }
        return Normalizer.normalize(out, Normalizer.Form.NFC);
    }
}
