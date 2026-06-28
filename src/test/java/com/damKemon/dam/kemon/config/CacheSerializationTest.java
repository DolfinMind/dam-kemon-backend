package com.damKemon.dam.kemon.config;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The Redis cache stores values as JSON with default typing. The classic failure
 * is LocalDateTime (and nested objects) not round-tripping — which would silently
 * 500 every cached endpoint once Redis is live. This pins that the actual
 * {@link CacheConfig} value serializer round-trips a Product. No Spring/DB needed.
 */
class CacheSerializationTest {

    @Test
    void roundTripsProductWithDatesAndNestedSellers() {
        SerializationPair<Object> pair =
                new CacheConfig().redisCacheConfiguration().getValueSerializationPair();

        Product p = Product.builder()
                .id("abc")
                .name("Apple iPhone 15 Pro Max")
                .lowestPrice(154000.0)
                .lastScraped(LocalDateTime.of(2026, 6, 28, 10, 30))
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").price(154000.0).build(),
                        SitePrice.builder().siteName("Ryans").price(155500.0).build()))
                .build();

        ByteBuffer buf = pair.write(p);
        Object back = pair.read(buf);

        assertInstanceOf(Product.class, back);
        Product r = (Product) back;
        assertEquals("Apple iPhone 15 Pro Max", r.getName());
        assertEquals(LocalDateTime.of(2026, 6, 28, 10, 30), r.getLastScraped());
        assertEquals(2, r.getPrices().size());
        assertEquals("Startech", r.getPrices().get(0).getSiteName());
    }
}
