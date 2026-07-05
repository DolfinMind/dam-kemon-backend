package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The rail must price a product from its cheapest VISIBLE offer — a hidden
 *  shop's junk parse (৳55 on a ৳56,500 laptop) can never headline the homepage. */
class HotDropsVisibleLowestTest {

    private static SitePrice offer(String slug, Double price) {
        return SitePrice.builder().siteSlug(slug).price(price).build();
    }

    private static Product with(SitePrice... offers) {
        Product p = new Product();
        p.setPrices(List.of(offers));
        return p;
    }

    @Test
    void hiddenShopOffersNeverPriceTheRail() {
        Set<String> hidden = Set.of("creatus");
        // junk 55 from hidden shop + real 56500 visible → rail sees 56500
        assertEquals(56500.0, HotDropsService.visibleLowest(
                with(offer("creatus", 55.0), offer("startech", 56500.0)), hidden));
        // only-hidden product prices to null → sits the rail out
        assertNull(HotDropsService.visibleLowest(with(offer("creatus", 55.0)), hidden));
        // nothing hidden → plain cheapest
        assertEquals(55.0, HotDropsService.visibleLowest(
                with(offer("creatus", 55.0), offer("startech", 56500.0)), Set.of()));
        // no offer rows → falls back to the stored aggregate
        Product bare = new Product();
        bare.setLowestPrice(1200.0);
        assertEquals(1200.0, HotDropsService.visibleLowest(bare, hidden));
    }
}
