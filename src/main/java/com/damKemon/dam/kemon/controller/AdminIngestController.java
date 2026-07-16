package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import com.damKemon.dam.kemon.service.SellerDirectoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded catalog inlet for trusted workers and operator-curated offers. Each
 * batch uses the indexer's URL/match-key merge path and category gate without
 * warming the full-catalog fuzzy index.
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class AdminIngestController {

    private static final Logger log = LoggerFactory.getLogger(AdminIngestController.class);
    private static final int MAX_OFFERS = 250;

    private final BulkIndexer indexer;
    private final ShopRepository shops;
    private final SellerDirectoryService sellers;

    public AdminIngestController(BulkIndexer indexer, ShopRepository shops,
                                 SellerDirectoryService sellers) {
        this.indexer = indexer;
        this.shops = shops;
        this.sellers = sellers;
    }

    public record IngestOffer(String name, Double price, Double originalPrice,
                              String productUrl, String imageUrl, Boolean inStock,
                              String sellerName, String sellerId) {}

    public record IngestBatch(String shopSlug, List<IngestOffer> offers) {}

    public record IngestRequest(List<IngestBatch> batches) {}

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody IngestRequest request) {
        if (request == null || request.batches() == null || request.batches().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no batches"));
        }
        int submitted = request.batches().stream()
                .filter(Objects::nonNull)
                .map(IngestBatch::offers)
                .filter(Objects::nonNull)
                .mapToInt(List::size)
                .sum();
        if (submitted > MAX_OFFERS) {
            return ResponseEntity.status(413).body(Map.of(
                    "error", "too many offers",
                    "maxOffers", MAX_OFFERS,
                    "submitted", submitted));
        }

        Map<String, Object> perShop = new LinkedHashMap<>();
        List<String> unknownShops = new ArrayList<>();
        int inserted = 0;
        int merged = 0;
        int outOfScope = 0;
        int invalid = 0;
        for (IngestBatch batch : request.batches()) {
            if (batch == null || batch.shopSlug() == null || batch.offers() == null) continue;
            Shop shop = shops.findBySlug(batch.shopSlug().trim()).orElse(null);
            if (shop == null) {
                unknownShops.add(batch.shopSlug());
                continue;
            }
            List<ScrapedProduct> scraped = new ArrayList<>();
            int shopInvalid = 0;
            for (IngestOffer o : batch.offers()) {
                if (o == null || o.name() == null || o.name().isBlank()
                        || o.productUrl() == null || o.productUrl().isBlank()
                        || o.price() == null || o.price() < 10) {
                    invalid++;
                    shopInvalid++;
                    continue;
                }
                scraped.add(ScrapedProduct.builder()
                        .name(o.name().trim())
                        .price(o.price())
                        .originalPrice(o.originalPrice())
                        .productUrl(o.productUrl())
                        .imageUrl(o.imageUrl())
                        .inStock(o.inStock() == null ? Boolean.TRUE : o.inStock())
                        .sellerName(o.sellerName())
                        .sellerId(o.sellerId())
                        .build());
            }
            BulkIndexer.FastIngestResult result = indexer.enrichFast(shop, scraped);
            boolean reactivated = reactivateIfRecovered(shop, result.accepted());
            inserted += result.inserted();
            merged += result.merged();
            outOfScope += result.outOfScope();
            perShop.put(shop.getSlug(), Map.of(
                    "submitted", batch.offers().size(),
                    "accepted", result.accepted(),
                    "inserted", result.inserted(),
                    "merged", result.merged(),
                    "outOfScope", result.outOfScope(),
                    "invalid", shopInvalid,
                    "reactivated", reactivated));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submitted", submitted);
        out.put("accepted", inserted + merged);
        out.put("inserted", inserted);
        out.put("merged", merged);
        out.put("outOfScope", outOfScope);
        out.put("invalid", invalid);
        out.put("shops", perShop);
        if (!unknownShops.isEmpty()) out.put("unknownShops", unknownShops);
        log.info("ManualIngest: {} inserted, {} merged across {} shops{}",
                inserted, merged, perShop.size(),
                unknownShops.isEmpty() ? "" : " (unknown: " + unknownShops + ")");
        return ResponseEntity.ok(out);
    }

    private boolean reactivateIfRecovered(Shop shop, int accepted) {
        if (accepted <= 0
                || "active".equalsIgnoreCase(shop.getStatus())
                || "operator".equalsIgnoreCase(shop.getBlockedBy())) {
            return false;
        }
        shop.setStatus("active");
        shop.setBlockedBy(null);
        shop.setHealth("active");
        shop.setConsecutiveFailures(0);
        shop.setNeedsRetry(false);
        shop.setLastError(null);
        shop.setUpdatedAt(LocalDateTime.now());
        shops.save(shop);
        sellers.syncShop(shop);
        return true;
    }
}
