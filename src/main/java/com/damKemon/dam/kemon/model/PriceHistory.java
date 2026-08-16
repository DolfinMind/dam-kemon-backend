package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "price_history")
@CompoundIndexes({
        @CompoundIndex(name = "history_product_recorded", def = "{'productId': 1, 'recordedAt': -1}"),
        @CompoundIndex(name = "history_recorded_product_site_price", def = "{'recordedAt': 1, 'productId': 1, 'siteName': 1, 'price': 1}")
})
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
