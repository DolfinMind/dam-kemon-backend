package com.damKemon.dam.kemon.indexer;

import com.damKemon.dam.kemon.model.Shop;
import com.damKemon.dam.kemon.scraper.BrowserFetcher;
import com.damKemon.dam.kemon.scraper.ScrapedProduct;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "read the page like a shopper" harvester — a platform-agnostic last resort
 * for shops that render their catalog in JavaScript, sit behind a bot wall, or
 * expose no standard feed/json-ld.
 *
 * <p>Instead of hunting for a JSON API ({@link ApiSniffer}) or structured data
 * (the extractors), it renders the shop's listing pages in a REAL browser and
 * reads the visible PRODUCT CARDS straight from the rendered DOM: find every
 * element that shows a Taka price, climb to the smallest ancestor that also
 * holds a product link, and pull name + price + image + url from it. Almost
 * every storefront — Shopify, WooCommerce, Magento, OpenCart, custom SPA — lays
 * its catalog out as a grid of such cards, so this works where per-platform code
 * does not. That is what gets the long tail of 0-product shops to start showing.
 *
 * <p>Rendering in Chromium also runs the page's own JavaScript (so SPA prices
 * land in the DOM) and presents a genuine browser fingerprint (so many bot/geo
 * blocks that reject curl let it through) — when even this can't reach a shop,
 * harvest() returns empty and we know it truly needs a proxy.
 */
@Service
public class DomCardHarvester {

    private static final Logger log = LoggerFactory.getLogger(DomCardHarvester.class);

