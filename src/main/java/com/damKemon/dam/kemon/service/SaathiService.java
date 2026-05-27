package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SaathiAccount;
import com.damKemon.dam.kemon.model.SaathiProduct;
import com.damKemon.dam.kemon.model.SaathiQuery;
import com.damKemon.dam.kemon.model.Seller;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.SaathiAccountRepository;
import com.damKemon.dam.kemon.repository.SaathiProductRepository;
import com.damKemon.dam.kemon.repository.SaathiQueryRepository;
import com.damKemon.dam.kemon.repository.SellerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Business logic for Damkemon Saathi — onboarding, slug generation,
 * verification, product attach/detach, and the live-assist matching that
 * powers both the FB-Live sidebar and the Messenger bot.
 *
 * <p>Two non-obvious responsibilities:
 * <ul>
 *   <li>Slug uniqueness: derived from the FB page name, dedupe-suffixed
 *       on collision so two sellers can both be called "Gadget Lounge".</li>
 *   <li>Verified ⇄ Seller directory sync: when an account flips to
 *       {@code verified}, we upsert a {@link Seller} row so the shop
 *       appears in the public directory automatically. Suspension reverses it.</li>
 * </ul>
 */
@Service
public class SaathiService {

    private static final Logger log = LoggerFactory.getLogger(SaathiService.class);
    private static final Pattern SLUG_SAFE = Pattern.compile("[^a-z0-9]+");

    /** Per-tier daily quota for live-assist + Messenger replies. */
    private static final Map<String, Integer> DAILY_QUOTA = Map.of(
            "trial", 50,
            "lite",  50,
            "pro",   100_000,
            "enterprise", 1_000_000
    );

    private final SaathiAccountRepository accounts;
    private final SaathiProductRepository products;
    private final SaathiQueryRepository queries;
    private final ProductRepository catalog;
    private final SellerRepository sellers;
    private final CatalogSearchService search;

    public SaathiService(SaathiAccountRepository accounts,
                         SaathiProductRepository products,
                         SaathiQueryRepository queries,
                         ProductRepository catalog,
                         SellerRepository sellers,
                         CatalogSearchService search) {
        this.accounts = accounts;
        this.products = products;
        this.queries = queries;
        this.catalog = catalog;
        this.sellers = sellers;
        this.search = search;
    }

