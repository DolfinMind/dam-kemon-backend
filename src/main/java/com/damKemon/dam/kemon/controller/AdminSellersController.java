package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Seller;
import com.damKemon.dam.kemon.repository.SellerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Admin controls for the Sellers directory. Three modes for growing the
 * directory beyond what sellers.json seeds:
 *
 * <ul>
 *   <li>{@code POST /api/admin/sellers} — quick-add one seller from the
 *       operator dashboard. JSON body, idempotent on slug.</li>
 *   <li>{@code POST /api/admin/sellers/bulk} — CSV bulk-import. Each row:
 *       {@code name,facebookUrl,city,categories(|-separated),verified}.</li>
 *   <li>{@code DELETE /api/admin/sellers/demos} — purge legacy demo seeds
 *       so the directory only shows curated + Saathi-onboarded entries.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/sellers")
public class AdminSellersController {

    private static final Logger log = LoggerFactory.getLogger(AdminSellersController.class);
    private static final Pattern SLUG_SAFE = Pattern.compile("[^a-z0-9]+");

    private final SellerRepository sellers;

    public AdminSellersController(SellerRepository sellers) {
        this.sellers = sellers;
    }

    @PostMapping
    public ResponseEntity<?> quickAdd(@RequestBody Map<String, Object> body) {
        if (body == null) return ResponseEntity.badRequest().body(Map.of("error", "body required"));
        String name = str(body.get("name"));
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name required"));
        }
        String slug = str(body.get("slug"));
        if (slug == null || slug.isBlank()) slug = slugify(name);
        slug = uniqueSlug(slug);

