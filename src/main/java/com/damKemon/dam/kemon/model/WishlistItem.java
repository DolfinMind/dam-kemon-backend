package com.damKemon.dam.kemon.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "wishlist")
@CompoundIndex(name = "user_product_unique", def = "{'userId': 1, 'productId': 1}", unique = true)
public class WishlistItem {

    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private String productId;

    private LocalDateTime addedAt;

    /** Price at the moment the item was added — used to compute "you saved X". */
    private Double priceAtAdd;
}
