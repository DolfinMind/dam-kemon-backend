package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.service.CrawlerControlService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/crawler")
public class AdminCrawlerController {

    private final CrawlerControlService crawler;

    public AdminCrawlerController(CrawlerControlService crawler) {
        this.crawler = crawler;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return response(crawler.health());
    }

    @GetMapping("/status")
    public ResponseEntity<String> status() {
        return response(crawler.status());
    }

    @GetMapping("/logs")
    public ResponseEntity<String> logs(@RequestParam(defaultValue = "300") int lines) {
        return response(crawler.logs(lines));
    }

    @PostMapping("/actions/{action}")
    public ResponseEntity<String> action(@PathVariable String action) {
        return response(crawler.action(action));
    }

    private ResponseEntity<String> response(CrawlerControlService.RemoteResponse remote) {
        return ResponseEntity.status(remote.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(remote.body());
    }
}
