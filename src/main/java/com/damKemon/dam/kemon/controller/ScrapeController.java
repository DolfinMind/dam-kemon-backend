package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.ScrapeRequest;
import com.damKemon.dam.kemon.model.ScrapingJob;
import com.damKemon.dam.kemon.service.ScrapingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/scrape")
public class ScrapeController {

    private final ScrapingService scrapingService;

    public ScrapeController(ScrapingService scrapingService) {
        this.scrapingService = scrapingService;
    }

    @PostMapping
    public ResponseEntity<ScrapingJob> triggerScrape(@RequestBody ScrapeRequest request) {
        ScrapingJob job = scrapingService.triggerScrape(request.getQuery(), request.getSites());
        return ResponseEntity.ok(job);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ScrapingJob> getJobStatus(@PathVariable String jobId) {
        return scrapingService.getJob(jobId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
