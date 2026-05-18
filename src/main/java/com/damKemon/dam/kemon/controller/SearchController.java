package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.service.CatalogSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final CatalogSearchService catalog;

    public SearchController(CatalogSearchService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public ResponseEntity<SearchResponse> search(@RequestParam("q") String query) {
        return ResponseEntity.ok(catalog.search(query));
    }

    @GetMapping("/suggest")
    public ResponseEntity<List<Map<String, Object>>> suggest(
            @RequestParam("q") String prefix,
            @RequestParam(value = "limit", defaultValue = "8") int limit) {
        return ResponseEntity.ok(catalog.autocomplete(prefix, limit));
    }
}
