package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.ProtectedOrder;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ProtectedOrderRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "Damkemon Protect" — the buyer-protection layer.
 *
 * <p>v2 makes the verdict versatile: it weighs the seller type (a known shop vs
 * a random Facebook page), the product category (counterfeit-prone vs
 * high-value vs perishable), the payment method (advance "Send Money" is the
 * #1 BD scam vector), and price-vs-market. It also recommends <b>safer trusted
 * shops that sell the same category</b> — so even when you're about to buy off
 * a sketchy page, we can point you somewhere safe. No funds move yet; this is
 * the trust + dispute substrate for escrow. A dispute on a known shop dents its
 * trust score.
 */
@Service
public class ProtectService {

    private static final SecureRandom RND = new SecureRandom();
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O/1/I/L

    private final ProtectedOrderRepository repo;
    private final TrustService trustService;
    private final ProductService productService;
    private final ShopRepository shopRepository;
    private final QueryClassifier classifier;

    public ProtectService(ProtectedOrderRepository repo, TrustService trustService,
                          ProductService productService, ShopRepository shopRepository,
                          QueryClassifier classifier) {
        this.repo = repo;
        this.trustService = trustService;
        this.productService = productService;
        this.shopRepository = shopRepository;
        this.classifier = classifier;
    }

    // ─── risk assessment ───

    public Map<String, Object> assessRisk(Map<String, Object> body) {
        return computeRisk(
                trimToNull(asStr(body.get("productId"))),
                trimToNull(asStr(body.get("shopSlug"))),
                trimToNull(asStr(body.get("sellerName"))),
                trimToNull(asStr(body.get("itemName"))),
                asDouble(body.get("amount")),
                normalizePay(asStr(body.get("paymentMethod"))),
                trimToNull(asStr(body.get("sellerType"))),
                trimToNull(asStr(body.get("category")))).toMap();
    }

    private Risk computeRisk(String productId, String shopSlug, String sellerName, String itemName,
                             Double amount, String pay, String sellerType, String categoryInput) {
        Map<String, Object> trust = null;
        if (shopSlug != null) trust = trustService.viewForSlugs(List.of(shopSlug)).get(shopSlug);
        Product product = productId != null ? productService.findByIdOrSlug(productId).orElse(null) : null;

        boolean known = trust != null;
        String authValue = known ? (String) trust.get("authenticity") : null;
        int risk = known ? Math.max(0, 100 - asInt(trust.get("trustScore"))) : 55;
        List<Map<String, Object>> flags = new ArrayList<>();

        if (!known) {
            flags.add(flag("unknown_seller", "Seller is not verified by Damkemon yet", "high"));
        } else {
            int ts = asInt(trust.get("trustScore"));
            if (ts >= 80) flags.add(flag("trusted_seller", "Verified, high-trust seller (" + ts + "/100)", "good"));
            else if (ts < 50) flags.add(flag("low_trust", "Low trust score (" + ts + "/100) — be careful", "high"));
        }

        // Seller type — a social-media page is riskier than a known shop/website.
        String stype = sellerType != null ? sellerType.toLowerCase(Locale.ROOT)
                : (shopSlug != null ? "known_shop" : "unknown");
        switch (stype) {
            case "fb_page", "facebook", "instagram", "social" -> {
                if (!known) { risk += 10; flags.add(flag("social_seller",
                        "Buying from a social-media seller — verify the page's age, followers and reviews", "warn")); }
            }
            case "marketplace" -> risk += 5;
            default -> { /* known_shop / website / unknown — no extra */ }
        }

        // Payment method — the BD scam vector.
        switch (pay == null ? "" : pay) {
            case "advance_bank", "bkash_personal", "nagad_personal", "advance", "advance_full", "advance_partial", "booking" -> {
                risk += 25;
                flags.add(flag("advance_payment",
                        "Paying in advance to a personal number — the #1 scam pattern in BD", "high"));
            }
            case "cod", "store_pickup" -> {
                risk -= 15;
                flags.add(flag("cod", "cod".equals(pay)
                        ? "Cash on delivery — you pay only after you receive it"
                        : "Store pickup — inspect before you pay", "good"));
            }
            case "bkash_merchant", "nagad_merchant", "card" -> {
                risk -= 5;
                flags.add(flag("traceable_payment", "Traceable merchant payment", "good"));
            }
            default -> { /* unknown method */ }
        }

        // Too-good-to-be-true vs market price.
        if (product != null && product.getLowestPrice() != null && amount != null && amount > 0) {
            double market = product.getLowestPrice();
            if (amount < market * 0.55) {
                risk += 22;
                flags.add(flag("suspicious_price",
                        "Price is far below the market rate (lowest ৳" + grp(market) + ") — common with fakes/scams", "high"));
            } else if (amount < market * 0.8) {
                flags.add(flag("below_market", "Below the usual market price — verify it's genuine", "warn"));
            }
        }

        if (known) {
            if ("marketplace".equals(authValue)) {
                risk += 5;
                flags.add(flag("marketplace", "Marketplace seller — genuineness varies by vendor", "warn"));
            } else if ("authorized".equals(authValue) || "official_store".equals(authValue)) {
                risk -= 5;
            }
            if (trust.get("returnWindowDays") instanceof Number n && n.intValue() == 0) {
                risk += 5;
                flags.add(flag("no_returns", "No returns accepted by this seller", "warn"));
            }
        }

        // Category-aware risk + tips.
        String catTag = categoryInput;
        if (catTag == null && product != null) catTag = product.getCategory();
        if (catTag == null && itemName != null) {
            try { catTag = classifier.classify(itemName).primaryCategory().getLabel(); }
            catch (Exception ignored) { /* leave null */ }
        }
        catTag = catTag == null ? null : catTag.toLowerCase(Locale.ROOT);
        String bucket = categoryBucket(catTag);
        if ("counterfeit".equals(bucket) && (!known || "marketplace".equals(authValue))) {
            risk += 8;
            flags.add(flag("counterfeit_risk", "Counterfeits are common in this category — verify authenticity", "high"));
        }

        risk = Math.max(0, Math.min(100, risk));
        String level = risk < 30 ? "low" : risk < 60 ? "medium" : "high";

        List<String> checklist = new ArrayList<>();
        checklist.add("Prefer Cash on Delivery (COD) whenever it's offered.");
        if (paymentRisky(pay)) {
            checklist.add("Never \"Send Money\" to a personal bKash/Nagad number — use COD or a verified merchant account.");
        }
        checklist.add("Ask the seller for a live unboxing video before they ship.");
        if (!known) checklist.add("Check the Facebook page's age, follower count and genuine reviews.");
        checklist.add("Get the return & warranty policy in writing (screenshot the chat).");
        checklist.add("Keep your Damkemon protection code and confirm delivery here when it arrives.");

        String rec = switch (level) {
            case "low" -> "Looks reasonably safe. Still pay on delivery if you can, and keep proof.";
            case "medium" -> "Proceed with caution: use COD and inspect the product before paying.";
            default -> "High risk. Avoid any advance payment — prefer COD, or pick a more trusted seller below.";
        };

        List<Map<String, Object>> alts = saferAlternatives(product);
        List<Map<String, Object>> saferShops = saferShopsInCategory(catTag, shopSlug);
        return new Risk(risk, level, flags, checklist, rec, trust, alts, categoryTips(bucket), saferShops, catTag, stype);
    }

    private List<Map<String, Object>> saferAlternatives(Product product) {
        if (product == null || product.getPrices() == null || product.getPrices().isEmpty()) return List.of();
        List<String> slugs = product.getPrices().stream()
                .map(sp -> sp.getSiteSlug() != null ? sp.getSiteSlug() : sp.getSiteName())
                .filter(s -> s != null).distinct().toList();
        Map<String, Map<String, Object>> tv = trustService.viewForSlugs(slugs);
        List<Map<String, Object>> out = new ArrayList<>();
        List<SitePrice> sorted = new ArrayList<>(product.getPrices());
        sorted.sort(Comparator.comparingDouble(sp -> sp.getPrice() == null ? Double.MAX_VALUE : sp.getPrice()));
        for (SitePrice sp : sorted) {
            String slug = sp.getSiteSlug() != null ? sp.getSiteSlug() : sp.getSiteName();
            Map<String, Object> t = slug == null ? null : tv.get(slug);
            int score = t == null ? 0 : asInt(t.get("trustScore"));
            if (score >= 72) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("siteName", sp.getSiteName());
                a.put("siteSlug", sp.getSiteSlug());
                a.put("price", sp.getPrice());
                a.put("trustScore", score);
                a.put("productId", product.getId());
                out.add(a);
            }
            if (out.size() >= 3) break;
        }
        return out;
    }

    /** Trusted shops that carry this category — the "buy here instead" list. */
    private List<Map<String, Object>> saferShopsInCategory(String catTag, String excludeSlug) {
        if (catTag == null || catTag.isBlank()) return List.of();
        String norm = catTag.toLowerCase(Locale.ROOT);
        List<Shop> shops;
        try { shops = shopRepository.findAll(); } catch (DataAccessException e) { return List.of(); }
        List<Shop> cands = new ArrayList<>();
        for (Shop s : shops) {
            if (s.getSlug() == null || s.getSlug().equals(excludeSlug)) continue;
            String status = s.getStatus() == null ? "active" : s.getStatus();
            if (!"active".equalsIgnoreCase(status)) continue;
            List<String> tags = s.getCategories() == null ? List.of() : s.getCategories();
            boolean match = false;
            for (String t : tags) {
                if (t == null) continue;
                String tl = t.toLowerCase(Locale.ROOT);
                if (norm.contains(tl) || tl.contains(norm)) { match = true; break; }
            }
            if (match) cands.add(s);
        }
        if (cands.isEmpty()) return List.of();
        Map<String, Map<String, Object>> tv = trustService.viewForSlugs(cands.stream().map(Shop::getSlug).toList());
        cands.sort((x, y) -> Integer.compare(trustScoreOf(tv, y.getSlug()), trustScoreOf(tv, x.getSlug())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Shop s : cands) {
            int sc = trustScoreOf(tv, s.getSlug());
            if (sc < 72) continue; // only genuinely trusted
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shopName", s.getName());
            m.put("shopSlug", s.getSlug());
            m.put("trustScore", sc);
            m.put("baseUrl", s.getBaseUrl());
            out.add(m);
            if (out.size() >= 4) break;
        }
        return out;
    }

    private static int trustScoreOf(Map<String, Map<String, Object>> tv, String slug) {
        Map<String, Object> v = tv.get(slug);
        return v == null ? 0 : asInt(v.get("trustScore"));
    }

    private static String categoryBucket(String tag) {
        if (tag == null) return "general";
        String t = tag.toLowerCase(Locale.ROOT);
        for (String s : new String[]{"beauty", "fashion", "accessor", "watch", "headphone", "audio", "gadget", "eyewear", "jewell", "perfume", "cosmetic"})
            if (t.contains(s)) return "counterfeit";
        for (String s : new String[]{"laptop", "phone", "tablet", "camera", "tv", "air condition", "refriger", "monitor", "desktop", "gaming", "power", "appliance"})
            if (t.contains(s)) return "highvalue";
        for (String s : new String[]{"grocer", "food", "health", "baby", "pet", "medicine"})
            if (t.contains(s)) return "perishable";
        return "general";
    }

    private static List<String> categoryTips(String bucket) {
        return switch (bucket) {
            case "counterfeit" -> List.of(
                    "Counterfeits are common here — ask for the box, serial/IMEI or authenticity card.",
                    "Buy from a brand-authorized seller when you can.");
            case "highvalue" -> List.of(
                    "Insist on the official warranty and a purchase invoice.",
                    "Test the product on delivery (COD) before you pay.");
            case "perishable" -> List.of(
                    "Check the expiry / manufacture date and that the seal is intact.",
                    "Avoid advance payment for perishables.");
            default -> List.of(
                    "Confirm the exact product, price and delivery time in writing before paying.");
        };
    }

    // ─── orders ───

    public Map<String, Object> createOrder(Map<String, Object> body, String anonHeader) {
        String productId = trimToNull(asStr(body.get("productId")));
        String shopSlug = trimToNull(asStr(body.get("shopSlug")));
        String sellerName = trimToNull(asStr(body.get("sellerName")));
        String itemName = clamp(trimToNull(asStr(body.get("itemName"))), 200);
        Double amount = asDouble(body.get("amount"));
        String pay = normalizePay(asStr(body.get("paymentMethod")));
        String sellerType = trimToNull(asStr(body.get("sellerType")));
        String category = trimToNull(asStr(body.get("category")));
        String anonId = firstNonBlank(anonHeader, asStr(body.get("anonId")));

        if (sellerName == null && shopSlug == null) {
            return Map.of("status", 400, "error", "Tell us who you're buying from (sellerName or shopSlug).");
        }
        if (itemName == null && productId == null) {
            return Map.of("status", 400, "error", "What are you buying? (itemName or productId)");
        }

        Risk r = computeRisk(productId, shopSlug, sellerName, itemName, amount, pay, sellerType, category);
        LocalDateTime now = LocalDateTime.now();

        ProtectedOrder order = ProtectedOrder.builder()
                .protectionCode(generateCode())
                .anonId(anonId)
                .productId(productId)
                .shopSlug(shopSlug)
                .sellerName(sellerName)
                .itemName(itemName)
                .amount(amount)
                .paymentMethod(pay)
                .sellerType(r.sellerType)
                .category(r.categoryTag)
                .riskScore(r.score)
                .riskLevel(r.level)
                .riskFlags(r.flags.stream().map(f -> (String) f.get("code")).toList())
                .status("open")
                .buyerNote(clamp(trimToNull(asStr(body.get("buyerNote"))), 500))
                .deliveryDeadline(now.plusDays(10))
                .timeline(new ArrayList<>(List.of(
                        ProtectedOrder.Event.builder().at(now).type("created")
                                .note("Protected order opened").build())))
                .createdAt(now).updatedAt(now)
                .build();
        try {
            order = repo.save(order);
        } catch (DataAccessException e) {
            return Map.of("status", 500, "error", "Could not create the protected order.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", 201);
        out.put("order", order);
        out.put("risk", r.toMap());
        return out;
    }

    public Optional<ProtectedOrder> getByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        try { return repo.findByProtectionCode(code.trim().toUpperCase(Locale.ROOT)); }
        catch (DataAccessException e) { return Optional.empty(); }
    }

    public ProtectedOrder confirm(String code, String anonId) {
        return transition(code, "confirmed", "Buyer confirmed the item was received", null);
    }

    public ProtectedOrder dispute(String code, String anonId, String reason) {
        ProtectedOrder o = transition(code, "disputed", "Buyer opened a dispute", clamp(trimToNull(reason), 800));
        if (o != null && o.getShopSlug() != null) {
            try { trustService.applyReview(o.getShopSlug(), null, "down", Boolean.FALSE, null, false); }
            catch (Exception ignored) { /* best-effort */ }
        }
        return o;
    }

    private ProtectedOrder transition(String code, String status, String note, String disputeReason) {
        Optional<ProtectedOrder> opt = getByCode(code);
        if (opt.isEmpty()) return null;
        ProtectedOrder o = opt.get();
        LocalDateTime now = LocalDateTime.now();
        o.setStatus(status);
        if (disputeReason != null) o.setDisputeReason(disputeReason);
        if (o.getTimeline() == null) o.setTimeline(new ArrayList<>());
        o.getTimeline().add(ProtectedOrder.Event.builder().at(now).type(status).note(note).build());
        o.setUpdatedAt(now);
        try { return repo.save(o); }
        catch (DataAccessException e) { return o; }
    }

    // ─── helpers ───

    private String generateCode() {
        for (int tries = 0; tries < 8; tries++) {
            StringBuilder sb = new StringBuilder("DK-");
            for (int i = 0; i < 6; i++) sb.append(CODE_ALPHABET.charAt(RND.nextInt(CODE_ALPHABET.length())));
            String code = sb.toString();
            try { if (!repo.existsByProtectionCode(code)) return code; }
            catch (DataAccessException e) { return code; }
        }
        return "DK-" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> flag(String code, String label, String severity) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("label", label);
        m.put("severity", severity); // "high" | "warn" | "good"
        return m;
    }

    private static boolean paymentRisky(String pay) {
        return pay != null && (pay.equals("advance_bank") || pay.equals("bkash_personal")
                || pay.equals("nagad_personal") || pay.equals("advance")
                || pay.equals("advance_full") || pay.equals("advance_partial") || pay.equals("booking"));
    }

    private static String normalizePay(String s) {
        String v = trimToNull(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static String grp(double v) { return String.format(Locale.US, "%,d", Math.round(v)); }

    private static int asInt(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) { try { return Double.valueOf(s.trim().replace(",", "")); } catch (NumberFormatException e) { return null; } }
        return null;
    }
    private static String asStr(Object o) { return o == null ? null : o.toString(); }
    private static String trimToNull(String s) { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t; }
    private static String firstNonBlank(String a, String b) { String x = trimToNull(a); return x != null ? x : trimToNull(b); }
    private static String clamp(String s, int max) { return s == null ? null : (s.length() <= max ? s : s.substring(0, max)); }

    /** Internal risk holder + JSON projection. */
    private record Risk(int score, String level, List<Map<String, Object>> flags,
                        List<String> checklist, String recommendation,
                        Map<String, Object> sellerTrust, List<Map<String, Object>> saferAlternatives,
                        List<String> categoryTips, List<Map<String, Object>> saferShops,
                        String categoryTag, String sellerType) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("riskScore", score);
            m.put("riskLevel", level);
            m.put("flags", flags);
            m.put("checklist", checklist);
            m.put("recommendation", recommendation);
            m.put("sellerTrust", sellerTrust);
            m.put("saferAlternatives", saferAlternatives);
            m.put("categoryTips", categoryTips);
            m.put("saferShops", saferShops);
            m.put("category", categoryTag);
            m.put("sellerType", sellerType);
            return m;
        }
    }
}
