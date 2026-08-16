package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.model.Product;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The homepage category rails, PRECOMPUTED. The old @Cacheable version made
 * the first visitor after every 60s cache expiry pay for 6 sorted category
 * queries — which is why the rails "appeared very late". Now the web node
 * builds the sections in the background (startup + every 15 min) and requests
 * are a volatile read: the rails render as fast as the bundle loads.
 *
 * <p>Every candidate's NAME is re-run through the {@link QueryClassifier}
 * before it may represent a category — a product whose stored label disagrees
 * with today's classifier (the Hikvision-CCTV-as-Headphones rows written by an
 * older keyword table) is skipped instead of headlining the homepage. The
 * catalog rows themselves are cleaned separately (focus-cleanup/reclassify).
 */
@Service
public class ShowcaseService {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseService.class);

    /** Cards per rail; the FE shows exactly this many. */
    private static final int PER_CATEGORY = 6;

    /** Flagship categories lead the homepage; anything unlisted keeps size order after them. */
    private static final List<String> PREFERRED = List.of("smartphones", "laptops", "desktops & pc",
            "monitors", "components", "headphones & audio", "accessories");

    private final MongoTemplate mongo;
    private final ShopVisibilityService shopVisibility;
    private final QueryClassifier classifier;
    private final AppRole appRole;

    private volatile List<Map<String, Object>> latest = List.of();

    public ShowcaseService(MongoTemplate mongo,
                           ShopVisibilityService shopVisibility,
                           QueryClassifier classifier,
                           AppRole appRole) {
        this.mongo = mongo;
        this.shopVisibility = shopVisibility;
        this.classifier = classifier;
        this.appRole = appRole;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!appRole.isWeb()) return;
        try {
            // Backs the per-category freshest-first fetch below (and any other
            // exact-category + recency read). Idempotent.
            mongo.indexOps(Product.class).ensureIndex(new Index()
                    .on("category", Sort.Direction.ASC)
                    .on("updatedAt", Sort.Direction.DESC));
        } catch (Exception e) {
            log.warn("Showcase: category+updatedAt index not created: {}", e.getMessage());
        }
        CompletableFuture.runAsync(this::rebuild);
    }

    @Scheduled(fixedDelayString = "${showcase.refresh-ms:900000}",
               initialDelayString = "${showcase.refresh-ms:900000}")
    public void scheduledRebuild() {
        if (appRole.isWeb()) rebuild();
    }

    public void rebuild() {
        try {
            List<Document> pipeline = List.of(
                    new Document("$match", new Document("category",
                            new Document("$nin", java.util.Arrays.asList(null, "", "general")))),
                    new Document("$group", new Document("_id", "$category")
                            .append("total", new Document("$sum", 1))),
                    new Document("$sort", new Document("total", -1)),
                    new Document("$limit", 8));
            List<Document> cats = new ArrayList<>();
            mongo.getCollection("products").aggregate(pipeline).into(cats);
            cats.sort(Comparator.comparingInt(d -> {
                int i = PREFERRED.indexOf(String.valueOf(d.getString("_id")).toLowerCase());
                return i < 0 ? PREFERRED.size() : i;
            }));
            if (cats.size() > 6) cats = cats.subList(0, 6);

            List<Map<String, Object>> out = new ArrayList<>();
            for (Document d : cats) {
                String cat = d.getString("_id");
                if (cat == null || cat.isBlank()) continue;
                // Deep over-fetch (up to 200 freshest): the freshest rows in a
                // polluted category are often the junk (a batch of routers just
                // crawled into "smartphones"), so we must scan past them to find
                // the real members. Exact category match keeps the index in play.
                List<Product> rows = mongo.find(
                        Query.query(Criteria.where("category").is(cat))
                                .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                                .limit(200),
                        Product.class);
                // Collect ALL valid candidates, then pick by highest seller count.
                List<Product> candidates = new ArrayList<>();
                for (Product p : rows) {
                    if (p.getName() == null) continue;
                    shopVisibility.stripInPlace(p);
                    if (p.getPrices() == null || p.getPrices().isEmpty()) continue;
                    if (p.getLowestPrice() == null || p.getImageUrl() == null) continue;
                    // Strict guard: the rail shows a product ONLY when today's
                    // classifier POSITIVELY files it under this exact rail. A row
                    // whose stored category is wrong (indexer misclassified it) is
                    // excluded even if it hasn't been re-indexed yet — the homepage
                    // never inherits stale category data. ("general" no longer
                    // gets a pass; a curated rail wants certainty.)
                    String reclass = classifier.classify(p.getName())
                            .primaryCategory().getLabel().toLowerCase();
                    if (!reclass.equals(cat)) continue;
                    candidates.add(p);
                }
                // Sort by number of sellers (prices) descending — products
                // with the most sellers headline the homepage rail.
                candidates.sort(Comparator.comparingInt(
                        (Product p) -> p.getPrices().size()).reversed());
                List<Product> keep = candidates.size() > PER_CATEGORY
                        ? candidates.subList(0, PER_CATEGORY)
                        : candidates;
                if (keep.isEmpty()) continue;
                Map<String, Object> section = new LinkedHashMap<>();
                section.put("category", cat);
                section.put("total", ((Number) d.get("total")).longValue());
                section.put("products", keep);
                out.add(section);
            }
            this.latest = out;
            log.info("Showcase: rebuilt {} category rails", out.size());
        } catch (Exception e) {
            // keep serving the previous build — the homepage must never 500 on this
            log.warn("Showcase rebuild failed ({}) — keeping previous rails", e.getMessage());
        }
    }

    /** Instant read of the precomputed rails, sliced when fewer cards are asked for. */
    public List<Map<String, Object>> get(int perCategory) {
        List<Map<String, Object>> snapshot = latest;
        if (perCategory >= PER_CATEGORY) return snapshot;
        List<Map<String, Object>> out = new ArrayList<>(snapshot.size());
        for (Map<String, Object> section : snapshot) {
            @SuppressWarnings("unchecked")
            List<Product> ps = (List<Product>) section.get("products");
            Map<String, Object> copy = new LinkedHashMap<>(section);
            copy.put("products", ps.size() > perCategory ? ps.subList(0, perCategory) : ps);
            out.add(copy);
        }
        return out;
    }
}
