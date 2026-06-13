package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.indexer.BulkIndexer;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.repository.ShopRepository;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual curation inlet: lets an operator push hand-verified offers straight
 * into the catalog. Each batch is a shop slug plus the offers found on that
 * shop; everything flows through {@link BulkIndexer.EnrichSession}, i.e. the
 * exact matchKey/LSH/URL merge path and category gate the nightly indexer
 * uses — so manually added offers group onto existing products as extra
 * sellers, and unknown models insert as new products, with no special-case
 * persistence logic to drift out of sync.
 */
@RestController
@RequestMapping("/api/admin/catalog")
public class AdminIngestController {

    private static final Logger log = LoggerFactory.getLogger(AdminIngestController.class);

    private final BulkIndexer indexer;
    private final ShopRepository shops;

    public AdminIngestController(BulkIndexer indexer, ShopRepository shops) {
        this.indexer = indexer;
        this.shops = shops;
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
        BulkIndexer.EnrichSession session = indexer.openEnrichSession();
        Map<String, Object> perShop = new LinkedHashMap<>();
        List<String> unknownShops = new ArrayList<>();
        for (IngestBatch batch : request.batches()) {
            if (batch == null || batch.shopSlug() == null || batch.offers() == null) continue;
            Shop shop = shops.findBySlug(batch.shopSlug().trim()).orElse(null);
            if (shop == null) {
                unknownShops.add(batch.shopSlug());
                continue;
            }
            List<ScrapedProduct> scraped = new ArrayList<>();
            for (IngestOffer o : batch.offers()) {
                if (o == null || o.name() == null || o.price() == null) continue;
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
            int persisted = indexer.enrich(session, shop, scraped);
            perShop.put(shop.getSlug(), Map.of("submitted", scraped.size(), "persisted", persisted));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", session.inserted());
        out.put("merged", session.merged());
        out.put("shops", perShop);
        if (!unknownShops.isEmpty()) out.put("unknownShops", unknownShops);
        log.info("ManualIngest: {} inserted, {} merged across {} shops{}",
                session.inserted(), session.merged(), perShop.size(),
                unknownShops.isEmpty() ? "" : " (unknown: " + unknownShops + ")");
        return ResponseEntity.ok(out);
    }
}