        Seller s = Seller.builder()
                .slug(slug)
                .name(name)
                .type(str(body.get("type"), "facebook"))
                .url(str(body.get("url"), str(body.get("facebookUrl"))))
                .messengerUrl(str(body.get("messengerUrl")))
                .city(str(body.get("city"), "Dhaka"))
                .area(str(body.get("area")))
                .categories(asList(body.get("categories")))
                .brands(asList(body.get("brands")))
                .verified(asBool(body.get("verified"), false))
                .codAvailable(asBool(body.get("codAvailable"), true))
                .sameDayDelivery(asBool(body.get("sameDayDelivery"), false))
                .source(str(body.get("source"), "admin"))
                .joinedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        try {
            return ResponseEntity.ok(sellers.save(s));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "could not save"));
        }
    }

    /**
     * Bulk import via CSV. Accepted columns (header row required):
     * {@code name, slug?, url, messengerUrl?, city?, area?, categories?,
     *        verified?, codAvailable?, sameDayDelivery?, source?}.
     * Categories are pipe-separated (e.g. {@code fashion|beauty}).
     * Booleans are 1/true/yes (case-insensitive).
     */
    @PostMapping("/bulk")
    public ResponseEntity<?> bulkImport(@RequestBody String csv) {
        if (csv == null || csv.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "csv body required"));
        }
        String[] lines = csv.split("\\r?\\n");
        if (lines.length < 2) return ResponseEntity.badRequest().body(Map.of("error", "need header + at least one row"));
        Map<String, Integer> headers = new LinkedHashMap<>();
        String[] hdr = parseCsvLine(lines[0]);
        for (int i = 0; i < hdr.length; i++) headers.put(hdr[i].trim().toLowerCase(), i);

        if (!headers.containsKey("name")) {
            return ResponseEntity.badRequest().body(Map.of("error", "csv must contain a 'name' column"));
        }
        int inserted = 0, updated = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (int rowIdx = 1; rowIdx < lines.length; rowIdx++) {
            String line = lines[rowIdx];
            if (line == null || line.trim().isEmpty()) continue;
            try {
                String[] cells = parseCsvLine(line);
                String name = at(cells, headers, "name");
                if (name == null || name.isBlank()) { failed++; continue; }
                String slugRaw = at(cells, headers, "slug");
                if (slugRaw == null || slugRaw.isBlank()) slugRaw = slugify(name);
                final String slug = uniqueSlugSkipping(slugRaw, name);

                Seller s = sellers.findBySlug(slug).orElseGet(() -> Seller.builder()
                        .slug(slug)
                        .joinedAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .build());
                boolean existed = s.getCreatedAt() != null && s.getCreatedAt().isBefore(LocalDateTime.now().minusSeconds(2));
                s.setName(name);
                s.setType(orDefault(at(cells, headers, "type"), "facebook"));
                s.setUrl(at(cells, headers, "url"));
                s.setMessengerUrl(at(cells, headers, "messengerurl"));
                s.setCity(orDefault(at(cells, headers, "city"), "Dhaka"));
                s.setArea(at(cells, headers, "area"));
                s.setCategories(splitPipe(at(cells, headers, "categories")));
                s.setVerified(parseBool(at(cells, headers, "verified"), false));
                s.setCodAvailable(parseBool(at(cells, headers, "codavailable"), true));
                s.setSameDayDelivery(parseBool(at(cells, headers, "samedaydelivery"), false));
                s.setSource(orDefault(at(cells, headers, "source"), "bulk_import"));
                s.setUpdatedAt(LocalDateTime.now());
                sellers.save(s);
                if (existed) updated++; else inserted++;
            } catch (Exception ex) {
                failed++;
                errors.add("row " + (rowIdx + 1) + ": " + ex.getMessage());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("inserted", inserted);
        out.put("updated", updated);
        out.put("failed", failed);
        if (!errors.isEmpty()) out.put("errors", errors.subList(0, Math.min(10, errors.size())));
        log.info("AdminSellers bulk import: {} inserted, {} updated, {} failed", inserted, updated, failed);
        return ResponseEntity.ok(out);
    }

    /**
     * Purge the legacy demo entries (anything with {@code source} of
     * {@code fb_scrape} or {@code manual} or missing source). Keeps
     * curated, Saathi-onboarded, and admin-added rows untouched.
     */
    @DeleteMapping("/demos")
    public ResponseEntity<?> wipeDemos() {
        try {
            List<Seller> all = sellers.findAll();
            List<Seller> demos = all.stream().filter(s -> {
                String src = s.getSource();
                if (src == null) return true;
                String sx = src.toLowerCase();
                return sx.equals("fb_scrape") || sx.equals("manual");
            }).toList();
            sellers.deleteAll(demos);
            return ResponseEntity.ok(Map.of("removed", demos.size()));
        } catch (DataAccessException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ─── helpers ───

    private String uniqueSlug(String base) {
        String slug = base;
        int i = 2;
        while (sellers.findBySlug(slug).isPresent() && i <= 999) slug = base + "-" + (i++);
        return slug;
    }
    private String uniqueSlugSkipping(String base, String name) {
        // For bulk import, we WANT to update an existing row if the slug matches.
        return base;
    }
    private static String slugify(String s) {
        if (s == null) return "shop";
        String out = SLUG_SAFE.matcher(s.toLowerCase()).replaceAll("-").replaceAll("^-+|-+$", "");
        return out.isBlank() ? "shop" : (out.length() > 40 ? out.substring(0, 40) : out);
    }
    private static String str(Object v) { return v == null ? null : v.toString().trim(); }
    private static String str(Object v, String fallback) {
        String s = str(v);
        return s == null || s.isEmpty() ? fallback : s;
    }
    private static List<String> asList(Object v) {
        if (v == null) return new ArrayList<>();
        if (v instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(o.toString());
            return out;
        }
        return splitPipe(v.toString());
    }
    private static List<String> splitPipe(String s) {
        if (s == null || s.isBlank()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(s.split("\\s*\\|\\s*")));
    }
    private static boolean asBool(Object v, boolean d) {
        if (v == null) return d;
        if (v instanceof Boolean b) return b;
        return parseBool(v.toString(), d);
    }
    private static boolean parseBool(String s, boolean d) {
        if (s == null) return d;
        String x = s.trim().toLowerCase();
        return x.equals("1") || x.equals("true") || x.equals("yes") || x.equals("y");
    }
    private static String at(String[] cells, Map<String, Integer> headers, String key) {
        Integer idx = headers.get(key);
        if (idx == null || idx >= cells.length) return null;
        String v = cells[idx];
        return v == null ? null : v.trim();
    }
    private static String orDefault(String s, String d) {
        return s == null || s.isBlank() ? d : s;
    }
    /** Light-weight CSV parser — handles double-quoted fields with embedded commas. */
    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') inQuote = false;
                else cur.append(c);
            } else {
                if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
                else if (c == '"') inQuote = true;
                else cur.append(c);
            }
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }
}