    /**
     * Onboarding entry point. Idempotent — re-signing-in returns the
     * existing account. Retries on slug collisions because two users
     * signing up "Gadget Lounge" at the same instant race past the
     * uniqueSlug() pre-check and would otherwise hit a Mongo unique
     * index violation.
     */
    public SaathiAccount signup(String userId, Map<String, Object> body) {
        if (userId == null) throw new IllegalArgumentException("user required");
        Optional<SaathiAccount> existing = accounts.findByUserId(userId);
        if (existing.isPresent()) return existing.get();

        String displayName = str(body.get("displayName"), "My Shop");
        String fbUrl = str(body.get("facebookUrl"), null);

        DuplicateKeyException lastErr = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            String slug = uniqueSlug(displayName);
            SaathiAccount acc = SaathiAccount.builder()
                    .userId(userId)
                    .slug(slug)
                    .displayName(displayName)
                    .facebookUrl(fbUrl)
                    .messengerUrl(str(body.get("messengerUrl"), null))
                    .whatsapp(str(body.get("whatsapp"), null))
                    .city(str(body.get("city"), null))
                    .area(str(body.get("area"), null))
                    .verificationStatus("pending")
                    .trialUntil(LocalDateTime.now().plusDays(14))
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            try {
                return accounts.save(acc);
            } catch (DuplicateKeyException e) {
                // userId-unique collision means someone else signed up the same user
                // in between our findByUserId and save. Re-read and return.
                Optional<SaathiAccount> raced = accounts.findByUserId(userId);
                if (raced.isPresent()) return raced.get();
                lastErr = e;
                // else it was a slug collision — loop and try a new slug
            }
        }
        throw lastErr == null
                ? new IllegalStateException("could not allocate slug")
                : lastErr;
    }

    public Optional<SaathiAccount> findByUser(String userId) {
        return accounts.findByUserId(userId);
    }

    public Optional<SaathiAccount> findBySlug(String slug) {
        return accounts.findBySlug(slug);
    }

    public SaathiAccount update(SaathiAccount acc, Map<String, Object> patch) {
        if (patch.containsKey("displayName")) acc.setDisplayName(str(patch.get("displayName"), acc.getDisplayName()));
        if (patch.containsKey("facebookUrl")) acc.setFacebookUrl(str(patch.get("facebookUrl"), acc.getFacebookUrl()));
        if (patch.containsKey("messengerUrl")) acc.setMessengerUrl(str(patch.get("messengerUrl"), acc.getMessengerUrl()));
        if (patch.containsKey("whatsapp")) acc.setWhatsapp(str(patch.get("whatsapp"), acc.getWhatsapp()));
        if (patch.containsKey("city")) acc.setCity(str(patch.get("city"), acc.getCity()));
        if (patch.containsKey("area")) acc.setArea(str(patch.get("area"), acc.getArea()));
        if (patch.containsKey("pickupAddress")) acc.setPickupAddress(str(patch.get("pickupAddress"), acc.getPickupAddress()));
        if (patch.containsKey("categories") && patch.get("categories") instanceof List<?> list) {
            List<String> cats = new ArrayList<>();
            for (Object o : list) if (o != null) cats.add(o.toString());
            acc.setCategories(cats);
        }
        acc.setUpdatedAt(LocalDateTime.now());
        return accounts.save(acc);
    }

    public SaathiAccount submitVerification(SaathiAccount acc, String nid, String tradeLicense) {
        if (nid != null && !nid.isBlank()) {
            // Never persist raw NID. BCrypt is fine here — slow on purpose.
            acc.setNidHash(BCrypt.hashpw(nid.trim(), BCrypt.gensalt(8)));
        }
        if (tradeLicense != null && !tradeLicense.isBlank()) {
            acc.setTradeLicense(tradeLicense.trim());
        }
        acc.setVerificationStatus("pending");
        acc.setVerificationNote(null);
        acc.setUpdatedAt(LocalDateTime.now());
        return accounts.save(acc);
    }

    /**
     * Admin action: approve, reject, or suspend. On {@code verified}, upserts
     * a {@link Seller} directory row. On {@code suspended}, drops it.
     */
    public SaathiAccount setVerificationStatus(SaathiAccount acc, String status, String note) {
        if (!Set.of("pending", "verified", "rejected", "suspended").contains(status)) {
            throw new IllegalArgumentException("invalid status");
        }
        acc.setVerificationStatus(status);
        acc.setVerificationNote(note);
        LocalDateTime now = LocalDateTime.now();
        if ("verified".equals(status)) {
            acc.setVerifiedAt(now);
            acc.setSuspendedAt(null);
            upsertSellerRow(acc);
        } else if ("suspended".equals(status)) {
            acc.setSuspendedAt(now);
            removeSellerRow(acc);
        } else if ("rejected".equals(status)) {
            removeSellerRow(acc);
        }
        acc.setUpdatedAt(now);
        return accounts.save(acc);
    }

    public SaathiProduct attachProduct(SaathiAccount acc, String productId, Double listedPrice, String note) {
        if (acc == null || productId == null) throw new IllegalArgumentException("missing args");
        SaathiProduct existing = products.findBySaathiIdAndProductId(acc.getId(), productId).orElse(null);
        if (existing != null) {
            existing.setListedPrice(listedPrice);
            existing.setNote(note);
            existing.setInStock(true);
            existing.setUpdatedAt(LocalDateTime.now());
            return products.save(existing);
        }
        return products.save(SaathiProduct.builder()
                .saathiId(acc.getId())
                .productId(productId)
                .listedPrice(listedPrice)
                .note(note)
                .inStock(true)
                .addedAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());
    }

    public List<SaathiProduct> listProducts(SaathiAccount acc) {
        return products.findBySaathiId(acc.getId());
    }

    public void detachProduct(SaathiAccount acc, String productId) {
        products.deleteBySaathiIdAndProductId(acc.getId(), productId);
    }

    /**
     * The core feature. Returns the data a seller's live-stream sidebar (or
     * Messenger bot) needs to answer "what's the price of X?" with both
     * their own listed price and market context.
     */
    public LiveAssistResult liveAssist(SaathiAccount acc, String query, String source) {
        LiveAssistResult result = new LiveAssistResult();
        result.setQuery(query);

        SearchResponse resp = search.search(query);
        List<Product> raw = resp.getProducts();
        final List<Product> hits = (raw == null) ? List.of() : raw;

        // Best catalog match — first organic, skipping any sponsored slot we
        // injected for consumers (sellers want the real competitor picture).
        Product best = hits.stream()
                .filter(p -> p.getId() != null && !Boolean.TRUE.equals(p.getSponsored()))
                .findFirst()
                .orElseGet(() -> hits.isEmpty() ? null : hits.get(0));
        result.setMatch(best);

        // Their own listing for this catalog id, if any.
        if (best != null && acc != null) {
            products.findBySaathiIdAndProductId(acc.getId(), best.getId())
                    .ifPresent(result::setYourListing);
        }

        // Log for analytics/billing.
        try {
            SaathiQuery row = SaathiQuery.builder()
                    .saathiId(acc == null ? null : acc.getId())
                    .source(source)
                    .rawQuery(query)
                    .normalizedQuery(query == null ? null : query.toLowerCase().trim())
                    .matchedProductId(best == null ? null : best.getId())
                    .replyPreview(best == null ? "no_match" : best.getName())
                    .ts(Instant.now())
                    .build();
            queries.save(row);
            if (acc != null) {
                acc.setTotalQueries((acc.getTotalQueries() == null ? 0L : acc.getTotalQueries()) + 1);
                accounts.save(acc);
            }
        } catch (Exception e) {
            log.debug("saathi query log failed: {}", e.getMessage());
        }

        return result;
    }

    /** Recent queries for the seller's analytics tab. */
    public List<SaathiQuery> recentQueries(SaathiAccount acc, int limit) {
        return queries.findBySaathiIdOrderByTsDesc(acc.getId(),
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, Math.min(limit, 100))));
    }

    /** True if the account can use paid features right now (paid OR within trial). */
    public boolean isEntitled(SaathiAccount acc) {
        if (acc == null) return false;
        LocalDateTime now = LocalDateTime.now();
        if (acc.getPaidUntil() != null && acc.getPaidUntil().isAfter(now)) return true;
        if (acc.getTrialUntil() != null && acc.getTrialUntil().isAfter(now)) return true;
        return false;
    }

    /** Effective tier name for quota lookup. Trial counts as the lite tier. */
    public String effectiveTier(SaathiAccount acc) {
        if (acc == null) return "trial";
        String t = acc.getTier();
        if (t != null && DAILY_QUOTA.containsKey(t)) return t;
        if (acc.getPaidUntil() != null && acc.getPaidUntil().isAfter(LocalDateTime.now())) return "lite";
        return "trial";
    }

    /**
     * Returns null when the seller is within their daily quota. Otherwise
     * returns a structured "quota_exceeded" map suitable for a 429 body.
     */
    public Map<String, Object> checkQuota(SaathiAccount acc) {
        String tier = effectiveTier(acc);
        int quota = DAILY_QUOTA.getOrDefault(tier, 50);
        long usedToday = queries.countBySaathiIdAndTsAfter(
                acc.getId(), Instant.now().minus(24, ChronoUnit.HOURS));
        if (usedToday >= quota) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "quota_exceeded");
            err.put("tier", tier);
            err.put("used", usedToday);
            err.put("limit", quota);
            err.put("upgradeTo", "pro");
            return err;
        }
        return null;
    }

    /**
     * Aggregate stats for the dashboard header — query volume, match rate,
     * top-searched product. Cheap: two count queries + one bounded scan.
     */
    public Map<String, Object> dashboardStats(SaathiAccount acc) {
        Map<String, Object> out = new LinkedHashMap<>();
        Instant now = Instant.now();
        out.put("queries24h", queries.countBySaathiIdAndTsAfter(acc.getId(), now.minus(24, ChronoUnit.HOURS)));
        out.put("queries7d",  queries.countBySaathiIdAndTsAfter(acc.getId(), now.minus(7,  ChronoUnit.DAYS)));
        out.put("queries30d", queries.countBySaathiIdAndTsAfter(acc.getId(), now.minus(30, ChronoUnit.DAYS)));

        List<SaathiQuery> recent = queries.findBySaathiIdOrderByTsDesc(acc.getId(),
                org.springframework.data.domain.PageRequest.of(0, 200));
        long matched = recent.stream().filter(q -> q.getMatchedProductId() != null).count();
        out.put("matchRate", recent.isEmpty() ? 0.0 : (double) matched / recent.size());

        // Top searched product (by frequency in recent window).
        Map<String, Integer> freq = new HashMap<>();
        for (SaathiQuery q : recent) {
            if (q.getMatchedProductId() != null) {
                freq.merge(q.getMatchedProductId(), 1, Integer::sum);
            }
        }
        String topProductId = freq.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        out.put("topProductId", topProductId);
        out.put("totalProducts", products.countBySaathiId(acc.getId()));
        out.put("tier", effectiveTier(acc));
        out.put("entitled", isEntitled(acc));
        out.put("quotaLimit", DAILY_QUOTA.getOrDefault(effectiveTier(acc), 50));
        return out;
    }

    private void upsertSellerRow(SaathiAccount acc) {
        try {
            Seller s = sellers.findBySlug(acc.getSlug()).orElseGet(() -> Seller.builder()
                    .slug(acc.getSlug())
                    .joinedAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .build());
            s.setName(acc.getDisplayName());
            s.setType("facebook");
            s.setUrl(acc.getFacebookUrl());
            s.setMessengerUrl(acc.getMessengerUrl());
            s.setCity(acc.getCity());
            s.setArea(acc.getArea());
            s.setVerified(true);
            s.setCategories(acc.getCategories());
            s.setSource("saathi");
            s.setUpdatedAt(LocalDateTime.now());
            sellers.save(s);
        } catch (Exception e) {
            log.warn("Seller directory upsert failed for {}: {}", acc.getSlug(), e.getMessage());
        }
    }

    private void removeSellerRow(SaathiAccount acc) {
        try {
            sellers.findBySlug(acc.getSlug()).ifPresent(sellers::delete);
        } catch (Exception e) {
            log.debug("Seller directory remove failed (ignored): {}", e.getMessage());
        }
    }

    private String uniqueSlug(String displayName) {
        String base = SLUG_SAFE.matcher(displayName == null ? "shop" : displayName.toLowerCase())
                .replaceAll("-").replaceAll("^-+|-+$", "");
        if (base.isBlank()) base = "shop";
        if (base.length() > 40) base = base.substring(0, 40);
        String slug = base;
        int i = 2;
        while (accounts.existsBySlug(slug)) {
            slug = base + "-" + i++;
            if (i > 999) {
                slug = base + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
                break;
            }
        }
        return slug;
    }

    private static String str(Object v, String fallback) {
        if (v == null) return fallback;
        String s = v.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    /** Compact result type returned by {@link #liveAssist}. */
    @lombok.Data
    public static class LiveAssistResult {
        private String query;
        private Product match;
        private SaathiProduct yourListing;
    }
}
