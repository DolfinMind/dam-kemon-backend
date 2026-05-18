package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.scraper.ExtractorRegistry;
import com.damKemon.dam.kemon.scraper.ProductExtractor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final ExtractorRegistry extractors;

    public SiteController(ExtractorRegistry extractors) {
        this.extractors = extractors;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllSites() {
        List<Map<String, Object>> sites = extractors.all().stream()
                .map(this::toJson)
                .collect(Collectors.toList());
        return ResponseEntity.ok(sites);
    }

    private Map<String, Object> toJson(ProductExtractor e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", e.getSiteName());
        m.put("slug", e.getSiteSlug());
        m.put("kind", "generic".equals(e.getSiteSlug()) ? "generic" : "site-specific");
        return m;
    }
}
