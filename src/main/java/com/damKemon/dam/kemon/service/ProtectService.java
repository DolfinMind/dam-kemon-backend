package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.ProtectedOrder;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ProtectedOrderRepository;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * "Damkemon Protect" v2 — an honest seller check + scam registry.
 *
 * <p>The old version scored every input 0–100 from keyword matches ("facebook"
 * → 88) and dressed it up as an escrow scan. This version only ever states
 * what we actually know:
 *
 * <ul>
 *   <li><b>known_shop</b> — the URL resolves to a shop in our catalog: return
 *       its real, site-wide {@code ShopTrust} view (the same score shown on
 *       search results), plus any scam reports against it.</li>
 *   <li><b>reported</b> — the identifier has scam reports in our registry:
 *       return the count and recent report excerpts.</li>
 *   <li><b>unknown</b> — everything else: no fabricated number, just the
 *       detected seller type so the UI can give payment-method guidance.</li>
 * </ul>
 *
 * <p>Teeth: confirming or disputing a protected order on a known shop feeds
 * {@link TrustService#applyReview}, so outcomes move the shop's public trust
 * score everywhere on the site.
 */
@Service
public class ProtectService {

    private static final SecureRandom RND = new SecureRandom();
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final List<String> REPORT_STATUSES = List.of("disputed", "reported");

    private final ProtectedOrderRepository repo;
    private final TrustService trustService;
    private final ShopRepository shopRepository;

    // ponytail: host→shop lookup cached with a 10-min TTL; ~200 shops so a
    // rebuild is one small findAll. Move to a Spring cache if shops ever 10x.
    private volatile Map<String, String[]> hostIndex = Map.of(); // host → [slug, name]
    private volatile long hostIndexAt = 0;

    public ProtectService(ProtectedOrderRepository repo, TrustService trustService,
                          ShopRepository shopRepository) {
        this.repo = repo;
        this.trustService = trustService;
        this.shopRepository = shopRepository;
    }

    // ─────────────────────── identifier canonicalization ───────────────────────

    /**
     * The primitive every lookup keys on. Collapses the many ways to write the
     * same seller into one stable string:
     * phones → {@code phone:01712345678} (880-prefix normalised),
     * Facebook/Instagram pages → {@code fb:pagehandle} / {@code ig:handle},
     * anything else URL-ish → {@code host:example.com.bd}.
     * Returns null when the input can't identify a seller at all.
     */
    static String canonicalIdentifier(String raw) {
        if (raw == null) return null;
        String q = raw.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return null;

        String digits = q.replaceAll("[^0-9]", "");
        if (q.matches("^[0-9+\\-() ]+$") && digits.length() >= 10) {
            if (digits.startsWith("880")) digits = "0" + digits.substring(3);
            return "phone:" + digits;
        }

        String noScheme = q.replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        int cut = indexOfAny(noScheme, '/', '?', '#');
        String host = cut < 0 ? noScheme : noScheme.substring(0, cut);
        String rest = cut < 0 ? "" : noScheme.substring(cut);
        if (host.isEmpty() || !host.contains(".")) return null;

        if (host.equals("facebook.com") || host.equals("m.facebook.com")
                || host.equals("web.facebook.com") || host.equals("fb.com")) {
            String handle = pageHandle(rest);
            return handle == null ? null : "fb:" + handle;
        }
        if (host.equals("instagram.com") || host.equals("m.instagram.com")) {
            String handle = pageHandle(rest);
            return handle == null ? null : "ig:" + handle;
        }
        return "host:" + host;
    }

    /** First path segment as the page handle; {@code profile.php?id=N} → the id. */
    private static String pageHandle(String pathAndQuery) {
        if (pathAndQuery == null || pathAndQuery.isEmpty()) return null;
        String path = pathAndQuery.startsWith("/") ? pathAndQuery.substring(1) : pathAndQuery;
        String query = "";
        int qm = path.indexOf('?');
        if (qm >= 0) { query = path.substring(qm + 1); path = path.substring(0, qm); }
        String seg = path.split("/")[0].trim();
        if (seg.equals("profile.php") || seg.equals("people")) {
            for (String p : query.split("&")) {
                if (p.startsWith("id=")) return p.substring(3).replaceAll("[^0-9]", "");
            }
            return null;
        }
        return seg.isEmpty() ? null : seg;
    }

    private static int indexOfAny(String s, char... chars) {
        int best = -1;
        for (char c : chars) {
            int i = s.indexOf(c);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static String sellerTypeOf(String id) {
        if (id.startsWith("phone:")) return "phone_number";
        if (id.startsWith("fb:")) return "facebook_page";
        if (id.startsWith("ig:")) return "instagram_page";
        return "website";
    }

    private static String displayNameOf(String id) {
        return id.substring(id.indexOf(':') + 1);
    }

    // ─────────────────────── seller resolution ───────────────────────

    /** Exact-host match against the catalog's shops ({@code Shop.baseUrl}). */
    private String[] shopForHost(String host) {
        long now = System.currentTimeMillis();
        if (now - hostIndexAt > 600_000) {
            try {
                Map<String, String[]> idx = new HashMap<>();
                for (Shop s : shopRepository.findAll()) {
                    String h = hostOf(s.getBaseUrl());
                    if (h != null) idx.put(h, new String[]{s.getSlug(), s.getName()});
                }
                hostIndex = idx;
                hostIndexAt = now;
            } catch (DataAccessException e) {
                // keep serving the stale index
            }
        }
        return hostIndex.get(host);
    }

    private static String hostOf(String url) {
        String id = canonicalIdentifier(url);
        return id != null && id.startsWith("host:") ? id.substring(5) : null;
    }

    // ─────────────────────── public API ───────────────────────

    public Map<String, Object> assessRisk(Map<String, Object> body) {
        String query = trimToNull(asStr(body.get("query")));
        String id = canonicalIdentifier(query);

        Map<String, Object> out = new LinkedHashMap<>();
        if (id == null) {
            out.put("kind", "invalid");
            return out;
        }

        String[] shop = id.startsWith("host:") ? shopForHost(id.substring(5)) : null;
        long reportCount = 0;
        List<ProtectedOrder> recent = List.of();
        try {
            reportCount = repo.countBySellerIdentifierAndStatusIn(id, REPORT_STATUSES);
            if (reportCount > 0) {
                recent = repo.findTop3BySellerIdentifierAndStatusInOrderByCreatedAtDesc(id, REPORT_STATUSES);
            }
        } catch (DataAccessException e) {
            // registry unavailable — still answer from the shop match below
        }

        out.put("kind", shop != null ? "known_shop" : (reportCount > 0 ? "reported" : "unknown"));
        out.put("identifier", id);
        out.put("sellerType", shop != null ? "shop" : sellerTypeOf(id));
        out.put("name", shop != null ? shop[1] : displayNameOf(id));
        if (shop != null) {
            out.put("shopSlug", shop[0]);
            out.put("trust", trustService.viewForSlugs(List.of(shop[0])).get(shop[0]));
        }
        Map<String, Object> reports = new LinkedHashMap<>();
        reports.put("count", reportCount);
        reports.put("recent", recent.stream().map(ProtectService::reportExcerpt).toList());
        out.put("reports", reports);
        return out;
    }

    /** Public excerpt of a report — what happened, never who reported it. */
    private static Map<String, Object> reportExcerpt(ProtectedOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", o.getUpdatedAt() != null ? o.getUpdatedAt() : o.getCreatedAt());
        m.put("note", clamp(o.getDisputeReason(), 160));
        m.put("paymentMethod", o.getPaymentMethod());
        m.put("amount", o.getAmount());
        return m;
    }

    /** Register a purchase before it happens → tracking code. */
    public Map<String, Object> createOrder(Map<String, Object> body, String anonHeader) {
        String query = trimToNull(asStr(body.get("query")));
        String itemName = clamp(trimToNull(asStr(body.get("itemName"))), 200);
        Double amount = asDouble(body.get("amount"));
        String pay = normalizePay(asStr(body.get("paymentMethod")));
        String anonId = firstNonBlank(anonHeader, asStr(body.get("anonId")));

        String id = canonicalIdentifier(query);
        if (id == null) return Map.of("status", 400, "error", "Paste the seller's link or phone number.");
        if (itemName == null) return Map.of("status", 400, "error", "What are you buying?");

        Map<String, Object> verdict = assessRisk(Map.of("query", query));
        String[] shop = id.startsWith("host:") ? shopForHost(id.substring(5)) : null;
        Object trustScore = verdict.get("trust") instanceof Map<?, ?> t ? t.get("trustScore") : null;
        LocalDateTime now = LocalDateTime.now();

        ProtectedOrder order = ProtectedOrder.builder()
                .protectionCode(generateCode())
                .anonId(anonId)
                .sellerName(asStr(verdict.get("name")))
                .sellerIdentifier(id)
                .shopSlug(shop != null ? shop[0] : null)
                .itemName(itemName)
                .amount(amount)
                .paymentMethod(pay)
                .sellerType(asStr(verdict.get("sellerType")))
                .riskScore(trustScore instanceof Number n ? n.intValue() : null)
                .riskLevel(asStr(verdict.get("kind")))
                .status("open")
                .deliveryDeadline(now.plusDays(10))
                .timeline(new ArrayList<>(List.of(
                        ProtectedOrder.Event.builder().at(now).type("created")
                                .note("Purchase logged").build())))
                .createdAt(now).updatedAt(now)
                .build();
        try {
            order = repo.save(order);
        } catch (DataAccessException e) {
            return Map.of("status", 500, "error", "Could not log the purchase.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", 201);
        out.put("order", order);
        out.put("verdict", verdict);
        return out;
    }

    /**
     * Standalone scam report — no prior protected order needed; this is how
     * the registry actually grows. One report per browser per seller.
     * ponytail: abuse control is anonId dedup + the global rate limiter; the
     * upgrade path is the review moderation queue (Review.status pattern).
     */
    public Map<String, Object> report(Map<String, Object> body, String anonHeader) {
        String query = trimToNull(asStr(body.get("query")));
        String reason = clamp(trimToNull(asStr(body.get("reason"))), 800);
        String anonId = firstNonBlank(anonHeader, asStr(body.get("anonId")));

        String id = canonicalIdentifier(query);
        if (id == null) return Map.of("status", 400, "error", "Paste the seller's link or phone number.");
        if (reason == null || reason.length() < 10) {
            return Map.of("status", 400, "error", "Tell us briefly what happened (at least 10 characters).");
        }
        try {
            if (anonId != null && repo.existsBySellerIdentifierAndAnonIdAndStatusIn(id, anonId, REPORT_STATUSES)) {
                return Map.of("status", 409, "error", "You already reported this seller.");
            }
        } catch (DataAccessException e) {
            // dedup unavailable — accept the report rather than lose it
        }

        String[] shop = id.startsWith("host:") ? shopForHost(id.substring(5)) : null;
        LocalDateTime now = LocalDateTime.now();
        ProtectedOrder rep = ProtectedOrder.builder()
                .protectionCode(generateCode())
                .anonId(anonId)
                .sellerName(shop != null ? shop[1] : displayNameOf(id))
                .sellerIdentifier(id)
                .shopSlug(shop != null ? shop[0] : null)
                .itemName(clamp(trimToNull(asStr(body.get("itemName"))), 200))
                .amount(asDouble(body.get("amount")))
                .paymentMethod(normalizePay(asStr(body.get("paymentMethod"))))
                .sellerType(shop != null ? "shop" : sellerTypeOf(id))
                .riskLevel(shop != null ? "known_shop" : "unknown")
                .status("reported")
                .disputeReason(reason)
                .timeline(new ArrayList<>(List.of(
                        ProtectedOrder.Event.builder().at(now).type("reported")
                                .note("Scam report filed").build())))
                .createdAt(now).updatedAt(now)
                .build();
        try {
            rep = repo.save(rep);
        } catch (DataAccessException e) {
            return Map.of("status", 500, "error", "Could not file the report.");
        }
        feedTrust(rep.getShopSlug(), false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", 201);
        out.put("reported", true);
        out.put("identifier", id);
        return out;
    }

    public Optional<ProtectedOrder> getByCode(String code) {
        if (code == null || code.isBlank()) return Optional.empty();
        try { return repo.findByProtectionCode(code.trim().toUpperCase(Locale.ROOT)); }
        catch (DataAccessException e) { return Optional.empty(); }
    }

    public ProtectedOrder confirm(String code, String anonId) {
        ProtectedOrder o = transition(code, "confirmed", "Buyer confirmed the item was received", null);
        if (o != null) feedTrust(o.getShopSlug(), true);
        return o;
    }

    public ProtectedOrder dispute(String code, String anonId, String reason) {
        ProtectedOrder o = transition(code, "disputed", "Buyer opened a dispute", clamp(trimToNull(reason), 800));
        if (o != null) feedTrust(o.getShopSlug(), false);
        return o;
    }

    /** Outcomes move the shop's public trust score — the feature's teeth. */
    private void feedTrust(String shopSlug, boolean good) {
        if (shopSlug == null || shopSlug.isBlank()) return;
        trustService.applyReview(shopSlug, null, good ? "up" : "down", good, null, false);
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

    private static String normalizePay(String s) {
        String v = trimToNull(s);
        return v == null ? null : v.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s) { try { return Double.valueOf(s.trim().replace(",", "")); } catch (NumberFormatException e) { return null; } }
        return null;
    }
    private static String asStr(Object o) { return o == null ? null : o.toString(); }
    private static String trimToNull(String s) { if (s == null) return null; String t = s.trim(); return t.isEmpty() ? null : t; }
    private static String firstNonBlank(String a, String b) { String x = trimToNull(a); return x != null ? x : trimToNull(b); }
    private static String clamp(String s, int max) { return s == null ? null : (s.length() <= max ? s : s.substring(0, max)); }
}
