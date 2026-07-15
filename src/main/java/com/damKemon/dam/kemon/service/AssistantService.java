package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.intelligence.QueryExpander;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "দরদাম" — a Bangla-aware shopping assistant. Deliberately <b>rules-based and
 * dependency-free</b>: it understands shopping questions (price, budget,
 * compare, "is shop X trustworthy") by reusing the search + trust intelligence
 * we already have, and answers from templates. It costs nothing to run (no LLM
 * API key) and never leaves our servers.
 *
 * <p>It's also <b>provider-agnostic by design</b>: {@link #chat} returns a
 * structured payload, and the intent routing here is the fallback. A future
 * {@code AssistantLanguageModel} (free Gemini/Groq tier, or a self-hosted
 * model) can be slotted in front of this same tool-set without touching the
 * frontend — the rule-based path stays as the zero-cost default + safety net.
 */
@Service
public class AssistantService {

    private final CatalogSearchService search;
    private final TrustService trust;
    private final ShopRepository shopRepository;
    private final QueryExpander expander;

    public AssistantService(CatalogSearchService search, TrustService trust,
                            ShopRepository shopRepository, QueryExpander expander) {
        this.search = search;
        this.trust = trust;
        this.shopRepository = shopRepository;
        this.expander = expander;
    }

    private static final Set<String> GREETING = Set.of(
            "hi", "hello", "hey", "salam", "assalamualaikum", "asalamualaikum",
            "hola", "yo", "help", "start", "ki khobor", "kemon acho", "নমস্কার", "হ্যালো");
    private static final String[] TRUST_WORDS = {
            "trust", "trustworth", "reliable", "safe", "genuine", "authentic", "legit", "scam",
            "review", "rating", "bishosto", "nirbhor", "valo", "bhalo", "kemon", "thik ache", "real"};
    private static final String[] CHEAP_WORDS = {
            "cheap", "cheapest", "lowest", "low price", "budget", "sosta", "shosta", "sasta",
            "kom dame", "kom dam", "komdame", "affordable", "value"};
    private static final String[] BUDGET_SIGNALS = {
            "under", "below", "within", "upto", "up to", "less than", "max", "maximum", "budget",
            "niche", "nicher", "moddhe", "modhe", "kome", "kom", "taka", "tk", "৳", "price",
            "daam", "dam", "cost", "above", "over", "beshi", "range", "between", "theke", "to"};
    private static final String[] MIN_SIGNALS = {"above", "over", "more than", "beshi", "at least", "minimum"};
    private static final String[] FACET_WORDS = {
            "delivery", "koto din", "kobe pabo", "kobe pabe", "shipping", "ship", "warranty", "guarantee",
            "cod", "cash on delivery", "return", "ferot", "exchange", "emi", "kisti", "installment",
            "গ্যারান্টি", "কিস্তি", "ডেলিভারি", "ফেরত"};
    private static final String[] EMI_WORDS = {"emi", "kisti", "installment", "কিস্তি", "monthly payment"};

    /** Compare trigger: "X vs Y", "X versus Y", "X naki Y" (Bangla "or"). */
    private static final Pattern COMPARE = Pattern.compile("(?i)\\s+(?:vs\\.?|versus|naki|na ki)\\s+");

