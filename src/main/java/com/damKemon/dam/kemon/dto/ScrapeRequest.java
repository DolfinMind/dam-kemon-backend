package com.damKemon.dam.kemon.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScrapeRequest {
    private String query;
    private List<String> sites;
}
