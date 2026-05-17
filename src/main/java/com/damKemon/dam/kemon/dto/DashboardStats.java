package com.damKemon.dam.kemon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardStats {
    private Long totalProducts;
    private Integer totalSites;
    private Long totalReviews;
    private Long totalPricePoints;
    private List<String> recentSearches;
    private List<SiteStat> siteStats;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SiteStat {
        private String siteName;
        private Long productCount;
        private String status;
    }
}