    /** A Bangladeshi-Taka price in visible text: ৳1,234 · Tk 1234 · BDT 1,234.50 */
    private static final Pattern PRICE = Pattern.compile(
            "(?:৳|tk\\.?|bdt|taka)\\s*([0-9][0-9,]{1,9}(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);

    private static final Pattern LISTING = Pattern.compile(
            "(category|categories|collection|collections|/shop|product-category|/brand|/products|catalog|route=product/category)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JUNK = Pattern.compile(
            "(login|register|signin|signup|cart|checkout|account|wishlist|compare|policy|terms|privacy"
          + "|about|contact|blog|news|faq|career|track|return|refund|/tag/|/author/)",
            Pattern.CASE_INSENSITIVE);

    @Value("${domcard.enabled:true}")         private boolean enabled;
    @Value("${domcard.max-listing-pages:5}")  private int maxListingPages;
    @Value("${domcard.max-products:400}")     private int maxProducts;
    @Value("${domcard.min-products:2}")       private int minProducts;

    private final BrowserFetcher browser;

    public DomCardHarvester(BrowserFetcher browser) { this.browser = browser; }

    public boolean isEnabled() { return enabled && browser.isAvailable(); }

    public List<ScrapedProduct> harvest(Shop shop) {
        if (!isEnabled() || shop == null || shop.getBaseUrl() == null || shop.getBaseUrl().isBlank())
            return List.of();
        String base = stripSlash(shop.getBaseUrl());
        String host = hostOf(base);
        if (host == null) return List.of();

        LinkedHashMap<String, ScrapedProduct> byUrl = new LinkedHashMap<>();

        // 1. Render the homepage in a real browser (runs the SPA's JS; bypasses
        //    many bot/geo blocks). If even this returns nothing, the shop is
        //    genuinely unreachable from here and needs a proxy.
        Document home = browser.fetchDocument(base);
        if (home == null) {
            log.info("DomCard: shop '{}' did not render — still blocked/unreachable from here", shop.getSlug());
            return List.of();
        }
        extractCards(home, base, host, byUrl);

        // 2. Render a few listing pages for fuller product grids.
        int rendered = 0;
        for (String url : listingUrls(home, base, host)) {
            if (rendered >= maxListingPages || byUrl.size() >= maxProducts) break;
            rendered++;
            Document d = browser.fetchDocument(url);
            if (d != null) extractCards(d, base, host, byUrl);
        }

        List<ScrapedProduct> out = new ArrayList<>(byUrl.values());
        if (out.size() < minProducts) {
            if (!out.isEmpty())
                log.info("DomCard: shop '{}' → only {} card(s)", shop.getSlug(), out.size());
            return out;
        }
        log.info("DomCard: shop '{}' → {} products read from rendered DOM cards", shop.getSlug(), out.size());
        return out;
    }

    /** Core: find price-bearing elements, climb to the product card, read fields. */
    private void extractCards(Document doc, String base, String host, Map<String, ScrapedProduct> out) {
        for (Element priceEl : doc.getAllElements()) {
            if (out.size() >= maxProducts) break;
            String own = priceEl.ownText();
            if (own.isEmpty() || !PRICE.matcher(own).find()) continue;

            Element card = climbToCard(priceEl, host);
            if (card == null) continue;
            Element link = productLink(card, host);
            if (link == null) continue;
            String url = stripFragment(link.absUrl("href"));
            if (url.isBlank() || out.containsKey(url)) continue;

            Double[] pp = priceInCard(card);
            if (pp[0] == null || pp[0] < 10) continue;
            String name = name(card, link);
            if (name == null || name.length() < 3) continue;

            out.put(url, ScrapedProduct.builder()
                    .name(name)
                    .price(pp[0])
                    .originalPrice(pp[1] != null && pp[1] > pp[0] ? pp[1] : null)
                    .productUrl(url)
                    .imageUrl(image(card, base))
                    .inStock(true)
                    .build());
        }
    }

    /** Smallest ancestor (≤6 levels up) of the price that also holds a same-host
     *  product link — that is the product card. Bounded so we never grab the
     *  whole grid as one card. */
    private Element climbToCard(Element priceEl, String host) {
        Element e = priceEl;
        for (int i = 0; i < 6 && e != null; i++) {
            if (productLink(e, host) != null) return e;
            e = e.parent();
        }
        return null;
    }

    private Element productLink(Element card, String host) {
        for (Element a : card.select("a[href]")) {
            String href = a.absUrl("href");
            if (href.isBlank()) continue;
            if (!sameHost(hostOf(href), host)) continue;
            String path = pathOf(href);
            if (path == null || path.length() < 2) continue;       // skip homepage links
            if (JUNK.matcher(href).find()) continue;
            return a;
        }
        return null;
    }

    private String name(Element card, Element link) {
        String n = clean(link.text());
        if (n.length() >= 3) return cap(n);
        n = clean(link.attr("title"));
        if (n.length() >= 3) return cap(n);
        Element t = card.selectFirst("h1,h2,h3,h4,h5,[class*=title],[class*=name],[itemprop=name]");
        if (t != null) { n = clean(t.text()); if (n.length() >= 3) return cap(n); }
        Element img = card.selectFirst("img[alt]");
        if (img != null) { n = clean(img.attr("alt")); if (n.length() >= 3) return cap(n); }
        return null;
    }

    /** [0] = selling price, [1] = original (struck-through) if any. */
    private Double[] priceInCard(Element card) {
        Double current = null, original = null;
        Element cur = card.selectFirst("[class*=price-new],[class*=special],[class*=sale-price],[class*=current-price],ins");
        if (cur != null) current = firstPrice(cur.text());
        for (Element o : card.select("del,s,strike,[class*=price-old],[class*=old-price],[class*=regular-price],[class*=mrp],[class*=was]")) {
            Double v = firstPrice(o.text());
            if (v != null) original = original == null ? v : Math.max(original, v);
        }
        if (current == null) {
            double best = -1;
            for (Element el : card.getAllElements()) {
                String own = el.ownText();
                if (own.isEmpty() || isStruck(el)) continue;
                Double v = firstPrice(own);
                if (v != null && v > best) best = v;
            }
            if (best >= 0) current = best;
        }
        if (current != null && original != null && original <= current) original = null;
        return new Double[]{ current, original };
    }

    private boolean isStruck(Element el) {
        for (Element p = el; p != null; p = p.parent()) {
            String tag = p.tagName();
            if (tag.equals("del") || tag.equals("s") || tag.equals("strike")) return true;
            String c = p.className().toLowerCase();
            if (c.contains("old") || c.contains("regular") || c.contains("mrp") || c.contains("was")) return true;
        }
        return false;
    }

    private String image(Element card, String base) {
        Element img = card.selectFirst("img");
        if (img == null) return null;
        for (String attr : new String[]{"src", "data-src", "data-original", "data-lazy-src", "data-srcset"}) {
            String v = img.attr(attr).trim();
            if (v.isBlank() || v.startsWith("data:")) continue;
            if (attr.equals("data-srcset")) v = v.split("\\s+")[0];
            try { return URI.create(base).resolve(v).toString(); } catch (Exception e) { return v; }
        }
        return null;
    }

    private List<String> listingUrls(Document home, String base, String host) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (Element a : home.select("a[href]")) {
            if (urls.size() >= maxListingPages * 2) break;
            String href = stripFragment(a.absUrl("href"));
            if (!sameHost(hostOf(href), host)) continue;
            String path = pathOf(href);
            if (path == null || JUNK.matcher(href).find()) continue;
            if (LISTING.matcher(href).find()) urls.add(href);
        }
        return new ArrayList<>(urls);
    }

    // ── helpers ──
    private static Double firstPrice(String text) {
        if (text == null) return null;
        Matcher m = PRICE.matcher(text);
        if (m.find()) { try { return Double.parseDouble(m.group(1).replace(",", "")); } catch (Exception e) { return null; } }
        return null;
    }
    private static String clean(String s) { return s == null ? "" : s.replaceAll("\\s+", " ").trim(); }
    private static String cap(String s) { return s.length() > 200 ? s.substring(0, 200) : s; }
    private static String stripSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String stripFragment(String s) { int h = s.indexOf('#'); return h < 0 ? s : s.substring(0, h); }
    private static String hostOf(String url) { try { return new URI(url).getHost(); } catch (Exception e) { return null; } }
    private static String pathOf(String url) { try { return new URI(url).getPath(); } catch (Exception e) { return null; } }
    private static boolean sameHost(String a, String b) {
        return a != null && b != null
                && a.replaceFirst("^www\\.", "").equalsIgnoreCase(b.replaceFirst("^www\\.", ""));
    }
}
