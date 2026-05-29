package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backs the seasonal "World Cup 2026" homepage rail. Surfaces fan merchandise
 * (team jerseys, flags, supporter gear) the catalog already holds, regardless
 * of which category each item landed in.
 *
 * <p>Matched by product name so a jersey filed under Fashion and a flag filed
 * under General both show up. {@code \\bflag\\b} keeps phone "flagship" listings
 * out. Cheap enough to run on demand — one indexed regex pass over names.
 */
@Service
public class WorldCupService {

    private static final Logger log = LoggerFactory.getLogger(WorldCupService.class);

    /** Fan-merch name signals. Word-boundary on "flag" avoids "flagship". */
    private static final String WC_REGEX = "jersey|world cup|fifa|\\bflag\\b|bunting|supporter|fan band";

    private final ProductRepository productRepository;

    public WorldCupService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<Map<String, Object>> get(int limit) {
        try {
            List<Product> matches = productRepository.findByNamePrefix(WC_REGEX, PageRequest.of(0, 200));
            List<Map<String, Object>> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Product p : matches) {
                if (p.getId() == null || p.getLowestPrice() == null || p.getLowestPrice() <= 0) continue;
                if (p.getImageUrl() == null || p.getImageUrl().isBlank()) continue; // rail needs a thumbnail
                if (!seen.add(p.getId())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", p.getId());
                row.put("slug", p.getSlug());
                row.put("name", p.getName());
                row.put("imageUrl", p.getImageUrl());
                row.put("category", p.getCategory());
                row.put("lowestPrice", p.getLowestPrice());
                row.put("sellerCount", p.getPrices() == null ? 0 : p.getPrices().size());
                out.add(row);
            }
            // Most-compared first (more sellers = more useful), then cheapest.
            out.sort((a, b) -> {
                int s = (Integer) b.get("sellerCount") - (Integer) a.get("sellerCount");
                if (s != 0) return s;
                return Double.compare((Double) a.get("lowestPrice"), (Double) b.get("lowestPrice"));
            });
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        } catch (DataAccessException e) {
            log.warn("WorldCup: query failed ({})", e.getMessage());
            return List.of();
        }
    }
}
