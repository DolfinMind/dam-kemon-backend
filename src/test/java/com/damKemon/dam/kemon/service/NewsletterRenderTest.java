package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.config.AppRole;
import com.damKemon.dam.kemon.repository.NewsletterSubscriberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Guards the weekly-newsletter rendering: product names are HTML-escaped (they
 * come from scraped titles — untrusted), every email carries an unsubscribe link,
 * and the copy never reveals how prices are gathered. Also dumps the HTML to
 * build/newsletter-preview.html for a visual check.
 */
class NewsletterRenderTest {

    private NewsletterService service() {
        NewsletterService s = new NewsletterService(
                mock(NewsletterSubscriberRepository.class),
                mock(HotDropsService.class),
                mock(ResendService.class),
                new AppRole("web"));
        ReflectionTestUtils.setField(s, "siteUrl", "https://damkemon.com");
        return s;
    }

    private static Map<String, Object> drop(String id, String name, String img, double cur, double peak, double pct, int sellers) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("name", name); m.put("imageUrl", img);
        m.put("currentPrice", cur); m.put("peakPrice", peak); m.put("dropPct", pct); m.put("sellerCount", sellers);
        return m;
    }

    @Test
    void rendersSafeAttractiveDigest() throws Exception {
        List<Map<String, Object>> picks = List.of(
                drop("p1", "Sony PS5 Gaming Console with Wireless Controller",
                        "https://img/ps5.jpg", 54990, 69990, 21.4, 4),
                drop("p2", "Apple iPhone 16 Plus 128GB <script>alert(1)</script>",
                        "https://img/ip16.jpg", 119900, 134900, 11.1, 6),
                drop("p3", "Samsung Galaxy S24 Ultra 256GB", "", 145000, 158000, 8.2, 3));

        NewsletterService s = service();
        String html = s.buildHtml(picks, "shopper@example.com");

        // dump for visual inspection
        Path out = Path.of("build", "newsletter-preview.html");
        Files.createDirectories(out.getParent());
        Files.writeString(out, html);

        // untrusted product names must be escaped — no raw <script> in the email
        assertFalse(html.contains("<script>alert(1)</script>"), "product name must be HTML-escaped");
        assertTrue(html.contains("&lt;script&gt;"), "escaped form should be present");
        // every email must offer one-click unsubscribe
        assertTrue(html.contains("/api/newsletter/unsubscribe?email=shopper%40example.com"), "unsubscribe link missing");
        // prices + a product link present
        assertTrue(html.contains("৳54,990"), "formatted price missing");
        assertTrue(html.contains("https://damkemon.com/product/p1"), "product link missing");
        // never reveal how prices are gathered (distinctive terms only — "bot"/"index"
        // would false-match Roboto / margin-bottom / z-index in the markup)
        String lower = html.toLowerCase();
        for (String banned : new String[]{"scrap", "crawl", "harvest", "spider"}) {
            assertFalse(lower.contains(banned), "copy must not reveal acquisition method: '" + banned + "'");
        }

        // subject is attractive and quantified
        String subject = s.buildSubject(picks);
        assertTrue(subject.contains("21%") || subject.toLowerCase().contains("best"), "subject should headline the saving: " + subject);
    }
}
