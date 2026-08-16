package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.repository.ShopRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The show/hide switch: a blocked shop's offers must vanish from public
 * responses — fully-blocked products dropped, mixed products pruned via a
 * COPY (search candidates are shared in-memory index payloads).
 */
class ShopVisibilityServiceTest {

    private static Shop shop(String slug, String status) {
        Shop s = new Shop();
        s.setSlug(slug);
        s.setStatus(status);
        return s;
    }

    private static Product product(String name, String... siteSlugs) {
        Product p = new Product();
        p.setName(name);
        List<SitePrice> prices = new ArrayList<>();
        for (String slug : siteSlugs) {
            prices.add(SitePrice.builder().siteSlug(slug).siteName(slug).price(1000.0).build());
        }
        p.setPrices(prices);
        p.setLowestPrice(1000.0);
        return p;
    }

    private ShopVisibilityService svc(Shop... shops) {
        ShopRepository repo = mock(ShopRepository.class);
        when(repo.findAll()).thenReturn(List.of(shops));
        return new ShopVisibilityService(repo);
    }

    @Test
    void blockedShopOffersAreHiddenEverywhere() {
        ShopVisibilityService vis = svc(shop("startech", "active"), shop("daraz", "blocked"));

        Product onlyBlocked = product("Phone A", "daraz");
        Product mixed = product("Phone B", "daraz", "startech");
        Product clean = product("Phone C", "startech");

        List<Product> out = vis.filterForPublic(new ArrayList<>(List.of(onlyBlocked, mixed, clean)));

        assertEquals(2, out.size(), "fully-blocked product must be dropped");
        assertSame(clean, out.get(1), "untouched product keeps its identity (no needless copy)");
        Product prunedMixed = out.get(0);
        assertEquals(1, prunedMixed.getPrices().size(), "blocked offer pruned from mixed product");
        assertEquals("startech", prunedMixed.getPrices().get(0).getSiteSlug());
        assertEquals(2, mixed.getPrices().size(), "ORIGINAL must stay pristine — it is a shared index payload");

        assertTrue(vis.fullyHidden(onlyBlocked));
        assertFalse(vis.fullyHidden(mixed));

        Product detail = product("Phone D", "daraz", "startech");
        vis.stripInPlace(detail);
        assertEquals(1, detail.getPrices().size());
        Product gone = product("Phone E", "daraz");
        vis.stripInPlace(gone);
        assertTrue(gone.getPrices().isEmpty());
        assertNull(gone.getLowestPrice(), "aggregates nulled when every offer is hidden");
    }

    @Test
    void nothingHiddenIsAFastPassThrough() {
        ShopVisibilityService vis = svc(shop("startech", "active"));
        List<Product> in = List.of(product("Phone A", "startech"));
        assertSame(in, vis.filterForPublic(in), "no hidden shops → input list returned untouched");
    }
}
