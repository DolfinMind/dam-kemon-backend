package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Seller;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.SellerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * F-commerce onboarding: Facebook page owners self-list their shop +
 * upload their inventory as a CSV.
 *
 * <p>We don't scrape Facebook — their ToS forbids it, and the pages are
 * hostile to crawlers anyway. The model is opt-in instead: shop owners
 * fill out a form, upload a CSV of products, and we render their
 * listings inline next to the larger shops' offerings.
 *
 * <p>CSV format (header row required):
 * {@code name,price,imageUrl,productUrl,category,description,inStock}
 */
@RestController
@RequestMapping("/api/fcommerce")
public class FcommerceController {

    private static final Logger log = LoggerFactory.getLogger(FcommerceController.class);
    private static final List<String> REQUIRED_CSV_COLS = List.of("name", "price");
    private static final int MAX_CSV_ROWS = 500;

    private final SellerRepository sellers;
    private final ProductRepository products;

    public FcommerceController(SellerRepository sellers, ProductRepository products) {
        this.sellers = sellers;
        this.products = products;
    }

    @PostMapping("/sellers/submit")
    public ResponseEntity<?> submit(@RequestBody Map<String, Object> body) {
        String name = str(body.get("name"));
        String url = str(body.get("url"));
        String type = str(body.get("type"));
        if (name == null || name.length() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }
        if (url == null || (!url.contains("facebook.com") && !url.contains("instagram.com")
                && !url.startsWith("http"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "url must be a Facebook/Instagram page or full website"));
        }

        String slug = slugify(name);
        try {
            Seller existing = sellers.findAll().stream()
                    .filter(s -> slug.equals(s.getSlug()))
                    .findFirst().orElse(null);
            if (existing != null) {
                return ResponseEntity.status(409).body(Map.of("error", "shop already listed", "slug", slug));
            }
            Seller s = Seller.builder()
                    .name(name)
                    .slug(slug)
                    .type(type == null ? "facebook" : type)
                    .url(url)
                    .messengerUrl(str(body.get("messengerUrl")))
                    .avatarUrl(str(body.get("avatarUrl")))
                    .city(str(body.get("city")))
                    .area(str(body.get("area")))
                    .codAvailable(bool(body.get("codAvailable")))
                    .sameDayDelivery(bool(body.get("sameDayDelivery")))
                    .avgReplyTime(str(body.get("avgReplyTime")))
                    .categories(stringList(body.get("categories")))
                    .verified(false)
                    .source("portal")
                    .joinedAt(LocalDateTime.now())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            sellers.save(s);
            log.info("Fcommerce: registered seller '{}'", name);
            return ResponseEntity.accepted().body(Map.of("ok", true, "id", s.getId(), "slug", slug));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save seller"));
        }
    }

    @PostMapping("/sellers/{slug}/products/upload")
    public ResponseEntity<?> uploadProducts(@PathVariable String slug,
                                            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no file uploaded"));
        }
        if (file.getSize() > 1_000_000) {
            return ResponseEntity.badRequest().body(Map.of("error", "csv too large (max 1MB)"));
        }

        Seller seller;
        try {
            seller = sellers.findAll().stream()
                    .filter(s -> slug.equals(s.getSlug()))
                    .findFirst().orElse(null);
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not load seller"));
        }
        if (seller == null) return ResponseEntity.notFound().build();

        int created = 0;
        int merged = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = r.readLine();
            if (headerLine == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "empty csv"));
            }
            List<String> header = parseCsvLine(headerLine).stream().map(s -> s.toLowerCase().trim()).toList();
            for (String c : REQUIRED_CSV_COLS) {
                if (!header.contains(c)) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "missing required column: " + c,
                            "expectedColumns", "name,price,imageUrl,productUrl,category,description,inStock"));
                }
            }

            String line;
            int row = 1;
            while ((line = r.readLine()) != null) {
                row++;
                if (row > MAX_CSV_ROWS + 1) {
                    errors.add("row " + row + ": truncated, max " + MAX_CSV_ROWS + " rows per upload");
                    break;
                }
                if (line.isBlank()) continue;
                List<String> cells = parseCsvLine(line);
                Map<String, String> rowData = new LinkedHashMap<>();
                for (int i = 0; i < header.size() && i < cells.size(); i++) {
                    rowData.put(header.get(i), cells.get(i).trim());
                }
                String name = rowData.get("name");
                String priceStr = rowData.get("price");
                if (name == null || name.length() < 2) { skipped++; continue; }
                double price;
                try { price = Double.parseDouble(priceStr.replaceAll("[^0-9.]", "")); }
                catch (Exception e) { errors.add("row " + row + ": invalid price"); skipped++; continue; }
                if (price < 10) { skipped++; continue; }

                String pUrl = rowData.getOrDefault("producturl", seller.getUrl());
                String slugified = slugify(seller.getSlug() + "-" + name);
                Optional<Product> existing = products.findByPriceUrl(pUrl);
                Product target;
                if (existing.isPresent()) {
                    target = existing.get();
                    target.getPrices().removeIf(sp -> seller.getSlug().equals(sp.getSiteSlug()));
                    merged++;
                } else {
                    target = Product.builder()
                            .name(name)
                            .slug(slugified)
                            .category(emptyToNull(rowData.get("category")))
                            .imageUrl(emptyToNull(rowData.get("imageurl")))
                            .description(emptyToNull(rowData.get("description")))
                            .prices(new ArrayList<>())
                            .createdAt(LocalDateTime.now())
                            .build();
                    created++;
                }
                SitePrice sp = SitePrice.builder()
                        .siteName(seller.getName())
                        .siteSlug(seller.getSlug())
                        .productUrl(pUrl)
                        .price(price)
                        .currency("BDT")
                        .inStock(!"false".equalsIgnoreCase(rowData.getOrDefault("instock", "true")))
                        .lastUpdated(LocalDateTime.now())
                        .build();
                target.getPrices().add(sp);
                if (target.getLowestPrice() == null || price < target.getLowestPrice()) {
                    target.setLowestPrice(price);
                }
                target.setLastScraped(LocalDateTime.now());
                target.setUpdatedAt(LocalDateTime.now());
                try { products.save(target); }
                catch (DataAccessException e) { errors.add("row " + row + ": db error"); }
            }
        } catch (Exception e) {
            log.warn("Fcommerce CSV parse failed: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "could not parse csv: " + e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", created);
        result.put("merged", merged);
        result.put("skipped", skipped);
        result.put("errors", errors);
        return ResponseEntity.ok(result);
    }

    private static List<String> parseCsvLine(String line) {
        // Minimal CSV: handles quoted commas. Good enough for shop-uploaded inventory.
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static Boolean bool(Object o) {
        if (o == null) return null;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(Object o) {
        if (o == null) return new ArrayList<>();
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        return Arrays.asList(String.valueOf(o).split("\\s*,\\s*"));
    }

    private static String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String slugify(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9\\s-]", "")
                .replaceAll("\\s+", "-").replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
    }
}
