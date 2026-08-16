package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.MarketplaceSeller;
import com.damKemon.dam.kemon.repository.MarketplaceSellerRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds and serves per-seller reputation for marketplace sub-sellers (Daraz
 * storefronts, etc). Every signal is REAL and scraped: we aggregate each
 * seller's listings by {@code sellerId} and derive a 0..100 score from their
 * average product rating (weighted by review volume) plus units sold and
 * catalog size. New/unrated sellers sit near a neutral 50; consistently
 * well-rated, high-selling sellers climb toward the 80s–90s.
 *
 * @see com.damKemon.dam.kemon.model.MarketplaceSeller
 */
@Service
public class MarketplaceSellerService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceSellerService.class);

    /** Neutral starting point for a marketplace seller before signal moves it. */
    private static final int BASE = 50;

    private final MarketplaceSellerRepository repo;
    private final MongoTemplate mongoTemplate;

    public MarketplaceSellerService(MarketplaceSellerRepository repo, MongoTemplate mongoTemplate) {
        this.repo = repo;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Reputation from real signals. Rating moves the score most (±35), gated by
     * review-volume confidence so a single 5★ listing can't inflate a seller;
     * sales volume and catalog size add smaller establishment bumps.
     */
    public int computeScore(Double ratingAvg, Integer reviewTotal, Long soldTotal, int listings) {
        double score = BASE;
        if (ratingAvg != null) {
            double conf = (reviewTotal != null && reviewTotal > 0)
                    ? Math.min(reviewTotal, 200) / 200.0          // reviews = real confidence
                    : Math.min(listings, 20) / 20.0 * 0.4;        // rated but few reviews → weak
            score += (ratingAvg - 3.0) / 2.0 * 35.0 * conf;       // up to ±35
        }
        long sold = soldTotal == null ? 0 : soldTotal;
        if (sold > 0) score += Math.min(sold, 2000) / 2000.0 * 12.0;   // up to +12
        if (listings >= 5) score += Math.min(listings, 50) / 50.0 * 5.0; // up to +5
        return (int) Math.round(Math.max(0, Math.min(100, score)));
    }

    /**
     * Recompute every marketplace seller's reputation from the current catalog.
     * Best-effort: a Mongo hiccup logs and returns 0 rather than throwing.
     *
     * @return number of seller profiles upserted.
     */
    public int recompute() {
        try {
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.unwind("prices"),
                    Aggregation.match(Criteria.where("prices.sellerId").ne(null)),
                    Aggregation.group("prices.sellerId")
                            .first("prices.sellerName").as("sellerName")
                            .first("prices.siteSlug").as("marketplace")
                            .count().as("listings")
                            .avg("prices.rating").as("ratingAvg")
                            .sum("prices.reviewCount").as("reviewTotal")
                            .sum("prices.soldCount").as("soldTotal"));
            AggregationResults<Document> res = mongoTemplate.aggregate(agg, "products", Document.class);

            int n = 0;
            for (Document d : res) {
                String sellerId = d.getString("_id");
                if (sellerId == null || sellerId.isBlank()) continue;
                Double ratingAvg = num(d.get("ratingAvg"));
                if (ratingAvg != null) ratingAvg = Math.round(ratingAvg * 10.0) / 10.0;
                int listings = (int) lng(d.get("listings"));
                int reviewTotal = (int) lng(d.get("reviewTotal"));
                long soldTotal = lng(d.get("soldTotal"));

                MarketplaceSeller m = repo.findBySellerId(sellerId)
                        .orElseGet(() -> MarketplaceSeller.builder().sellerId(sellerId).build());
                m.setSellerName(d.getString("sellerName"));
                m.setMarketplace(d.getString("marketplace"));
                m.setListingCount(listings);
                m.setRatingAvg(ratingAvg);
                m.setReviewTotal(reviewTotal);
                m.setSoldTotal(soldTotal);
                m.setTrustScore(computeScore(ratingAvg, reviewTotal, soldTotal, listings));
                m.setUpdatedAt(LocalDateTime.now());
                repo.save(m);
                n++;
            }
            log.info("Marketplace seller trust: recomputed {} sellers", n);
            return n;
        } catch (Exception e) {
            log.warn("Marketplace seller trust recompute failed: {}", e.getMessage());
            return 0;
        }
    }

    /** Display views keyed by sellerId for the requested ids (unknown ids omitted). */
    public Map<String, Map<String, Object>> viewForSellerIds(Collection<String> ids) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        List<MarketplaceSeller> rows;
        try {
            rows = repo.findBySellerIdIn(ids);
        } catch (Exception e) {
            return out;
        }
        for (MarketplaceSeller m : rows) {
            if (m.getSellerId() == null) continue;
            out.put(m.getSellerId(), view(m));
        }
        return out;
    }

    private Map<String, Object> view(MarketplaceSeller m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("sellerId", m.getSellerId());
        v.put("sellerName", m.getSellerName());
        v.put("marketplace", m.getMarketplace());
        v.put("trustScore", m.getTrustScore());
        v.put("ratingAvg", m.getRatingAvg());
        v.put("reviewTotal", m.getReviewTotal());
        v.put("soldTotal", m.getSoldTotal());
        v.put("listingCount", m.getListingCount());
        return v;
    }

    /** Nightly refresh, after the catalog index (3 AM) and scraped-signal recompute (4:20). */
    @Scheduled(cron = "0 35 4 * * *")
    public void scheduledRecompute() {
        recompute();
    }

    private static Double num(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : null;
    }

    private static long lng(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }
}