    private static final Pattern NUM = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(k|hazar|hajar|hazaar|hejar|lakh|lac|lacs|lakhs|crore|cr)?",
            Pattern.CASE_INSENSITIVE);

    /** Main entry. Returns { reply, products[], trust{}, suggestions[], intent, detectedCategory }. */
    public Map<String, Object> chat(String message) {
        String raw = message == null ? "" : message.trim();
        if (raw.isEmpty() || isGreeting(normalize(raw))) return greeting();

        String norm = normalize(raw);

        // 1) "X vs Y" — head-to-head comparison.
        if (COMPARE.matcher(norm).find()) {
            Map<String, Object> cmp = compareReply(raw);
            if (cmp != null) return cmp;
        }

        // 2) "Is <shop> trustworthy?" / delivery / warranty / COD / EMI for a shop.
        ShopRef shop = detectShop(norm);
        boolean facet = containsAny(norm, FACET_WORDS);
        if (shop != null && (containsAny(norm, TRUST_WORDS) || facet)) {
            return shopTrustReply(shop, norm);
        }

        // 3) Service questions with no shop named (EMI/COD/delivery in general).
        if (facet && shop == null) {
            return facetNoShopReply();
        }

        // 4) Otherwise it's a product/price/budget query.
        return productReply(raw, norm);
    }

    private Map<String, Object> compareReply(String raw) {
        String[] parts = raw.split("(?i)\\s+(?:vs\\.?|versus|naki|na ki)\\s+", 2);
        if (parts.length != 2 || parts[0].trim().length() < 2 || parts[1].trim().length() < 2) return null;
        Product a = topMatch(parts[0].trim());
        Product b = topMatch(parts[1].trim());
        if (a == null && b == null) return null;

        Map<String, Object> out = base("compare");
        List<Product> products = new ArrayList<>();
        Set<String> slugs = new LinkedHashSet<>();
        if (a != null) { products.add(a); String s = cheapestSlug(a); if (s != null) slugs.add(s); }
        if (b != null) { products.add(b); String s = cheapestSlug(b); if (s != null) slugs.add(s); }
        Map<String, Map<String, Object>> tv = trust.viewForSlugs(slugs);

        StringBuilder sb = new StringBuilder();
        Double pa = a == null ? null : a.getLowestPrice();
        Double pb = b == null ? null : b.getLowestPrice();
        sb.append(side(parts[0].trim(), a)).append(" ").append(side(parts[1].trim(), b)).append(" ");
        if (pa != null && pb != null) {
            if (pa.doubleValue() == pb.doubleValue()) sb.append("Both cost about the same — pick the more trusted seller.");
            else {
                Product cheaper = pa < pb ? a : b;
                sb.append(cheaper.getName().length() > 40 ? cheaper.getName().substring(0, 40) : cheaper.getName())
                  .append(" is cheaper by ").append(fmt(Math.abs(pa - pb))).append(".");
            }
        }
        out.put("reply", sb.toString().trim());
        out.put("products", products);
        out.put("trust", tv);
        out.put("suggestions", List.of("Cheapest of these", "Is the seller trustworthy?", "Show similar options"));
        return out;
    }

    private String side(String label, Product p) {
        if (p == null) return "Couldn't find \"" + label + "\".";
        SitePrice cp = cheapestPrice(p);
        String nm = p.getName().length() > 40 ? p.getName().substring(0, 40) + "…" : p.getName();
        return nm + ": " + (cp != null && cp.getPrice() != null
                ? fmt(cp.getPrice()) + (cp.getSiteName() != null ? " at " + cp.getSiteName() : "")
                : "price N/A") + ".";
    }

    private Product topMatch(String q) {
        try {
            SearchResponse sr = search.search(q);
            List<Product> ps = sr.getProducts();
            if (ps == null || ps.isEmpty()) return null;
            // Prefer a result in the detected category so "iphone 15" returns a
            // phone, not an iPhone case.
            String cat = sr.getDetectedCategory();
            if (cat != null && !cat.isBlank()) {
                for (Product p : ps) if (cat.equalsIgnoreCase(p.getCategory())) return p;
            }
            return ps.get(0);
        } catch (Exception e) { return null; }
    }

    private Map<String, Object> facetNoShopReply() {
        Map<String, Object> out = base("facet");
        out.put("reply", "Tell me which seller and I'll check it — e.g. \"is Daraz delivery fast?\" or \"Star Tech warranty\". "
                + "In general: most BD shops offer Cash on Delivery, and larger electronics retailers (Star Tech, Ryans, Pickaboo) usually offer card EMI.");
        out.put("suggestions", List.of("Is Daraz trustworthy?", "Star Tech delivery time", "Best phone under ৳30,000"));
        return out;
    }

    // ─── product / budget query ───

    private Map<String, Object> productReply(String raw, String norm) {
        Budget budget = parseBudget(norm);
        List<Product> products;
        String detectedCategory = null;
        try {
            SearchResponse sr = search.search(raw);
            products = sr.getProducts() == null ? new ArrayList<>() : new ArrayList<>(sr.getProducts());
            detectedCategory = sr.getDetectedCategory();
        } catch (Exception e) {
            products = new ArrayList<>();
        }

        // Align to the detected category so "best phone" doesn't surface ৳270
        // earbuds (the token "phone" otherwise matches "headphone"). Only apply
        // when it leaves a usable set.
        if (detectedCategory != null && !detectedCategory.isBlank()) {
            List<Product> inCat = new ArrayList<>();
            for (Product p : products) {
                if (detectedCategory.equalsIgnoreCase(p.getCategory())) inCat.add(p);
            }
            if (inCat.size() >= 2) products = inCat;
        }

        if (budget != null) {
            products.removeIf(p -> {
                Double lp = p.getLowestPrice();
                if (lp == null) return true;
                if (budget.max != null && lp > budget.max) return true;
                return budget.min != null && lp < budget.min;
            });
        }
        // Sort by price only when the user explicitly wants the cheapest. For a
        // plain budget ("best phone under ৳30k") keep search relevance order.
        boolean cheapIntent = containsAny(norm, CHEAP_WORDS);
        if (cheapIntent) {
            products.sort(Comparator.comparingDouble(p -> p.getLowestPrice() == null ? Double.MAX_VALUE : p.getLowestPrice()));
        }

        List<Product> top = products.size() > 5 ? new ArrayList<>(products.subList(0, 5)) : products;

        Map<String, Object> out = base("product");
        out.put("detectedCategory", detectedCategory);

        if (top.isEmpty()) {
            out.put("reply", budget != null
                    ? "I couldn't find anything matching that within " + budgetPhrase(budget) + ". Try a broader term or a higher budget."
                    : "I couldn't find anything for that yet. Our catalog crawls 80+ BD shops nightly — try a broader term.");
            out.put("products", List.of());
            out.put("suggestions", List.of("Best phones under ৳30,000", "Cheapest laptops", "Is Daraz trustworthy?"));
            return out;
        }

        // Trust for the cheapest seller of each shown product, so the widget can
        // show a trust pill and the reply can name the smartest buy.
        Set<String> slugs = new LinkedHashSet<>();
        for (Product p : top) { String s = cheapestSlug(p); if (s != null) slugs.add(s); }
        Map<String, Map<String, Object>> tv = trust.viewForSlugs(slugs);

        out.put("reply", buildProductReply(top, budget, cheapIntent, tv));
        out.put("products", top);
        out.put("trust", tv);
        out.put("suggestions", productSuggestions(top, detectedCategory));
        return out;
    }

    private String buildProductReply(List<Product> top, Budget budget, boolean cheapIntent,
                                     Map<String, Map<String, Object>> tv) {
        Product cheapest = top.stream()
                .filter(p -> p.getLowestPrice() != null)
                .min(Comparator.comparingDouble(Product::getLowestPrice))
                .orElse(top.get(0));
        SitePrice cs = cheapestPrice(cheapest);
        StringBuilder sb = new StringBuilder();
        sb.append(top.size() == 1 ? "Here's the best match" : "Here are the top ").append(top.size() == 1 ? "" : "matches");
        if (budget != null) sb.append(" within ").append(budgetPhrase(budget));
        sb.append(". ");
        if (cs != null && cs.getPrice() != null) {
            sb.append("Cheapest is ").append(fmt(cs.getPrice()));
            if (cs.getSiteName() != null) sb.append(" at ").append(cs.getSiteName());
            sb.append(". ");
        }
        // Highlight the strongest available seller score among the shown offers.
        String bestSlug = null; int bestScore = -1; String bestShop = null;
        for (Product p : top) {
            String slug = cheapestSlug(p);
            Map<String, Object> t = slug == null ? null : tv.get(slug);
            int score = t == null ? -1 : asInt(t.get("trustScore"));
            if (score > bestScore) { bestScore = score; bestSlug = slug; bestShop = nameOf(cheapestPrice(p)); }
        }
        if (bestSlug != null && bestScore >= 0) {
            Map<String, Object> t = tv.get(bestSlug);
            String dt = deliveryText(t);
            sb.append("Strongest available seller signals: ").append(bestShop != null ? bestShop : bestSlug)
              .append(" (").append(bestScore).append("/100");
            if (dt != null) sb.append(", ").append(dt);
            sb.append(").");
        }
        if (!cheapIntent && budget == null) sb.append(" Tap a result to compare every seller.");
        return sb.toString().trim();
    }

    private List<String> productSuggestions(List<Product> top, String category) {
        List<String> s = new ArrayList<>();
        String shop = top.isEmpty() ? null : nameOf(cheapestPrice(top.get(0)));
        if (shop != null) s.add("Is " + shop + " trustworthy?");
        if (category != null && !category.isBlank()) s.add("Cheapest " + category.toLowerCase());
        s.add("Show me cheaper options");
        return s.size() > 3 ? s.subList(0, 3) : s;
    }

    // ─── trust-about-shop query ───

    private Map<String, Object> shopTrustReply(ShopRef shop, String norm) {
        Map<String, Map<String, Object>> tv = trust.viewForSlugs(List.of(shop.slug));
        Map<String, Object> t = tv.get(shop.slug);
        Map<String, Object> out = base("trust");
        if (t == null) {
            out.put("reply", "I don't have a trust profile for " + shop.name + " yet.");
            out.put("suggestions", List.of("Best phones under ৳30,000", "Cheapest laptops"));
            return out;
        }
        int score = asInt(t.get("trustScore"));
        String tier = tier(score);
        StringBuilder sb = new StringBuilder();
        // Lead with the specific facet the buyer asked about, if any.
        String lead = facetLead(shop, t, norm);
        if (lead != null) sb.append(lead).append(" ");
        sb.append(shop.name).append(" has a Damkemon seller score of ").append(score).append("/100 — ").append(tier)
          .append(" available signals. This is a comparison aid, not a purchase guarantee. ");
        String auth = authLabel((String) t.get("authenticity"));
        if (auth != null) sb.append(auth).append(". ");
        String dt = deliveryText(t);
        if (dt != null) sb.append("Typical delivery ").append(dt).append(". ");
        if (Boolean.TRUE.equals(t.get("codAvailable"))) sb.append("Cash on delivery available. ");
        Object rc = t.get("ratingCount");
        if (rc instanceof Number n && n.intValue() > 0) sb.append(n.intValue()).append(" buyer review(s). ");
        out.put("reply", sb.toString().trim());
        out.put("trust", tv);
        out.put("shopSlug", shop.slug);
        out.put("suggestions", List.of("Best phones under ৳30,000", "Compare cheapest laptops", "Is Pickaboo trustworthy?"));
        return out;
    }

    // ─── greeting ───

    private Map<String, Object> greeting() {
        Map<String, Object> out = base("greeting");
        out.put("reply", "নমস্কার! I'm দরদাম, your Damkemon shopping assistant. "
                + "Ask me things like \"best phone under ৳30,000\", \"cheapest MacBook\", or \"is Daraz trustworthy?\" "
                + "— I'll compare price, available seller signals and typical delivery for you.");
        out.put("products", List.of());
        out.put("suggestions", List.of("Best phone under ৳30,000", "Cheapest laptop", "Is Daraz trustworthy?"));
        return out;
    }

    private Map<String, Object> base(String intent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intent", intent);
        m.put("products", List.of());
        m.put("trust", Map.of());
        m.put("suggestions", List.of());
        return m;
    }

    // ─── shop detection (cached) ───

    private volatile List<ShopRef> shopRefs = List.of();
    private volatile long shopRefsAt = 0;

    private List<ShopRef> shopRefs() {
        long now = System.currentTimeMillis();
        if (now - shopRefsAt < 600_000 && !shopRefs.isEmpty()) return shopRefs;
        try {
            List<ShopRef> refs = new ArrayList<>();
            for (Shop s : shopRepository.findAll()) {
                if (s.getSlug() == null) continue;
                refs.add(new ShopRef(s.getSlug(), s.getName() == null ? s.getSlug() : s.getName(),
                        shopMatchKey(s.getName(), s.getSlug())));
            }
            // Longest key first so "apple gadgets" wins over "apple".
            refs.sort((a, b) -> Integer.compare(b.key.length(), a.key.length()));
            shopRefs = refs;
            shopRefsAt = now;
        } catch (DataAccessException ignored) { /* keep stale */ }
        return shopRefs;
    }

    private ShopRef detectShop(String norm) {
        for (ShopRef r : shopRefs()) {
            if (norm.contains(r.slug) || (r.key.length() >= 3 && norm.contains(r.key))) return r;
        }
        return null;
    }

    /** A simplified, matchable name: lowercased, alnum+space, brand-y suffixes dropped. */
    private static String shopMatchKey(String name, String slug) {
        String s = (name == null ? slug : name).toLowerCase(Locale.ROOT);
        s = s.replaceAll("[^a-z0-9 ]", " ")
             .replaceAll("\\b(bd|bangladesh|ltd|limited|electronics|online|com)\\b", " ")
             .replaceAll("\\s+", " ").trim();
        return s.isBlank() ? slug : s;
    }

    private record ShopRef(String slug, String name, String key) {}

    // ─── budget parsing ───

    private static final class Budget { Double min; Double max; }

    private Budget parseBudget(String norm) {
        boolean signal = containsAny(norm, BUDGET_SIGNALS);
        boolean minDir = containsAny(norm, MIN_SIGNALS);
        List<Double> vals = new ArrayList<>();
        Matcher m = NUM.matcher(norm);
        while (m.find()) {
            String numStr = m.group(1).replace(",", "");
            String unit = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            double v;
            try { v = Double.parseDouble(numStr); } catch (NumberFormatException e) { continue; }
            double mult = switch (unit) {
                case "k", "hazar", "hajar", "hazaar", "hejar" -> 1000;
                case "lakh", "lac", "lacs", "lakhs" -> 100_000;
                case "crore", "cr" -> 10_000_000;
                default -> 1;
            };
            boolean suffixed = mult != 1;
            double value = v * mult;
            // A bare number only counts as a budget when there's a price signal —
            // otherwise it's a model number (iPhone 15) or a year (2022).
            if (suffixed || (signal && value >= 500)) vals.add(value);
        }
        if (vals.isEmpty()) return null;
        Budget b = new Budget();
        if (vals.size() >= 2) {
            double lo = vals.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            double hi = vals.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            b.min = lo; b.max = hi;
        } else if (minDir) {
            b.min = vals.get(0);
        } else {
            b.max = vals.get(0);
        }
        return b;
    }

    private String budgetPhrase(Budget b) {
        if (b.min != null && b.max != null) return fmt(b.min) + "–" + fmt(b.max);
        if (b.max != null) return "≤ " + fmt(b.max);
        if (b.min != null) return "≥ " + fmt(b.min);
        return "your budget";
    }

    // ─── small helpers ───

    private String normalize(String raw) {
        String n = expander.normalizeBengali(raw);
        n = bengaliDigits(n).toLowerCase(Locale.ROOT);
        return n.replaceAll("\\s+", " ").trim();
    }

    private static String bengaliDigits(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c >= '০' && c <= '৯') sb.append((char) ('0' + (c - '০')));
            else sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isGreeting(String norm) {
        if (GREETING.contains(norm)) return true;
        return norm.length() <= 12 && GREETING.stream().anyMatch(norm::equals);
    }

    private static boolean containsAny(String hay, String[] needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }

    private static String cheapestSlug(Product p) {
        SitePrice sp = cheapestPrice(p);
        return sp == null ? null : (sp.getSiteSlug() != null ? sp.getSiteSlug() : sp.getSiteName());
    }

    private static SitePrice cheapestPrice(Product p) {
        if (p == null || p.getPrices() == null || p.getPrices().isEmpty()) return null;
        return p.getPrices().stream()
                .filter(sp -> sp.getPrice() != null)
                .min(Comparator.comparingDouble(SitePrice::getPrice))
                .orElse(null);
    }

    private static String nameOf(SitePrice sp) { return sp == null ? null : sp.getSiteName(); }

    private static int asInt(Object o) { return o instanceof Number n ? n.intValue() : -1; }

    private static String tier(int s) {
        if (s >= 85) return "very strong";
        if (s >= 72) return "strong";
        if (s >= 60) return "moderate";
        if (s >= 45) return "limited";
        return "weak";
    }

    private static String authLabel(String a) {
        if (a == null) return null;
        return switch (a) {
            case "authorized" -> "Listed as an authorized seller; confirm warranty for the offer";
            case "official_store" -> "Listed brand storefront";
            case "reseller" -> "Independent reseller";
            case "marketplace" -> "Marketplace listing (seller and item vary)";
            default -> null;
        };
    }

    private static String deliveryText(Map<String, Object> t) {
        if (t == null) return null;
        Object avg = t.get("avgReportedDelivery");
        if (avg instanceof Number n) {
            int d = (int) Math.round(n.doubleValue());
            return d <= 0 ? "same day" : "~" + d + (d == 1 ? " day" : " days");
        }
        Integer lo = t.get("deliveryDaysMin") instanceof Number n ? n.intValue() : null;
        Integer hi = t.get("deliveryDaysMax") instanceof Number n ? n.intValue() : null;
        if (lo == null && hi == null) return null;
        if (Objects.equals(lo, hi)) return lo + (lo == 1 ? " day" : " days");
        if (lo != null && lo == 0) return "same–" + hi + " days";
        return lo + "–" + hi + " days";
    }

    private static String facetLead(ShopRef shop, Map<String, Object> t, String norm) {
        if (containsAny(norm, EMI_WORDS)) {
            return "I don't track EMI per seller yet — ask " + shop.name + " directly; larger electronics retailers usually offer card EMI.";
        }
        if (norm.contains("delivery") || norm.contains("koto din") || norm.contains("kobe pab")
                || norm.contains("shipping") || norm.contains("ডেলিভারি")) {
            String dt = deliveryText(t);
            return "Delivery from " + shop.name + " is typically " + (dt != null ? dt : "a few days") + ".";
        }
        if (norm.contains("warranty") || norm.contains("guarantee") || norm.contains("গ্যারান্টি")) {
            Object w = t.get("warranty");
            return "Warranty at " + shop.name + ": " + (w != null ? w : "varies — confirm with the seller") + ".";
        }
        if (norm.contains("cod") || norm.contains("cash on delivery")) {
            return shop.name + (Boolean.TRUE.equals(t.get("codAvailable"))
                    ? " offers cash on delivery." : " may not offer cash on delivery — confirm first.");
        }
        if (norm.contains("return") || norm.contains("ferot") || norm.contains("exchange") || norm.contains("ফেরত")) {
            return "Returns at " + shop.name + ": " + returnText(t) + ".";
        }
        return null;
    }

    private static String returnText(Map<String, Object> t) {
        String ease = (String) t.get("returnEase");
        Object rw = t.get("returnWindowDays");
        int d = rw instanceof Number n ? n.intValue() : 0;
        if ("none".equals(ease) || d == 0) return "no returns accepted";
        String e = "easy".equals(ease) ? "easy" : "limited";
        return d + "-day returns (" + e + ")";
    }

    private static String fmt(double v) {
        return "৳" + String.format(Locale.US, "%,d", Math.round(v));
    }
}
