package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.Review;
import com.damKemon.dam.kemon.model.SitePrice;
import com.damKemon.dam.kemon.service.ProductService;
import com.damKemon.dam.kemon.service.ShowcaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The anonymous teaser gate: capped offer list, masked cheapest seller,
 * member-only history, review teaser. Signed-in state = the "authUserId"
 * request attribute stamped by JwtAuthFilter.
 */
class ProductControllerGateTest {

    private ProductController controller(Product p, List<Review> reviews) {
        ProductService svc = mock(ProductService.class);
        when(svc.findByIdOrSlug("p1")).thenReturn(Optional.ofNullable(p));
        when(svc.getReviews("p1")).thenReturn(reviews);
        when(svc.getPriceHistory("p1")).thenReturn(List.of());
        when(svc.getDailyPriceSeries(eq("p1"), anyInt())).thenReturn(List.of());
        return new ProductController(svc, mock(ShowcaseService.class));
    }

    private static HttpServletRequest anonReq() {
        return mock(HttpServletRequest.class); // getAttribute -> null
    }

    private static HttpServletRequest userReq() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("authUserId")).thenReturn("u1");
        return req;
    }

    /** 6 distinct sellers plus one duplicate slug, cheapest = 80. */
    private static Product sixSellerProduct() {
        List<SitePrice> sp = new ArrayList<>();
        double[] prices = {100, 80, 95, 120, 110, 130};
        for (int i = 0; i < prices.length; i++) {
            sp.add(SitePrice.builder().siteName("Shop " + i).siteSlug("shop-" + i)
                    .productUrl("https://shop" + i + ".bd/x").price(prices[i]).build());
        }
        sp.add(SitePrice.builder().siteName("Shop 1").siteSlug("shop-1")
                .productUrl("https://shop1.bd/dup").price(85.0).build()); // duplicate seller
        Product p = new Product();
        p.setId("p1");
        p.setName("Test phone");
        p.setPrices(sp);
        return p;
    }

    @Test
    void anonymousGetsFourOffersWithCheapestMasked() {
        var resp = controller(sixSellerProduct(), List.of()).getProductById("p1", anonReq());

        Product body = resp.getBody();
        assertNotNull(body);
        assertEquals(4, body.getPrices().size(), "anon sees only the 4 cheapest sellers");
        assertEquals(6, body.getTotalSellerCount(), "true distinct-seller count still travels");

        SitePrice best = body.getPrices().get(0);
        assertEquals(Boolean.TRUE, best.getLocked());
        assertEquals(80.0, best.getPrice(), "the TRUE lowest price stays visible");
        assertNull(best.getSiteName(), "shop identity is stripped");
        assertNull(best.getSiteSlug());
        assertNull(best.getProductUrl(), "no buy link on the locked offer");

        assertNotNull(body.getPrices().get(1).getSiteName(), "runner-up offers keep identity");
    }

    @Test
    void signedInGetsEverything() {
        var resp = controller(sixSellerProduct(), List.of()).getProductById("p1", userReq());

        Product body = resp.getBody();
        assertNotNull(body);
        assertEquals(7, body.getPrices().size(), "full raw list, untouched");
        assertNull(body.getTotalSellerCount());
        assertTrue(body.getPrices().stream().allMatch(sp -> sp.getLocked() == null));
    }

    @Test
    void historyIsMemberOnly() {
        ProductController c = controller(sixSellerProduct(), List.of());
        assertEquals(401, c.getPriceHistory("p1", anonReq()).getStatusCode().value());
        assertEquals(401, c.getDailyPriceHistory("p1", 30, anonReq()).getStatusCode().value());
        assertEquals(200, c.getPriceHistory("p1", userReq()).getStatusCode().value());
        assertEquals(200, c.getDailyPriceHistory("p1", 30, userReq()).getStatusCode().value());
    }

    @Test
    void anonymousReviewsCappedAtThreeWithTrueCountHeader() {
        List<Review> five = List.of(new Review(), new Review(), new Review(), new Review(), new Review());
        ProductController c = controller(sixSellerProduct(), five);

        var anon = c.getReviews("p1", anonReq());
        assertEquals(3, anon.getBody().size());
        assertEquals("5", anon.getHeaders().getFirst("X-Total-Reviews"));

        var member = c.getReviews("p1", userReq());
        assertEquals(5, member.getBody().size());
        assertEquals("5", member.getHeaders().getFirst("X-Total-Reviews"));
    }
}
