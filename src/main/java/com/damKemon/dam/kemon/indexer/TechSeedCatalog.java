package com.damKemon.dam.kemon.indexer;

import java.util.List;

/**
 * Curated set of specific, high-demand tech models BD shoppers actually compare
 * — the deterministic backbone of the {@link SellerDepthHarvester}'s cross-shop
 * fanout.
 *
 * <p>Unlike {@code SearchSeedCrawler}'s category seeds ("iphone", "laptop"),
 * which return a DIFFERENT product mix per shop, every entry here is a single
 * resolvable product. Firing the SAME canonical query ("Samsung Galaxy A55 5G")
 * at 40 shops is what makes the same product land on one matchKey with many
 * sellers — i.e. it manufactures the overlapping supply that lifts
 * sellers-per-product, the structural lever the catalog has been missing.
 *
 * <p>Kept intentionally specific (brand + model + key qualifier) so the harvester's
 * {@code sameProduct} gate can confirm the search hit before attaching an offer.
 * Edit freely as the market moves; order is roughly "most-compared first" so a
 * budgeted run covers the hottest models.
 */
final class TechSeedCatalog {

    private TechSeedCatalog() {}

    /** Categories a shop must carry for the fanout to bother searching it. */
    static final List<String> TECH_CATEGORIES = List.of(
            "smartphone", "laptop", "desktop", "tablet", "headphone",
            "smartwatch", "camera", "gaming", "tv", "accessory", "gadget",
            "power", "office", "monitor");

    /** Accessory/peripheral nouns that mean "this is a case/charger for X, not X". */
    static final java.util.Set<String> ACCESSORY_NOISE = java.util.Set.of(
            "case", "cover", "glass", "protector", "tempered", "skin", "pouch",
            "strap", "band", "cable", "charger", "adapter", "dock", "stand",
            "holder", "mount", "screen", "guard", "sticker", "replacement",
            "lens protector", "camera lens", "back cover");

    static final List<String> MODELS = List.of(
            // ── Phones: Apple ──
            "iPhone 16 Pro Max", "iPhone 16 Pro", "iPhone 16 Plus", "iPhone 16",
            "iPhone 15 Pro Max", "iPhone 15 Pro", "iPhone 15 Plus", "iPhone 15",
            "iPhone 14 Pro Max", "iPhone 14", "iPhone 13",
            // ── Phones: Samsung ──
            "Samsung Galaxy S24 Ultra", "Samsung Galaxy S24", "Samsung Galaxy S23 Ultra",
            "Samsung Galaxy Z Fold 6", "Samsung Galaxy Z Flip 6",
            "Samsung Galaxy A55 5G", "Samsung Galaxy A35 5G", "Samsung Galaxy A15",
            "Samsung Galaxy A05s", "Samsung Galaxy M35 5G",
            // ── Phones: Xiaomi / Redmi / POCO ──
            "Xiaomi 14", "Xiaomi Redmi Note 13 Pro", "Xiaomi Redmi Note 13",
            "Xiaomi Redmi 13C", "POCO X6 Pro", "POCO M6 Pro", "Xiaomi Redmi A3",
            // ── Phones: Realme / Oppo / Vivo / OnePlus ──
            "Realme 12 Pro Plus", "Realme 12", "Realme C67", "Realme Narzo 70",
            "Oppo Reno 11", "Oppo A79 5G", "Vivo V30", "Vivo Y28",
            "OnePlus 12R", "OnePlus Nord CE 4",
            // ── Phones: Infinix / Tecno / Walton / Symphony ──
            "Infinix Note 40 Pro", "Infinix Hot 40", "Infinix Zero 30",
            "Tecno Camon 30", "Tecno Spark 20 Pro", "Walton Primo GH11",
            "Symphony Innova 50",
            // ── Laptops ──
            "MacBook Air M3 13", "MacBook Air M2", "MacBook Pro M3 14",
            "Asus Vivobook 15", "Asus ROG Strix G16", "Asus TUF Gaming F15",
            "Acer Aspire 5", "Acer Nitro 5", "HP Pavilion 15", "HP Victus 15",
            "Dell Inspiron 15", "Dell XPS 13", "Lenovo IdeaPad Slim 3",
            "Lenovo LOQ 15", "Lenovo ThinkPad E14", "MSI Modern 14",
            // ── Tablets ──
            "iPad 10th Gen", "iPad Air M2", "iPad Pro M4 11",
            "Samsung Galaxy Tab S9 FE", "Samsung Galaxy Tab A9 Plus",
            "Xiaomi Redmi Pad SE",
            // ── Smartwatches / wearables ──
            "Apple Watch Series 10", "Apple Watch SE 2", "Samsung Galaxy Watch 7",
            "Xiaomi Smart Band 9", "Amazfit GTR 4", "Amazfit Bip 5",
            "Realme Watch 3", "Haylou Solar Plus",
            // ── Earbuds / audio ──
            "Apple AirPods Pro 2", "Apple AirPods 4", "Samsung Galaxy Buds FE",
            "Xiaomi Redmi Buds 5", "JBL Tune 230NC TWS", "Sony WF-C710N",
            "Sony WH-CH520", "Soundcore Liberty 4 NC", "Baseus Bowie",
            "JBL Go 4", "Marshall Emberton 2",
            // ── Monitors / displays ──
            "Samsung 24 inch Monitor", "LG UltraGear 27", "Asus ProArt Monitor",
            "Dell 24 Monitor P2422H", "MSI G244F",
            // ── PC components ──
            "RTX 4060 Graphics Card", "RTX 4070 Super", "Ryzen 5 7600",
            "Intel Core i5 13400F", "Samsung 990 Pro 1TB SSD",
            "WD Blue SN580 1TB", "Corsair Vengeance 16GB DDR5",
            "Gigabyte B650 Motherboard",
            // ── Gaming ──
            "PlayStation 5 Slim", "PlayStation 5", "Xbox Series S",
            "DualSense Controller", "Logitech G502",
            // ── Accessories / gadgets / power ──
            "Anker PowerCore 20000", "Baseus 65W Charger", "Anker 313 Charger",
            "TP-Link Archer C6 Router", "TP-Link Tapo C200",
            "Xiaomi Power Bank 3", "Logitech MX Master 3S",
            "Samsung 65 inch Crystal UHD TV", "Walton 43 inch Smart TV",
            "Sony Bravia 55 inch");
}
