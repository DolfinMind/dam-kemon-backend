package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.intelligence.ProductCategory;
import com.damKemon.dam.kemon.intelligence.QueryClassifier;
import com.damKemon.dam.kemon.model.PriceHistory;
import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.model.ShopTrust;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.AffiliateClickRepository;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final TrustService trustService;
    private final MongoTemplate mongoTemplate;
    private final AffiliateClickRepository affiliateClicks;
    private final QueryClassifier classifier;
    private final CategoryFocusService categoryFocus;
    private final ShopVisibilityService shopVisibility;
    private final AppRole appRole;

    public ProductService(ProductRepository productRepository,
                          ReviewRepository reviewRepository,
                          PriceHistoryRepository priceHistoryRepository,
                          TrustService trustService,
                          MongoTemplate mongoTemplate,
                          AffiliateClickRepository affiliateClicks,
                          QueryClassifier classifier,
                          CategoryFocusService categoryFocus,
                          ShopVisibilityService shopVisibility,
                          AppRole appRole) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.trustService = trustService;
        this.mongoTemplate = mongoTemplate;
        this.affiliateClicks = affiliateClicks;
        this.classifier = classifier;
        this.categoryFocus = categoryFocus;
        this.shopVisibility = shopVisibility;
        this.appRole = appRole;
    }

    /**
     * Bring the catalog down to the allowed category focus (computing + mobile).
     * Heap-safe: streams the catalog in id-ordered pages reading only name+category,
     * decides per product, then applies in batches. A product already in an allowed
     * category is checked against a fresh name classification too, so an appliance
     * historically mislabeled as a smartphone is removed. Ambiguous GENERAL names
     * keep their existing allowed category. {@code dryRun} reports without writes.
     */
    public Map<String, Object> focusCleanup(boolean dryRun) {
        if (!categoryFocus.isEnabled()) {
            return Map.of("enabled", false, "note", "category-focus is disabled");
        }
        int scanned = 0, kept = 0, reclassified = 0;
        List<String> deleteIds = new ArrayList<>();
        Map<String, String> updates = new LinkedHashMap<>();
        List<String> sampleDeletes = new ArrayList<>();

        int page = 0;
        final int pageSize = 1000;
        try {
            while (true) {
                Query q = new Query().with(PageRequest.of(page, pageSize, Sort.by(Sort.Direction.ASC, "_id")));
                q.fields().include("name").include("category");
                List<Product> rows = mongoTemplate.find(q, Product.class);
                if (rows.isEmpty()) break;
                for (Product p : rows) {
                    scanned++;
                    String name = p.getName();
                    ProductCategory nc = (name == null || name.isBlank())
                            ? null : classifier.classify(name).primaryCategory();
                    boolean currentAllowed = categoryFocus.isAllowedLabel(p.getCategory());
                    if (currentAllowed && (nc == null || nc == ProductCategory.GENERAL)) {
                        kept++;
                        continue;
                    }
                    if (nc != null && categoryFocus.isAllowed(nc)) {
                        String label = nc.getLabel().toLowerCase();
                        if (!label.equalsIgnoreCase(p.getCategory())) {
                            updates.put(p.getId(), label);
                            reclassified++;
                        } else {
                            kept++;
                        }
                    } else {
                        deleteIds.add(p.getId());
                        if (sampleDeletes.size() < 25 && name != null) {
                            sampleDeletes.add(name + "  [" + p.getCategory() + "]");
                        }
                    }
                }
                page++;
                if (page > 5000) break; // hard safety bound
            }
        } catch (DataAccessException e) {
            return Map.of("error", "catalog scan failed", "scanned", scanned);
        }

        if (!dryRun) {
            if (!updates.isEmpty()) {
                try {
                    BulkOperations bulk = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, Product.class);
                    LocalDateTime now = LocalDateTime.now();
                    for (Map.Entry<String, String> e : updates.entrySet()) {
                        bulk.updateOne(Query.query(Criteria.where("_id").is(e.getKey())),
                                new Update().set("category", e.getValue()).set("updatedAt", now));
                    }
                    bulk.execute();
                } catch (Exception e) { /* best-effort; rerun is safe */ }
            }
            int from = 0;
            while (from < deleteIds.size()) {
                int to = Math.min(from + pageSize, deleteIds.size());
                try {
                    mongoTemplate.remove(Query.query(Criteria.where("_id").in(deleteIds.subList(from, to))), Product.class);
                } catch (Exception ignored) { /* skip batch */ }
                from = to;
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("scanned", scanned);
        out.put("keptInScope", kept);
        out.put("reclassifiedKept", reclassified);
        out.put("deleted", deleteIds.size());
        out.put("remainingAfter", kept + reclassified);
        out.put("allowed", categoryFocus.allowed().stream().map(ProductCategory::getLabel).toList());
        out.put("sampleDeletes", sampleDeletes);
        return out;
    }

    /** Repair category drift after repeated crawls without running on both JVMs. */
    @Scheduled(cron = "${category-focus.cleanup-cron:0 0 6 * * SUN}")
    public void scheduledFocusCleanup() {
        if (!appRole.isWorker() || !categoryFocus.isEnabled()) return;
        focusCleanup(false);
    }

    /**
     * Re-run the (improved) classifier over the whole catalog and update each
     * product's {@code category}. Fixes rows mis-categorised by an older
     * classifier (e.g. an "iPhone case" stored as a smartphone). Admin-only,
     * one-off; returns counts. Best-effort — skips on Mongo errors.
     */
    public Map<String, Object> reclassifyAll() {
        int total = 0, changed = 0;
        try {
            for (Product p : productRepository.findAll()) {
                total++;
                if (p.getName() == null || p.getName().isBlank()) continue;
                String cat = classifier.classify(p.getName()).primaryCategory().getLabel().toLowerCase();
                if (!cat.equalsIgnoreCase(p.getCategory())) {
                    p.setCategory(cat);
                    p.setUpdatedAt(LocalDateTime.now());
                    try { productRepository.save(p); changed++; }
                    catch (DataAccessException ignored) { /* skip this row */ }
                }
            }
        } catch (DataAccessException e) {
            return Map.of("error", "catalog read failed", "total", total, "changed", changed);
        }
        return Map.of("total", total, "changed", changed);
    }

    /**
     * Distinct, non-blank product categories for the Browse filter, deduped
     * case-insensitively (the catalog has both "Smartphones" and "smartphones")
     * and sorted. Returns the lower-cased form; the UI capitalises for display.
     */
    public List<String> getCategories() {
        try {
            List<String> cats = mongoTemplate.findDistinct(new Query(), "category", Product.class, String.class);
            return cats.stream()
                    .filter(c -> c != null && !c.isBlank())
                    .map(c -> c.trim().toLowerCase())
                    .filter(c -> !categoryFocus.isEnabled() || categoryFocus.isAllowedLabel(c))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    public Page<Product> getAllProducts(Pageable pageable) {
        return getAllProducts(null, pageable);
    }

    /** All products, or just one category when {@code category} is provided. */
    public Page<Product> getAllProducts(String category, Pageable pageable) {
        try {
            // Exact match on the lower-cased category, NOT findByCategoryIgnoreCase:
            // "IgnoreCase" compiles to a case-insensitive $regex that can't use the
            // @Indexed on `category`, so it scanned the whole catalog (~1s+ per
            // Browse page). Categories are always stored lower-case at index time,
            // so a plain indexed equality is both correct and O(log n).
            String normalized = category == null ? "" : category.trim().toLowerCase();
            Page<Product> page;
            if (categoryFocus.isEnabled()) {
                if (!normalized.isBlank() && !categoryFocus.isAllowedLabel(normalized)) {
                    return new PageImpl<>(Collections.emptyList(), pageable, 0);
                }
                page = normalized.isBlank()
                        ? productRepository.findByCategoryIn(categoryFocus.allowedLabels(), pageable)
                        : productRepository.findByCategory(normalized, pageable);
            } else {
                page = normalized.isBlank()
                        ? productRepository.findAll(pageable)
                        : productRepository.findByCategory(normalized, pageable);
            }
            // Strip hidden shops and legacy misclassified rows immediately;
            // the scheduled cleanup later removes them from Mongo. ponytail:
            // filtered rows can leave a short page until that cleanup lands.
            List<Product> visible = new ArrayList<>(page.getNumberOfElements());
            for (Product product : page) {
                shopVisibility.stripInPlace(product);
                if (isPubliclyVisible(product)) visible.add(product);
            }
            return new PageImpl<>(visible, pageable, page.getTotalElements());
        } catch (DataAccessException e) {
            return new PageImpl<>(Collections.emptyList(), pageable, 0);
        }
    }

    /**
     * Homepage "most compared" rail: the products offered by the most sellers.
     * Mongo ranks ids by raw offer count on a slim projection (cheap sort),
     * then we re-check against VISIBLE offers after shop-visibility stripping,
     * over-fetching 3× to survive it. Cached 60s like the showcase.
     */
    @org.springframework.cache.annotation.Cacheable(value = "most-sellers", key = "{#limit, #minSellers}")
    public List<Product> mostSellers(int limit, int minSellers) {
        try {
            List<org.bson.Document> pipeline = List.of(
                    new org.bson.Document("$project", new org.bson.Document("sellerCount",
                            new org.bson.Document("$size", new org.bson.Document("$ifNull",
                                    java.util.Arrays.asList("$prices", List.of()))))),
                    new org.bson.Document("$match", new org.bson.Document("sellerCount",
                            new org.bson.Document("$gte", minSellers))),
                    new org.bson.Document("$sort", new org.bson.Document("sellerCount", -1)),
                    new org.bson.Document("$limit", (long) limit * 3));
            List<String> ids = new java.util.ArrayList<>();
            mongoTemplate.getCollection("products").aggregate(pipeline)
                    .forEach(d -> ids.add(d.get("_id").toString()));
            List<Product> keep = new java.util.ArrayList<>(limit);
            for (Product p : findByIds(ids)) {
                shopVisibility.stripInPlace(p);
                if (p.getPrices() == null || p.getPrices().size() < minSellers) continue;
                if (p.getLowestPrice() == null || p.getImageUrl() == null) continue;
                if (!isHomepageQuality(p)) continue;
                keep.add(p);
            }
            // Stable sort: ties keep the Mongo rank order.
            keep.sort((a, b) -> b.getPrices().size() - a.getPrices().size());
            return keep.size() > limit ? new java.util.ArrayList<>(keep.subList(0, limit)) : keep;
        } catch (Exception e) {
            // Landing page must render without this rail rather than 500.
            return List.of();
        }
    }

    /** Keep stale misclassification and obvious accessory-as-device rows off the
     * landing page immediately; the weekly focus cleanup repairs them in Mongo. */
    private boolean isHomepageQuality(Product p) {
        if (!isPubliclyVisible(p)) return false;
        String category = p.getCategory() == null ? "" : p.getCategory().toLowerCase();
        if ((category.contains("smartphone") || category.contains("laptop") || category.contains("tablet"))
                && CatalogSearchService.isAccessoryProduct(p)) return false;
        return !category.contains("smartphone") || p.getLowestPrice() >= 1_000;
    }

    /** Bulk lookup preserving the caller's order. Missing ids are skipped. */
    public List<Product> findByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        try {
            Iterable<Product> rows = productRepository.findAllById(ids);
            java.util.Map<String, Product> byId = new java.util.HashMap<>();
            rows.forEach(p -> byId.put(p.getId(), p));
            List<Product> out = new java.util.ArrayList<>();
            for (String id : ids) {
                Product p = byId.get(id);
                if (p != null) out.add(p);
            }
            return out;
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Look up by Mongo {@code _id} first, then by {@code slug}. Returns
     * empty (not a 5xx) if MongoDB is unreachable, so the caller can show
     * a clean "not found" rather than "scraper unreachable".
     */
    public Optional<Product> findByIdOrSlug(String idOrSlug) {
        if (idOrSlug == null || idOrSlug.isBlank()) return Optional.empty();
        Optional<Product> found = Optional.empty();
        try {
            found = productRepository.findById(idOrSlug);
        } catch (DataAccessException ignored) {}
        if (found.isEmpty()) {
            try {
                found = productRepository.findBySlug(idOrSlug);
            } catch (DataAccessException e) {
                return Optional.empty();
            }
        }
        found.ifPresent(ProductService::dedupeOffers);
        found.ifPresent(shopVisibility::stripInPlace);
        return found.filter(this::isPubliclyVisible);
    }

    /** Immediate public barrier for legacy catalog pollution. The scheduled
     * cleanup removes these rows later; until then they must not reach browse,
     * product pages, crawler previews, or merchant redirects. */
    private boolean isPubliclyVisible(Product p) {
        if (!categoryFocus.isEnabled()) return true;
        if (!categoryFocus.isAllowedLabel(p.getCategory())) return false;
        List<ProductCategory> classified = classifier.classify(p.getName()).getCategories();
        List<ProductCategory> decisive = classified == null ? List.of() : classified.stream()
                .filter(c -> c != null && c != ProductCategory.GENERAL)
                .toList();
        if (!decisive.isEmpty()) {
            if (decisive.stream().noneMatch(categoryFocus::isAllowed)) return false;
            String stored = p.getCategory() == null ? "" : p.getCategory().trim();
            if (decisive.stream().noneMatch(c -> c.getLabel().equalsIgnoreCase(stored))) return false;
        }
        if (CatalogSearchService.isAccessoryProduct(p)
                && ("smartphones".equalsIgnoreCase(p.getCategory())
                    || "laptops".equalsIgnoreCase(p.getCategory()))) return false;
        return p.getPrices() != null && !p.getPrices().isEmpty();
    }

    /** Drop duplicate offers from a product's price list so a comparison set never
     *  shows the same seller/offer twice (item 1). Identity = the offer URL, else
     *  siteSlug+sellerId+price. In-memory only (the served object), not persisted. */
    private static void dedupeOffers(Product p) {
        if (p == null || p.getPrices() == null || p.getPrices().size() < 2) return;
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<SitePrice> out = new ArrayList<>(p.getPrices().size());
        for (SitePrice sp : p.getPrices()) {
            if (sp == null) continue;
            String key = (sp.getProductUrl() != null && !sp.getProductUrl().isBlank())
                    ? "u:" + sp.getProductUrl()
                    : "s:" + String.valueOf(sp.getSiteSlug() == null ? sp.getSiteName() : sp.getSiteSlug()).toLowerCase()
                            + "|" + (sp.getSellerId() == null ? "" : sp.getSellerId())
                            + "|" + (sp.getPrice() == null ? "" : sp.getPrice());
            if (seen.add(key)) out.add(sp);
        }
        if (out.size() != p.getPrices().size()) p.setPrices(out);
    }

    public List<PriceHistory> getPriceHistory(String productIdOrSlug) {
        try { return priceHistoryRepository.findByProductIdOrderByRecordedAtDesc(productIdOrSlug); }
        catch (DataAccessException e) { return Collections.emptyList(); }
    }

    /**
     * Daily-bucketed price series with missing days forward-filled from the
     * previous observation. Returns a stable, gap-free list of
     * {@code [{date: 'yyyy-MM-dd', price: Double}, …]} for the last N days
     * — exactly what the recharts line chart expects.
     */
    public List<java.util.Map<String, Object>> getDailyPriceSeries(String productIdOrSlug, int days) {
        if (days <= 0) days = 30;
        if (days > 365) days = 365;
        Optional<Product> p = findByIdOrSlug(productIdOrSlug);
        if (p.isEmpty()) return Collections.emptyList();
        String productId = p.get().getId();

        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate start = today.minusDays(days - 1L);

        java.util.TreeMap<java.time.LocalDate, Double> byDay = new java.util.TreeMap<>();
        try {
            for (PriceHistory h : priceHistoryRepository.findByProductIdOrderByRecordedAtDesc(productId)) {
                if (h.getRecordedAt() == null || h.getPrice() == null) continue;
                java.time.LocalDate d = h.getRecordedAt().toLocalDate();
                // Keep the earliest entry for each day (the order is desc, so we
                // overwrite — the final value for each day is the latest of that day).
                byDay.put(d, h.getPrice());
            }
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }

        List<java.util.Map<String, Object>> series = new java.util.ArrayList<>();
        Double last = null;
        for (java.time.LocalDate d = start; !d.isAfter(today); d = d.plusDays(1)) {
            Double val = byDay.get(d);
            if (val != null) last = val;
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("date", d.toString());
            row.put("price", last);
            series.add(row);
        }
        // If there's no history but the product itself has a lowestPrice,
        // back-fill the entire window with that so the chart isn't empty.
        if (series.stream().allMatch(r -> r.get("price") == null) && p.get().getLowestPrice() != null) {
            double v = p.get().getLowestPrice();
            for (java.util.Map<String, Object> r : series) r.put("price", v);
        }
        return series;
    }

    public List<Review> getReviews(String productIdOrSlug) {
        Optional<Product> p = findByIdOrSlug(productIdOrSlug);
        if (p.isEmpty()) return Collections.emptyList();
        try {
            return reviewRepository.findByProductIdOrderByReviewDateDesc(p.get().getId()).stream()
                    // Hide moderated reviews and bare delivery-report data points.
                    // Legacy/scraped rows have null status — keep those visible.
                    .filter(r -> !"flagged".equals(r.getStatus()) && !"hidden".equals(r.getStatus()))
                    .filter(r -> !"delivery_report".equals(r.getSource()))
                    .filter(r -> r.getSeedBatch() == null || r.getSeedBatch().isBlank())
                    .toList();
        } catch (DataAccessException e) {
            return Collections.emptyList();
        }
    }

    /** HTTP status + body pair so the controller stays a thin pass-through. */
    public record ReviewOutcome(int status, Object body) {}

    /**
     * Persist a shopper-submitted community review and fold its trust /
     * delivery / recommend signals into the seller's {@code ShopTrust}
     * profile. Anonymous: one review per browser ({@code anonId}) per
     * product. The product's own aggregate rating is intentionally left
     * untouched — those reflect scraped seller ratings; community sentiment
     * lives in the Reviews tab and the shop trust score.
     */
    public ReviewOutcome addCommunityReview(String idOrSlug, Map<String, Object> body,
                                            String userId, String anonHeader) {
        Optional<Product> po = findByIdOrSlug(idOrSlug);
        if (po.isEmpty()) return new ReviewOutcome(404, Map.of("error", "product not found"));
        Product product = po.get();

        Integer rating = asInt(body.get("rating"));
        if (rating == null || rating < 1 || rating > 5) {
            return new ReviewOutcome(400, Map.of("error", "rating (1..5) is required"));
        }

        String anonId = firstNonBlank(anonHeader, asStr(body.get("anonId")));
        String shopSlug = trimToNull(asStr(body.get("shopSlug")));
        String siteName = trimToNull(asStr(body.get("siteName")));
        String name = firstNonBlank(trimToNull(asStr(body.get("reviewerName"))), "Damkemon member");
        String title = clamp(trimToNull(asStr(body.get("title"))), 140);
        String content = clamp(trimToNull(asStr(body.get("content"))), 2000);
        Integer deliveryDays = clampInt(asInt(body.get("deliveryDaysReported")), 0, 60);
        Boolean recommend = asBool(body.get("wouldRecommend"));
        String trustVote = normVote(asStr(body.get("trustVote")));

        if (userId != null) {
            try {
                if (reviewRepository.countByProductIdAndUserId(product.getId(), userId) > 0) {
                    return new ReviewOutcome(409, Map.of("error", "you have already reviewed this product"));
                }
            } catch (DataAccessException ignored) { /* unique vote identity still protects scoring */ }
        } else if (anonId != null) {
            try {
                if (reviewRepository.countByProductIdAndAnonId(product.getId(), anonId) > 0) {
                    return new ReviewOutcome(409, Map.of("error", "you have already reviewed this product"));
                }
            } catch (DataAccessException ignored) { /* fall through; dedup is best-effort */ }
        }

        // Verified buyer: did this browser click out to buy this product?
        boolean verified = false;
        if (anonId != null) {
            try {
                verified = shopSlug != null
                        ? affiliateClicks.existsByAnonIdAndProductIdAndSiteSlug(anonId, product.getId(), shopSlug)
                        : affiliateClicks.existsByAnonIdAndProductId(anonId, product.getId());
            } catch (DataAccessException ignored) { /* verification is best-effort */ }
        }

        // Lightweight spam gate — flagged reviews are saved (for the moderation
        // queue) but excluded from the public list and from trust scoring.
        boolean spam = looksLikeSpam(title, content);
        String status = spam ? "flagged" : "published";

        Review review = Review.builder()
                .productId(product.getId())
                .userId(userId)
                .siteName(siteName)
                .shopSlug(shopSlug)
                .reviewerName(name)
                .rating(rating)
                .title(title)
                .content(content)
                .anonId(anonId)
                .deliveryDaysReported(deliveryDays)
                .wouldRecommend(recommend)
                .trustVote(trustVote)
                .helpfulCount(0)
                .upvoteCount(0)
                .downvoteCount(0)
                .score(0)
                .authorReputation(1)
                .source("community")
                .verified(verified)
                .trusted(verified)
                .status(status)
                .reviewDate(LocalDateTime.now())
                .build();
        try {
            review = reviewRepository.save(review);
        } catch (DataAccessException e) {
            return new ReviewOutcome(500, Map.of("error", "could not save review"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("review", review);
        out.put("verified", verified);
        if (spam) out.put("pendingModeration", true);
        // Only published (non-spam) reviews move the trust score.
        if (!spam && shopSlug != null) {
            ShopTrust updated = trustService.applyReview(shopSlug, rating, trustVote, recommend, deliveryDays, verified);
            if (updated != null) out.put("trust", trustService.view(updated));
        }
        return new ReviewOutcome(201, out);
    }

    /**
     * Lightweight delivery-time report (no full review). Records one delivery
     * data point into the seller's ShopTrust. Anonymous, deduped per browser.
     * Body: {@code shopSlug} (required), {@code days} (0..60, required).
     */
    public ReviewOutcome addDeliveryReport(String idOrSlug, Map<String, Object> body, String anonHeader) {
        Optional<Product> po = findByIdOrSlug(idOrSlug);
        if (po.isEmpty()) return new ReviewOutcome(404, Map.of("error", "product not found"));
        Product product = po.get();

        String shopSlug = trimToNull(asStr(body.get("shopSlug")));
        Integer days = clampInt(asInt(body.get("days")), 0, 60);
        if (shopSlug == null || days == null) {
            return new ReviewOutcome(400, Map.of("error", "shopSlug and days (0..60) are required"));
        }
        String anonId = firstNonBlank(anonHeader, asStr(body.get("anonId")));
        if (anonId != null) {
            try {
                if (reviewRepository.countByProductIdAndAnonIdAndSource(product.getId(), anonId, "delivery_report") > 0) {
                    return new ReviewOutcome(409, Map.of("error", "you have already reported delivery for this product"));
                }
            } catch (DataAccessException ignored) { /* best-effort */ }
        }

        try {
            reviewRepository.save(Review.builder()
                    .productId(product.getId())
                    .shopSlug(shopSlug)
                    .siteName(trimToNull(asStr(body.get("siteName"))))
                    .anonId(anonId)
                    .deliveryDaysReported(days)
                    .source("delivery_report")
                    .status("published")
                    .verified(false)
                    .reviewDate(LocalDateTime.now())
                    .build());
        } catch (DataAccessException e) {
            return new ReviewOutcome(500, Map.of("error", "could not save report"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        ShopTrust updated = trustService.applyDeliveryReport(shopSlug, days);
        if (updated != null) out.put("trust", trustService.view(updated));
        return new ReviewOutcome(201, out);
    }

    /** Reviews awaiting moderation (spam-flagged). Admin-only. */
    public List<Review> flaggedReviews() {
        try { return reviewRepository.findByStatusOrderByReviewDateDesc("flagged"); }
        catch (DataAccessException e) { return Collections.emptyList(); }
    }

    /**
     * Admin moderation: set a review's status. Approving a previously-withheld
     * community review feeds its signals into the seller's trust score (they
     * were intentionally withheld while flagged).
     */
    public Review moderateReview(String reviewId, String status) {
        if (!Set.of("published", "flagged", "hidden").contains(status)) return null;
        try {
            Optional<Review> r = reviewRepository.findById(reviewId);
            if (r.isEmpty()) return null;
            Review rv = r.get();
            String prev = rv.getStatus();
            rv.setStatus(status);
            Review saved = reviewRepository.save(rv);
            if ("published".equals(status) && !"published".equals(prev)
                    && "community".equals(rv.getSource()) && rv.getShopSlug() != null) {
                trustService.applyReview(rv.getShopSlug(), rv.getRating(), rv.getTrustVote(),
                        rv.getWouldRecommend(), rv.getDeliveryDaysReported(), Boolean.TRUE.equals(rv.getVerified()));
            }
            return saved;
        } catch (DataAccessException e) {
            return null;
        }
    }

    /** Heuristic spam gate: links, phone numbers, banned words, char floods. */
    private static boolean looksLikeSpam(String title, String content) {
        String s = ((title == null ? "" : title) + " " + (content == null ? "" : content)).toLowerCase().trim();
        if (s.isBlank()) return false;
        if (s.matches("(?s).*(https?://|www\\.|\\.com|\\.net|t\\.me/|wa\\.me/|@).*")) return true;
        if (s.replaceAll("[^0-9]", "").length() >= 9) return true; // phone-like
        for (String b : new String[]{"viagra", "casino", "loan", "forex", "betting", "porn", "xxx", "earn money", "click here"}) {
            if (s.contains(b)) return true;
        }
        if (s.matches("(?s).*(.)\\1{6,}.*")) return true; // aaaaaaa floods
        return false;
    }

    // ─── lenient body parsing (Jackson hands us Integer/Double/Boolean/String) ───

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) { try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; } }
        return null;
    }

    private static Boolean asBool(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) {
            String v = s.trim().toLowerCase();
            if (v.equals("true") || v.equals("yes") || v.equals("1")) return true;
            if (v.equals("false") || v.equals("no") || v.equals("0")) return false;
        }
        return null;
    }

    private static String asStr(Object o) { return o == null ? null : o.toString(); }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String firstNonBlank(String a, String b) {
        String x = trimToNull(a);
        return x != null ? x : trimToNull(b);
    }

    private static String clamp(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static Integer clampInt(Integer v, int min, int max) {
        if (v == null) return null;
        return Math.max(min, Math.min(max, v));
    }

    private static String normVote(String s) {
        String v = trimToNull(s);
        if (v == null) return null;
        v = v.toLowerCase();
        return (v.equals("up") || v.equals("down")) ? v : null;
    }
}
