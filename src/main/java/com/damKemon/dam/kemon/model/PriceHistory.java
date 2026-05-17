package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "price_history")
public class PriceHistory {
    @Id
    private String id;
    private String productId;
    private String siteName;
    private Double price;
    @Builder.Default
    private String currency = "BDT";
    private LocalDateTime recordedAt;
}
