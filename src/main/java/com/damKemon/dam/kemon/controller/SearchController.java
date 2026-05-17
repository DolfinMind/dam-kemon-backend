package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.SearchResponse;
import com.damKemon.dam.kemon.service.SearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    public ResponseEntity<SearchResponse> search(
            @RequestParam("q") String query,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        SearchResponse response = searchService.search(query);
        return ResponseEntity.ok(response);
    }
}
