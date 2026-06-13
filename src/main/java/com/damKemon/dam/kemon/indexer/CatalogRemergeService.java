package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Catalog-wide duplicate-product consolidation — the fastest sellers-per-product
 * lever, because the sellers are usually ALREADY in the catalog, fragmented
 * across near-duplicate rows the per-run matching missed ("MacBook Air M2
 * 13.6 inch" vs "MacBook Air M2 13-inch 2022"; "Ryzen 9 9900X3D Gaming
 * Processor" vs "Ryzen 9 9900X3D 12 Core 24 Thread AM5 Gaming Processor").
 * Merging those rows stacks their sellers onto one product instantly, no
 * crawling required.
 *
 * <p>Algorithm: scan id+name projections; coarse-group by the model's
 * DISCRIMINATOR tokens (digit-bearing + pro/max/ultra-style qualifiers from the
 * matching-normalised name — the same tokens {@code sameProduct} treats as
 * identity); within a group, accept a merge only when the strict
 * {@link BulkIndexer#sameProduct} gate passes against the survivor (so "Spigen
 * case for iPhone 15 Pro Max" never merges into the phone — word overlap is far
 * too low). Survivor = the row with the most offers; it absorbs every distinct
 * offer (deduped by {@link BulkIndexer#offerKey}), gets re-aggregated, has its
 * matchKey rewritten with the current normaliser, and the duplicates are
 * deleted.
 *
 * <p>Idempotent and safe to re-run; {@code dryRun} previews counts + samples.
 */
@Service
public class CatalogRemergeService {

    private static final Logger log = LoggerFactory.getLogger(CatalogRemergeService.class);

    private final MongoTemplate mongo;

    @Value("${remerge.enabled:true}")
    private boolean scheduledEnabled;

    public CatalogRemergeService(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    /**
     * Nightly consolidation, after the 03:00 indexer + 04:00 retry have landed
     * their offers — fragmentation re-accumulates with every crawl, so the merge
     * runs continuously, not as a one-off. The first live run lifted avg
     * sellers/product 1.17 → 1.35 and tripled the ≥5-seller products.
     */
    @Scheduled(cron = "${remerge.cron:0 30 4 * * *}")
    public void scheduled() {
        if (!scheduledEnabled) return;
        log.info("Remerge: scheduled nightly consolidation firing");
        try { remerge(false); }
        catch (Exception e) { log.error("Remerge: scheduled run crashed", e); }
    }

    public Map<String, Object> remerge(boolean dryRun) {
        // ── pass 1: heap-safe scan of id+name, coarse-group by discriminators ──
        Map<String, List<String[]>> groups = new LinkedHashMap<>(); // coarseKey -> [id, name]
        int scanned = 0;
        int page = 0;
        final int pageSize = 2000;
        while (true) {
            Query q = new Query().with(PageRequest.of(page, pageSize, Sort.by(Sort.Direction.ASC, "_id")));
            q.fields().include("name");
            List<Product> rows = mongo.find(q, Product.class);
            if (rows.isEmpty()) break;
            for (Product p : rows) {
                scanned++;
                if (p.getId() == null || p.getName() == null || p.getName().isBlank()) continue;
                String key = coarseKey(p.getName());
                if (key == null) continue;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{p.getId(), p.getName()});
            }
            page++;
            if (page > 1000) break; // safety bound
        }

        // ── pass 2: inside multi-row groups, gate with sameProduct and merge ──
        int groupsMerged = 0, rowsAbsorbed = 0, offersMoved = 0;
        List<String> samples = new ArrayList<>();
        for (Map.Entry<String, List<String[]>> e : groups.entrySet()) {
            List<String[]> members = e.getValue();
            if (members.size() < 2) continue;

            // Cluster within the group: greedy — each unclaimed row starts a
            // cluster and pulls in every other row that passes sameProduct.
            boolean[] claimed = new boolean[members.size()];
            for (int i = 0; i < members.size(); i++) {
                if (claimed[i]) continue;
                List<String[]> cluster = new ArrayList<>();
                cluster.add(members.get(i));
                claimed[i] = true;
                for (int j = i + 1; j < members.size(); j++) {
                    if (claimed[j]) continue;
                    if (BulkIndexer.sameProduct(members.get(i)[1], members.get(j)[1])) {
                        cluster.add(members.get(j));
                        claimed[j] = true;
                    }
                }
                if (cluster.size() < 2) continue;

                MergeResult r = mergeCluster(cluster, dryRun);
                if (r == null) continue;
                groupsMerged++;
                rowsAbsorbed += r.absorbed;
                offersMoved += r.offersMoved;
                if (samples.size() < 20) {
                    samples.add(r.survivorName + "  ← absorbed " + r.absorbed
                            + " duplicate row(s), now " + r.totalOffers + " seller(s)");
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("scanned", scanned);
        out.put("clustersMerged", groupsMerged);
        out.put("duplicateRowsAbsorbed", rowsAbsorbed);
        out.put("offersMoved", offersMoved);
        out.put("samples", samples);
        log.info("Remerge: scanned={} clusters={} rowsAbsorbed={} offersMoved={} (dryRun={})",
                scanned, groupsMerged, rowsAbsorbed, offersMoved, dryRun);
        return out;
    }

    /**
     * Eject offers that don't belong on a product — the price-comparison corruption
     * a too-loose historic merge produced: a ৳4,400 case listed as the "lowest"
     * seller of a ৳92,000 phone, or a ৳15,500 item among ৳3,730 earbuds. Offers
     * carry no scraped name, so two robust signals flag a wrong offer:
     * <ul>
     *   <li>(a) its product-URL slug names an accessory (case/cover/charger…) the
     *       product itself doesn't — it's literally a different item's page; or</li>
     *   <li>(b) the product has ≥3 priced offers and this one is a hard price
     *       outlier (&lt; 0.30× or &gt; 3.5× the median) — no real seller is 70%
     *       under the field on a price-comparison catalog.</li>
     * </ul>
     * The offer is removed and the product re-aggregated; a product is never
     * emptied. Idempotent; {@code dryRun} previews counts + samples.
     */
    public Map<String, Object> purgeMismatchedOffers(boolean dryRun) {
        int scanned = 0, productsTouched = 0, offersRemoved = 0;
        List<String> samples = new ArrayList<>();
        int page = 0;
        final int pageSize = 1000;
        while (true) {
            Query q = new Query(Criteria.where("prices.1").exists(true))   // ≥2 offers
                    .with(PageRequest.of(page, pageSize, Sort.by(Sort.Direction.ASC, "_id")));
            List<Product> rows;
            try { rows = mongo.find(q, Product.class); }
            catch (Exception e) { break; }
            if (rows.isEmpty()) break;
            for (Product p : rows) {
                scanned++;
                List<SitePrice> offers = p.getPrices();
                if (offers == null || offers.size() < 2) continue;
                List<Double> vals = new ArrayList<>();
                for (SitePrice sp : offers) if (sp.getPrice() != null && sp.getPrice() > 0) vals.add(sp.getPrice());
                if (vals.size() < 2) continue;
                java.util.Collections.sort(vals);
                double median = vals.get(vals.size() / 2);
                boolean nameAcc = BulkIndexer.rawNameHasAccessory(p.getName());
                // Signal (a) only makes sense for DEVICES. An accessory-type product
                // (a charger/cable/case itself) legitimately has accessory words in
                // its offer URLs — flagging those would drop its real sellers. So
                // (a) fires only when the product is a device category.
                boolean device = isDeviceCategory(p.getCategory());
                boolean phone = "smartphones".equalsIgnoreCase(p.getCategory() == null ? "" : p.getCategory().trim());
                double maxv = vals.get(vals.size() - 1);
                // 2-offer junk: a real official-vs-grey gap is ~2×, never ≥4× — so the
                // LOW of a ≥4× pair is junk (৳90 / ৳125 / ৳10,000-booking vs the real price).
                Double twoOfferJunk = (phone && vals.size() == 2 && vals.get(1) >= vals.get(0) * 4.0)
                        ? vals.get(0) : null;
                String prodBrand = phone ? phoneBrand(p.getName()) : null;
                List<SitePrice> kept = new ArrayList<>();
                List<SitePrice> dropped = new ArrayList<>();
                for (SitePrice sp : offers) {
                    boolean bad = false;
                    Double pr = sp.getPrice();
                    if (device && !nameAcc && slugHasAccessory(sp.getProductUrl())) bad = true;   // (a)
                    if (!bad && vals.size() >= 3 && pr != null && pr > 0
                            && (pr < median * 0.30 || pr > median * 3.5)) bad = true;             // (b) relative outlier
                    if (!bad && twoOfferJunk != null && twoOfferJunk.equals(pr)) bad = true;       // (c) 2-offer junk-low
                    // Phone-specific ABSOLUTE junk (won't touch legit grey, which is >৳5k):
                    if (!bad && phone && pr != null && pr < 5000) bad = true;                      // (d) no smartphone < ৳5k
                    if (!bad && phone && pr != null && pr == 10000.0 && maxv >= 25000) bad = true; // (e) ৳10k booking deposit
                    // Wrong-product offer: the URL slug names a DIFFERENT phone brand
                    // (e.g. a ".../realme-16-pro" offer glued onto "iPhone 16 Pro").
                    // Read only the PATH — a shop domain like "applegadgetsbd.com" must
                    // not make every non-Apple phone it sells look like a mismatch.
                    if (!bad && prodBrand != null && sp.getProductUrl() != null) {
                        String path = sp.getProductUrl();
                        int proto = path.indexOf("://"); if (proto >= 0) path = path.substring(proto + 3);
                        int slash = path.indexOf('/'); path = slash >= 0 ? path.substring(slash) : "";
                        String ob = phoneBrand(path);
                        if (ob != null && !ob.equals(prodBrand)) bad = true;                       // (f) brand mismatch
                    }
                    if (bad) dropped.add(sp); else kept.add(sp);
                }
                if (dropped.isEmpty() || kept.isEmpty()) continue;   // never empty a product
                if (samples.size() < 25) {
                    SitePrice d = dropped.get(0);
                    samples.add(p.getName() + "  ✂ dropped " + dropped.size() + " offer(s) (e.g. ৳"
                            + (d.getPrice() == null ? "?" : Math.round(d.getPrice())) + " @ " + d.getSiteSlug()
                            + " | median ৳" + Math.round(median) + ")");
                }
                offersRemoved += dropped.size();
                productsTouched++;
                if (!dryRun) {
                    p.setPrices(kept);
                    BulkIndexer.recomputeAggregates(p);
                    p.setUpdatedAt(LocalDateTime.now());
                    try { mongo.save(p); }
                    catch (Exception e) { log.warn("Purge: save failed for '{}': {}", p.getName(), e.getMessage()); }
                }
            }
            page++;
            if (page > 2000) break;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dryRun);
        out.put("scanned", scanned);
        out.put("productsTouched", productsTouched);
        out.put("offersRemoved", offersRemoved);
        out.put("samples", samples);
        log.info("Purge: scanned={} productsTouched={} offersRemoved={} (dryRun={})",
                scanned, productsTouched, offersRemoved, dryRun);
        return out;
    }

    /** Device categories where an accessory-slug offer is a genuine mismatch (a
     *  case on a phone). Accessory-type categories (accessories/networking/storage/
     *  power) are excluded — their own offers legitimately name cables/chargers. */
    private static final Set<String> DEVICE_CATEGORIES = Set.of(
            "smartphones", "laptops", "desktops & pc", "tablets", "headphones & audio",
            "smartwatches", "cameras", "gaming", "tvs", "monitors",
            "printers & scanners", "home appliances", "kitchen");

    private static boolean isDeviceCategory(String category) {
        return category != null && DEVICE_CATEGORIES.contains(category.trim().toLowerCase());
    }

    /** Canonical phone brand from a name or URL slug, or null if none/ambiguous.
     *  Used to spot a wrong-product offer (a realme URL on an iPhone doc). Only
     *  unambiguous brand tokens are mapped; first match wins. */
    static String phoneBrand(String text) {
        if (text == null) return null;
        String s = " " + text.toLowerCase().replaceAll("[^a-z0-9]+", " ") + " ";
        String[][] map = {
            {"apple","iphone","ipad","macbook","airpods","imac"},
            {"samsung","galaxy"},
            {"xiaomi","redmi","poco"},
            {"realme","narzo"},
            {"oppo"}, {"vivo","iqoo"}, {"oneplus"}, {"infinix"}, {"tecno","itel"},
            {"honor"}, {"huawei"}, {"motorola"}, {"nokia"}, {"google","pixel"},
            {"nothing"}, {"asus","zenfone"}, {"walton"}, {"symphony"}
        };
        int best = Integer.MAX_VALUE; String brand = null;
        for (String[] group : map) {
            for (String tok : group) {
                int i = s.indexOf(" " + tok + " ");
                if (i >= 0 && i < best) { best = i; brand = group[0]; }
            }
        }
        return brand;
    }

    /** True if a product-URL's slug contains an accessory noun token (split on
     *  non-alphanumerics so "showcase"/"skincare" don't false-match "case"/"skin"). */
    private static boolean slugHasAccessory(String url) {
        if (url == null) return false;
        for (String w : url.toLowerCase().split("[^a-z0-9]+")) {
            if (!w.isBlank() && BulkIndexer.ACCESSORY_WORDS.contains(w)) return true;
        }
        return false;
    }

    private record MergeResult(String survivorName, int absorbed, int offersMoved, int totalOffers) {}

    /** Load the cluster's full docs, pick the survivor (most offers), absorb the
     *  rest (offers deduped by offerKey), re-aggregate, rewrite matchKey, delete dupes. */
    private MergeResult mergeCluster(List<String[]> cluster, boolean dryRun) {
        List<String> ids = cluster.stream().map(m -> m[0]).toList();
        List<Product> docs;
        try {
            docs = mongo.find(Query.query(Criteria.where("_id").in(ids)), Product.class);
        } catch (Exception e) {
            return null;
        }
        if (docs.size() < 2) return null;

        Product survivor = docs.get(0);
        for (Product p : docs) {
            if (offers(p).size() > offers(survivor).size()) survivor = p;
        }

        Set<String> have = new LinkedHashSet<>();
        List<SitePrice> mergedOffers = new ArrayList<>();
        for (SitePrice sp : offers(survivor)) {
            if (have.add(BulkIndexer.offerKey(sp) + "|" + sp.getProductUrl())) mergedOffers.add(sp);
        }
        int moved = 0;
        List<String> deleteIds = new ArrayList<>();
        for (Product p : docs) {
            if (p == survivor) continue;
            for (SitePrice sp : offers(p)) {
                if (have.add(BulkIndexer.offerKey(sp) + "|" + sp.getProductUrl())) {
                    mergedOffers.add(sp);
                    moved++;
                }
            }
            // Inherit descriptive fields the survivor lacks.
            if (survivor.getImageUrl() == null && p.getImageUrl() != null) survivor.setImageUrl(p.getImageUrl());
            if (survivor.getDescription() == null && p.getDescription() != null) survivor.setDescription(p.getDescription());
            deleteIds.add(p.getId());
        }

        if (!dryRun) {
            survivor.setPrices(mergedOffers);
            BulkIndexer.capSellers(survivor);
            BulkIndexer.recomputeAggregates(survivor);
            survivor.setMatchKey(BulkIndexer.productMatchKey(survivor.getName()));
            survivor.setUpdatedAt(LocalDateTime.now());
            try {
                mongo.save(survivor);
                mongo.remove(Query.query(Criteria.where("_id").in(deleteIds)), Product.class);
            } catch (Exception e) {
                log.warn("Remerge: cluster persist failed for '{}': {}", survivor.getName(), e.getMessage());
                return null;
            }
        }
        return new MergeResult(survivor.getName(), deleteIds.size(), moved, mergedOffers.size());
    }

    private static List<SitePrice> offers(Product p) {
        return p.getPrices() == null ? List.of() : p.getPrices();
    }

    /**
     * Coarse grouping key: the sorted discriminator tokens of the normalised name
     * (model numbers + pro/max/ultra qualifiers). Names with no discriminators
     * (generic accessories) fall back to the full normalised name — only an
     * exact-name twin can group with them. The coarse key only PROPOSES; the
     * sameProduct gate decides.
     */
    static String coarseKey(String name) {
        String norm = BulkIndexer.normaliseForMatching(name);
        if (norm == null || norm.isBlank()) return null;
        Set<String> disc = BulkIndexer.discriminators(BulkIndexer.words(norm));
        if (disc.isEmpty()) return "name:" + norm;
        return String.join(" ", new TreeSet<>(disc));
    }
}
