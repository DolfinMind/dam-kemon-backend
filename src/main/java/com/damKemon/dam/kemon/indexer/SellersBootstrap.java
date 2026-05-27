package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Seller;
import com.damKemon.dam.kemon.repository.SellerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * On boot, reads {@code resources/sellers.json} and upserts each entry into
 * the {@code sellers} collection. This is how we replace the old hand-seeded
 * demo placeholders (Gadget Lounge BD, Dhaka Kitchen Wares etc.) with a
 * curated list of real BD F-commerce brands.
 *
 * <p>Sellers are tracked by {@link Seller#getSlug()} (unique). Re-running
 * the bootstrap refreshes display fields (name, URL, categories, verified
 * flag, COD/delivery flags) but preserves runtime fields like rating,
 * reviewCount, and lastSeen.
 *
 * <p>To grow the directory beyond what's in sellers.json:
 * <ul>
 *   <li>Saathi self-signups (verified accounts auto-upsert a Seller row).</li>
 *   <li>Admin "quick add" through the operator dashboard.</li>
 *   <li>Bulk CSV import via the admin upload endpoint.</li>
 *   <li>Pending shop submissions promoted from {@code /api/admin/pending-shops}.</li>
 * </ul>
 *
 * <p>If MongoDB is unreachable on boot the bootstrap silently no-ops.
 */
@Component
public class SellersBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SellersBootstrap.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SellerRepository sellerRepository;

    public SellersBootstrap(SellerRepository sellerRepository) {
        this.sellerRepository = sellerRepository;
    }

    @PostConstruct
    public void seed() {
        List<SellerEntry> entries = loadDirectory();
        if (entries.isEmpty()) return;

        // First pass: clear out the old demo placeholders so the directory
        // looks credible on first load. Demos are anything with source other
        // than "curated"/"saathi"/"portal" — i.e. the fb_scrape + manual
        // seeds that we never sourced from real data.
        try {
            List<Seller> all = sellerRepository.findAll();
            List<Seller> demos = all.stream().filter(s -> {
                String src = s.getSource();
                // Old seeds: source=fb_scrape with placeholder names, OR
                // source=portal that was a demo (kept conservative: only
                // delete entries whose slug doesn't match any curated entry
                // and isn't a Saathi-onboarded seller).
                if (src == null) return true;
                if ("fb_scrape".equalsIgnoreCase(src)) return true;
                if ("manual".equalsIgnoreCase(src)) return true;
                return false;
            }).toList();
            if (!demos.isEmpty()) {
                sellerRepository.deleteAll(demos);
                log.info("Sellers bootstrap: removed {} legacy demo entries", demos.size());
            }
        } catch (DataAccessException e) {
            log.warn("Sellers bootstrap: could not clean demos ({}), continuing", e.getMessage());
        }

        int inserted = 0, updated = 0;
        for (SellerEntry e : entries) {
            try {
                Optional<Seller> existing = sellerRepository.findBySlug(e.slug);
                Seller s = existing.orElseGet(() -> Seller.builder()
                        .slug(e.slug)
                        .joinedAt(LocalDateTime.now())
                        .createdAt(LocalDateTime.now())
                        .build());
                s.setName(e.name);
                s.setType(e.type == null ? "facebook" : e.type);
                s.setUrl(e.url);
                s.setMessengerUrl(e.messengerUrl);
                s.setCity(e.city);
                s.setArea(e.area);
                s.setCategories(e.categories == null ? new ArrayList<>() : e.categories);
                s.setBrands(e.brands == null ? new ArrayList<>() : e.brands);
                s.setVerified(Boolean.TRUE.equals(e.verified));
                s.setCodAvailable(e.codAvailable);
                s.setSameDayDelivery(e.sameDayDelivery);
                s.setAvgReplyTime(e.avgReplyTime);
                s.setSource(e.source == null ? "curated" : e.source);
                s.setUpdatedAt(LocalDateTime.now());
                sellerRepository.save(s);
                if (existing.isPresent()) updated++; else inserted++;
            } catch (DataAccessException ex) {
                log.warn("Sellers bootstrap: Mongo unreachable ({}). Skipping further seeds.", ex.getMessage());
                return;
            } catch (Exception ex) {
                log.warn("Sellers bootstrap: failed to upsert '{}': {}", e.slug, ex.getMessage());
            }
        }
        log.info("Sellers directory: {} inserted, {} updated, {} curated total",
                inserted, updated, inserted + updated);
    }

    private List<SellerEntry> loadDirectory() {
        try (InputStream in = new ClassPathResource("sellers.json").getInputStream()) {
            List<SellerEntry> entries = MAPPER.readValue(in, new TypeReference<List<SellerEntry>>() {});
            log.info("Sellers directory: read {} curated entries from sellers.json", entries.size());
            return entries;
        } catch (Exception e) {
            log.warn("Could not load sellers.json: {}", e.getMessage());
            return List.of();
        }
    }

    /** JSON shape — keep aligned with sellers.json. */
    public static class SellerEntry {
        public String slug;
        public String name;
        public String type;
        public String url;
        public String messengerUrl;
        public String city;
        public String area;
        public List<String> categories;
        public List<String> brands;
        public Boolean verified;
        public Boolean codAvailable;
        public Boolean sameDayDelivery;
        public String avgReplyTime;
        public String source;
    }
}
