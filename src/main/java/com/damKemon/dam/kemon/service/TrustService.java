package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.ShopTrust;
import com.damKemon.dam.kemon.repository.ShopTrustRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes and serves the "beyond price" decision signals: per-shop trust
 * score, delivery window, returns and genuineness. Blends the curated
 * baseline (seeded from {@code shop-trust.json}) with community feedback
 * (star ratings, trust votes, reported delivery days) so a score is useful
 * on day one and sharpens as reviews arrive.
 *
 * @see com.damKemon.dam.kemon.model.ShopTrust
 */
@Service
public class TrustService {

    /** Neutral starting reputation for shops without a curated baseline. */
    private static final int DEFAULT_BASE = 60;

    private final ShopTrustRepository repo;

    public TrustService(ShopTrustRepository repo) {
        this.repo = repo;
    }

    /**
     * Final 0..100 trust score. Starts at the curated baseline, then nudges
     * up or down with community signal. Each signal's influence ramps with
     * volume (full weight at ~20 data points) so a single early review can't
     * swing a score wildly.
     */
    public int computeTrust(ShopTrust t) {
        if (t == null) return DEFAULT_BASE;
        double score = t.getBaseTrust() != null ? t.getBaseTrust() : DEFAULT_BASE;

        int rc = nz(t.getRatingCount());
        if (rc > 0) {
            double avg = nz(t.getRatingSum()) / rc;          // 1..5
            double w = Math.min(rc, 20) / 20.0;              // confidence ramp
            score += (avg - 3.0) / 2.0 * 20.0 * w;           // up to ±20
        }

        int votes = nz(t.getTrustUp()) + nz(t.getTrustDown());
        if (votes > 0) {
            double up = (double) nz(t.getTrustUp()) / votes; // 0..1
            double w = Math.min(votes, 20) / 20.0;
            score += (up - 0.5) * 20.0 * w;                  // up to ±10
        }

        int recs = nz(t.getRecommendYes()) + nz(t.getRecommendNo());
        if (recs > 0) {
            double yes = (double) nz(t.getRecommendYes()) / recs;
            double w = Math.min(recs, 20) / 20.0;
            score += (yes - 0.5) * 10.0 * w;                 // up to ±5
        }

        return (int) Math.round(Math.max(0, Math.min(100, score)));
    }

    /** Average reported delivery days, or null if no one has reported yet. */
    public Double avgReportedDelivery(ShopTrust t) {
        int n = nz(t.getDeliveryReports());
        return n > 0 ? nz(t.getDeliveryDaysSum()) / n : null;
    }

    /** Average community star rating (1..5), or null when there are none. */
    public Double avgRating(ShopTrust t) {
        int n = nz(t.getRatingCount());
        return n > 0 ? Math.round(nz(t.getRatingSum()) / n * 10.0) / 10.0 : null;
    }

    /**
     * Display views keyed by shop slug for the requested slugs. Slugs without
     * a stored profile still get a neutral default view so the UI can always
     * show delivery/returns context for every seller.
     */
    public Map<String, Map<String, Object>> viewForSlugs(Collection<String> slugs) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (slugs == null || slugs.isEmpty()) return out;
        List<ShopTrust> rows;
        try {
            rows = repo.findByShopSlugIn(slugs);
        } catch (DataAccessException e) {
            rows = List.of();
        }
        Map<String, ShopTrust> bySlug = new LinkedHashMap<>();
        for (ShopTrust t : rows) bySlug.put(t.getShopSlug(), t);
        for (String slug : slugs) {
            if (slug == null || slug.isBlank() || out.containsKey(slug)) continue;
            ShopTrust t = bySlug.getOrDefault(slug, defaultFor(slug));
            out.put(slug, view(t));
        }
        return out;
    }

    /** A neutral, never-persisted profile for shops we haven't classified. */
    public ShopTrust defaultFor(String slug) {
        return ShopTrust.builder()
                .shopSlug(slug)
                .baseTrust(DEFAULT_BASE)
                .deliveryDaysMin(3).deliveryDaysMax(7)
                .codAvailable(true)
                .returnWindowDays(7).returnEase("limited")
                .authenticity("unknown")
                .warranty("Check with seller")
                .responseTime("normal")
                .computedTrust(DEFAULT_BASE)
                .build();
    }

    /** Slim, display-ready projection — never leaks raw sums. */
    public Map<String, Object> view(ShopTrust t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("shopSlug", t.getShopSlug());
        m.put("shopName", t.getShopName());
        m.put("trustScore", t.getComputedTrust() != null ? t.getComputedTrust() : computeTrust(t));
        m.put("deliveryDaysMin", t.getDeliveryDaysMin());
        m.put("deliveryDaysMax", t.getDeliveryDaysMax());
        m.put("codAvailable", t.getCodAvailable());
        m.put("returnWindowDays", t.getReturnWindowDays());
        m.put("returnEase", t.getReturnEase());
        m.put("authenticity", t.getAuthenticity());
        m.put("warranty", t.getWarranty());
        m.put("responseTime", t.getResponseTime());
        m.put("ratingAvg", avgRating(t));
        m.put("ratingCount", nz(t.getRatingCount()));
        m.put("avgReportedDelivery", avgReportedDelivery(t));
        int recs = nz(t.getRecommendYes()) + nz(t.getRecommendNo());
        m.put("recommendRate", recs > 0 ? Math.round((double) nz(t.getRecommendYes()) / recs * 100) : null);
        return m;
    }

    /**
     * Fold one community review into a shop's aggregates and recompute its
     * score. Creates the profile from the neutral default if the shop has no
     * curated baseline yet. Best-effort: a Mongo outage is swallowed so a
     * review submission never 500s on the trust side.
     *
     * @return the updated profile, or null if persistence failed.
     */
    public ShopTrust applyReview(String slug, Integer rating, String trustVote,
                                 Boolean recommend, Integer deliveryDays) {
        if (slug == null || slug.isBlank()) return null;
        try {
            ShopTrust t = repo.findByShopSlug(slug).orElseGet(() -> {
                ShopTrust d = defaultFor(slug);
                d.setId(null); // ensure insert, not the synthetic default
                return d;
            });
            if (rating != null && rating >= 1 && rating <= 5) {
                t.setRatingCount(nz(t.getRatingCount()) + 1);
                t.setRatingSum(nz(t.getRatingSum()) + rating);
            }
            if ("up".equalsIgnoreCase(trustVote))   t.setTrustUp(nz(t.getTrustUp()) + 1);
            if ("down".equalsIgnoreCase(trustVote)) t.setTrustDown(nz(t.getTrustDown()) + 1);
            if (Boolean.TRUE.equals(recommend))  t.setRecommendYes(nz(t.getRecommendYes()) + 1);
            if (Boolean.FALSE.equals(recommend)) t.setRecommendNo(nz(t.getRecommendNo()) + 1);
            if (deliveryDays != null && deliveryDays >= 0 && deliveryDays <= 60) {
                t.setDeliveryReports(nz(t.getDeliveryReports()) + 1);
                t.setDeliveryDaysSum(nz(t.getDeliveryDaysSum()) + deliveryDays);
            }
            t.setComputedTrust(computeTrust(t));
            t.setUpdatedAt(LocalDateTime.now());
            return repo.save(t);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }
    private static double nz(Double v) { return v == null ? 0.0 : v; }
}
