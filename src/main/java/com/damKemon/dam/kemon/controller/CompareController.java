package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.dto.CompareResponse;
import com.damKemon.dam.kemon.service.CompareService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/compare")
public class CompareController {

    private final CompareService compareService;

    public CompareController(CompareService compareService) {
        this.compareService = compareService;
    }

    /**
     * GET /api/compare?ids=ID1,ID2,ID3
     * Up to 4 products at a time.
     */
    @GetMapping
    public CompareResponse compare(@RequestParam(value = "ids", required = false) String ids) {
        if (ids == null || ids.isBlank()) {
            return CompareResponse.builder().products(Collections.emptyList()).build();
        }
        List<String> idList = Arrays.stream(ids.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(4)
                .toList();
        return compareService.compare(idList);
    }
}
