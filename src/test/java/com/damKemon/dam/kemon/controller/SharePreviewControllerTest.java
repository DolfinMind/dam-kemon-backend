package com.damKemon.dam.kemon.controller;

import com.damKemon.dam.kemon.model.Product;
import com.damKemon.dam.kemon.model.SitePrice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharePreviewControllerTest {

    private static final String BASE = "https://damkemon.com";

    private Product product() {
        Product p = new Product();
        p.setId("665f00");
        p.setSlug("samsung-galaxy-s24-ultra");
        p.setName("Samsung Galaxy S24 Ultra 12/256GB \"Titanium\" & more");
        p.setImageUrl("https://shop.example/img/s24.jpg");
        p.setLowestPrice(124999.0);
        p.setPrices(List.of(SitePrice.builder().price(124999.0).build(),
                SitePrice.builder().price(129999.0).build()));
        return p;
    }

    @Test
    void rendersEscapedOgTagsWithPriceAndImage() {
        String html = SharePreviewController.render(product(), BASE);
        assertTrue(html.contains("og:title\" content=\"Samsung Galaxy S24 Ultra 12/256GB &quot;Titanium&quot; &amp; more\""));
        assertTrue(html.contains("og:image\" content=\"https://shop.example/img/s24.jpg\""));
        assertTrue(html.contains("৳124,999"));
        assertTrue(html.contains("from 2 sellers"));
        assertTrue(html.contains("og:url\" content=\"" + BASE + "/product/samsung-galaxy-s24-ultra\""));
        assertFalse(html.contains("Titanium\" &"), "raw quote must not survive into markup");
    }

    @Test
    void missingImageFallsBackToBrandedOgPngAndMissingSlugToId() {
        Product p = product();
        p.setImageUrl(null);
        p.setSlug(null);
        String html = SharePreviewController.render(p, BASE);
        assertTrue(html.contains("og:image\" content=\"" + BASE + "/api/og/product/665f00.png\""));
        assertTrue(html.contains("og:image:width\" content=\"1200\""));
        assertTrue(html.contains("og:url\" content=\"" + BASE + "/product/665f00\""));
    }

    @Test
    void nullPriceStillRendersValidDescription() {
        Product p = product();
        p.setLowestPrice(null);
        String html = SharePreviewController.render(p, BASE);
        assertTrue(html.contains("og:description\" content=\"Compare prices from shops across Bangladesh"));
        assertFalse(html.contains("৳"));
    }
}
