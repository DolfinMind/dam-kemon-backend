package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.scraper.ScraperEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sites")
public class SiteController {

    private final ScraperEngine scraperEngine;

    public SiteController(ScraperEngine scraperEngine) {
        this.scraperEngine = scraperEngine;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getAllSites() {
        return ResponseEntity.ok(scraperEngine.getAvailableSites());
    }
}
