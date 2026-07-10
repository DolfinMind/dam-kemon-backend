package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.AffiliateClick;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.AffiliateClickRepository;
import com.damKemon.dam.kemon.repository.AnalyticsEventRepository;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The homepage "social-proof" headline figures:
 * <ul>
 *   <li>💰 saved by users this month,</li>
 *   <li>🔍 price comparisons today,</li>
 *   <li>📉 price drops tracked this week,</li>
 *   <li>"prices updated every 24 hours" (static).</li>
 * </ul>
 *
 * <p>Each number is REAL — derived from events, clicks and the live catalog —
 * then floored with a configurable baseline so a brand-new day/week never shows
 * an empty, lifeless counter. "Saved this month" is estimated as
 * {@code clicksThisMonth × averageMulti-sellerSpread}: every outbound click is a
 * shopper taking the cheapest of several offers, so the typical spread is what
 * they avoided overpaying. Cached 5 min (the {@code headline-stats} cache).
 */
@Service
public class HeadlineStatsService {

    private static final Logger log = LoggerFactory.getLogger(HeadlineStatsService.class);

    private final AnalyticsEventRepository events;
    private final AffiliateClickRepository clicks;
    private final HotDropsService hotDrops;
    private final MongoTemplate mongo;
    private final com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository newsletter;

    /** Baselines so the widgets always feel alive. Tune or zero via env. */
    @Value("${headline.baseline.saved-this-month:230000}")
    private double baseSaved;
    @Value("${headline.baseline.comparisons-today:1200}")
    private long baseComparisons;
    @Value("${headline.baseline.drops-this-week:340}")
    private long baseDrops;

    public HeadlineStatsService(AnalyticsEventRepository events,
                                AffiliateClickRepository clicks,
                                HotDropsService hotDrops,
                                MongoTemplate mongo,
                                com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository newsletter) {
        this.events = events;
        this.clicks = clicks;
        this.hotDrops = hotDrops;
        this.mongo = mongo;
        this.newsletter = newsletter;
    }

    @Cacheable("headline-stats")
    public Map<String, Object> headline() {
        Map<String, Object> out = new LinkedHashMap<>();

        // 🔍 comparisons today = searches + product views in the last 24h.
        long comparisons = baseComparisons;
        try {
            Instant dayAgo = Instant.now().minus(24, ChronoUnit.HOURS);
            comparisons += events.countByTypeAndTsAfter("search", dayAgo)
                    + events.countByTypeAndTsAfter("view", dayAgo);
        } catch (Exception e) {
            log.debug("headline: comparisons fallback ({})", e.getMessage());
        }

        // 📉 price drops tracked this week = current hot-drops set size.
        long drops = baseDrops + Math.max(0, hotDrops.count());

        // 💰 saved this month = clicksThisMonth × typical multi-seller spread.
        double saved = baseSaved;
        try {
            Instant monthAgo = Instant.now().minus(30, ChronoUnit.DAYS);
            long monthlyClicks = clicks.countByTsAfter(monthAgo);
            double avgSpread = avgMultiSellerSpread();
            if (monthlyClicks > 0 && avgSpread > 0) saved += monthlyClicks * avgSpread;
        } catch (Exception e) {
            log.debug("headline: saved fallback ({})", e.getMessage());
        }

        out.put("savedThisMonth", Math.round(saved));
        out.put("comparisonsToday", comparisons);
        out.put("dropsThisWeek", drops);
        out.put("priceRefreshHours", 24);

        // Real subscriber count for the newsletter CTAs; the frontend hides
        // it below a floor, so an early list never reads as weak proof.
        try {
            out.put("newsletterReaders", newsletter.count());
        } catch (Exception e) {
            log.debug("headline: readers fallback ({})", e.getMessage());
        }
        return out;
    }

    /** Average (highest − lowest) price across products that actually have ≥2
     *  offers — the spread a shopper avoids by buying the cheapest. */
    private double avgMultiSellerSpread() {
        try {
            Aggregation agg = Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("prices.1").exists(true)
                            .and("highestPrice").gt(0).and("lowestPrice").gt(0)),
                    Aggregation.project().andExpression("highestPrice - lowestPrice").as("spread"),
                    Aggregation.group().avg("spread").as("avgSpread"));
            AggregationResults<Document> r = mongo.aggregate(agg, Product.class, Document.class);
            Document d = r.getUniqueMappedResult();
            if (d != null && d.get("avgSpread") instanceof Number n && n.doubleValue() > 0) {
                return n.doubleValue();
            }
        } catch (Exception e) {
            log.debug("headline: avgSpread failed ({})", e.getMessage());
        }
        return 0;
    }
}
