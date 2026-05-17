package com.damKemon.dam.kemon.scraper;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapedReview {
    private String reviewerName;
    private Integer rating;
    private String title;
    private String content;
    private LocalDateTime date;
    private Boolean verified;
}
