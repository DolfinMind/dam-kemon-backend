package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "scraping_jobs")
public class ScrapingJob {
    @Id
    private String id;
    private String query;
    @Builder.Default
    private String status = "PENDING";
    @Builder.Default
    private List<String> sitesRequested = new ArrayList<>();
    @Builder.Default
    private List<String> sitesCompleted = new ArrayList<>();
    private Integer totalProducts;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
}
