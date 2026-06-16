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

@Service
public class ProtectService {

    private static final SecureRandom RND = new SecureRandom();
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

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

    public Map<String, Object> assessRisk(Map<String, Object> body) {
        String query = trimToNull(asStr(body.get("query")));
        return computeRisk(query).toMap();
    }

    private Risk computeRisk(String query) {
        int risk = 55;
        List<Map<String, Object>> flags = new ArrayList<>();
        String level = "medium";
        String sellerName = "Unknown Link";
        String sellerType = "External Website";
        String status = "medium";
        
        if (query == null) {
            return new Risk(0, "low", flags, List.of(), "Please provide a valid shop link or phone number.", null, List.of(), List.of(), List.of(), null, "unknown", null, "Unknown");
        }

        String q = query.toLowerCase(Locale.ROOT).trim();
        String identifier = q;
        boolean isFb = q.contains("facebook.com") || q.contains("fb.com") || q.contains("instagram.com");
        boolean isDaraz = q.contains("daraz.com.bd") || q.contains("daraz");
        boolean isPhone = q.matches("^[0-9+ \\-]+$") && q.length() >= 11;

        if (isDaraz) {
            risk = 12;
            sellerName = "Daraz Marketplace";
            sellerType = "Verified Marketplace";
            status = "low";
            flags.add(flag("verified_platform", "Verified merchant platform", "good"));
            flags.add(flag("escrow_active", "Escrow and return policies detected", "good"));
        } else if (isFb) {
            risk = 88;
            sellerName = "Social Media Seller";
            sellerType = "Social Media Page";
            status = "high";
            flags.add(flag("social_seller", "Social seller (no physical verification)", "high"));
            flags.add(flag("advance_risk", "High risk of advance payment scams on social pages", "high"));
            
            // Extract page name roughly
            String[] parts = q.replace("https://", "").replace("http://", "").replace("www.", "").split("/");
            if (parts.length > 1) {
                sellerName = parts[1].split("\\?")[0];
                identifier = sellerName;
            }
        } else if (isPhone) {
            risk = 95;
            sellerName = q;
            sellerType = "Personal Phone Number";
            status = "high";
            identifier = q.replaceAll("[^0-9]", "");
            flags.add(flag("personal_number", "Personal bKash/Nagad numbers offer ZERO buyer protection", "high"));
        } else {
            risk = 42;
            String[] parts = q.replace("https://", "").replace("http://", "").replace("www.", "").split("/");
            sellerName = parts[0];
            sellerType = "External Website";
            status = "medium";
            flags.add(flag("standard_gateway", "Standard web store detected", "good"));
        }

        // Crowdsourced database check!
        if (identifier != null && !identifier.isBlank()) {
            List<ProtectedOrder> disputes = repo.findBySellerIdentifierAndStatus(identifier, "disputed");
            if (!disputes.isEmpty()) {
                risk = 100;
                status = "high";
                flags.add(flag("known_scam", "CRITICAL: This seller/number was reported for a scam in Damkemon Escrow!", "high"));
            }
        }

        level = status;

        return new Risk(risk, level, flags, List.of(), "Proceed with caution.", null, List.of(), List.of(), List.of(), null, sellerType, identifier, sellerName);
    }

    public Map<String, Object> createOrder(Map<String, Object> body, String anonHeader) {
        String query = trimToNull(asStr(body.get("query")));
        String itemName = clamp(trimToNull(asStr(body.get("itemName"))), 200);
        Double amount = asDouble(body.get("amount"));
        String pay = normalizePay(asStr(body.get("paymentMethod")));
        String anonId = firstNonBlank(anonHeader, asStr(body.get("anonId")));

        if (query == null) {
            return Map.of("status", 400, "error", "Missing query link or number.");
        }
        if (itemName == null) {
            return Map.of("status", 400, "error", "What are you buying?");
        }

        Risk r = computeRisk(query);
        LocalDateTime now = LocalDateTime.now();

        ProtectedOrder order = ProtectedOrder.builder()
                .protectionCode(generateCode())
                .anonId(anonId)
                .sellerName(r.sellerName)
                .sellerIdentifier(r.identifier)
                .itemName(itemName)
                .amount(amount)
                .paymentMethod(pay)
                .sellerType(r.sellerType)
                .riskScore(r.score)
                .riskLevel(r.level)
                .riskFlags(r.flags.stream().map(f -> (String) f.get("code")).toList())
                .status("open")
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
        m.put("severity", severity);
        return m;
    }

    private static String normalizePay(String s) {
        String v = trimToNull(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

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

    private record Risk(int score, String level, List<Map<String, Object>> flags,
                        List<String> checklist, String recommendation,
                        Map<String, Object> sellerTrust, List<Map<String, Object>> saferAlternatives,
                        List<String> categoryTips, List<Map<String, Object>> saferShops,
                        String categoryTag, String sellerType, String identifier, String sellerName) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("riskScore", score);
            m.put("status", level); // mapped to 'status' in frontend
            m.put("finalScore", score);
            m.put("flags", flags.stream().map(f -> Map.of("text", f.get("label"), "bad", "high".equals(f.get("severity")))).toList());
            m.put("name", sellerName);
            m.put("type", sellerType);
            return m;
        }
    }
}
