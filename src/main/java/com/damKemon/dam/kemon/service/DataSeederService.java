package com.damKemon.dam.kemon.service;

import com.damKemon.dam.kemon.model.*;
import com.damKemon.dam.kemon.repository.PriceHistoryRepository;
import com.damKemon.dam.kemon.repository.ProductRepository;
import com.damKemon.dam.kemon.repository.ReviewRepository;
import com.damKemon.dam.kemon.repository.SellerRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Component
public class DataSeederService {

    private static final Logger log = LoggerFactory.getLogger(DataSeederService.class);

    private final ProductRepository productRepository;
    private final ReviewRepository reviewRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final SellerRepository sellerRepository;

    public DataSeederService(ProductRepository productRepository,
                             ReviewRepository reviewRepository,
                             PriceHistoryRepository priceHistoryRepository,
                             SellerRepository sellerRepository) {
        this.productRepository = productRepository;
        this.reviewRepository = reviewRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.sellerRepository = sellerRepository;
    }

    @PostConstruct
    public void seed() {
        try {
            if (productRepository.count() == 0) {
                log.info("Seeding demo products...");
                List<Product> products = createDemoProducts();
                List<Product> savedProducts = productRepository.saveAll(products);
                log.info("Seeded {} products", savedProducts.size());

                List<Review> reviews = createDemoReviews(savedProducts);
                reviewRepository.saveAll(reviews);
                log.info("Seeded {} reviews", reviews.size());

                List<PriceHistory> history = createDemoPriceHistory(savedProducts);
                priceHistoryRepository.saveAll(history);
                log.info("Seeded {} price history entries", history.size());
            } else {
                log.info("Products collection is not empty, skipping product seeding.");
            }

            if (sellerRepository.count() == 0) {
                log.info("Seeding demo Facebook sellers...");
                List<Seller> sellers = createDemoSellers();
                sellerRepository.saveAll(sellers);
                log.info("Seeded {} sellers", sellers.size());
            } else {
                log.info("Sellers collection is not empty, skipping seller seeding.");
            }
        } catch (Exception e) {
            log.warn("Could not seed demo data (MongoDB may be unavailable): {}", e.getMessage());
        }
    }

    private List<Seller> createDemoSellers() {
        LocalDateTime now = LocalDateTime.now();
        List<Seller> sellers = new ArrayList<>();

        sellers.add(Seller.builder()
                .name("Gadget Lounge BD").slug("gadget-lounge-bd").type("facebook")
                .url("https://facebook.com/gadgetloungebd").messengerUrl("https://m.me/gadgetloungebd")
                .city("Dhaka").area("Dhanmondi")
                .followers(38_400L).rating(4.5).reviewCount(412)
                .verified(true).codAvailable(true).sameDayDelivery(true).avgReplyTime("5 min")
                .categories(Arrays.asList("SMARTPHONE","HEADPHONE","SMARTWATCH","GAMING"))
                .brands(Arrays.asList("apple","samsung","xiaomi","oneplus"))
                .tags(Arrays.asList("authorized-reseller","warranty"))
                .source("portal").joinedAt(now.minusMonths(14)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        sellers.add(Seller.builder()
                .name("Sundori Kothon").slug("sundori-kothon").type("facebook")
                .url("https://facebook.com/sundorikothon").messengerUrl("https://m.me/sundorikothon")
                .city("Dhaka").area("Banani")
                .followers(82_300L).rating(4.7).reviewCount(950)
                .verified(true).codAvailable(true).sameDayDelivery(false).avgReplyTime("15 min")
                .categories(Arrays.asList("BEAUTY","FASHION"))
                .brands(Arrays.asList("loreal","cetaphil","nivea","lakme"))
                .tags(Arrays.asList("genuine","imported"))
                .source("portal").joinedAt(now.minusMonths(22)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        sellers.add(Seller.builder()
                .name("Dhaka Kitchen Wares").slug("dhaka-kitchen-wares").type("facebook")
                .url("https://facebook.com/dhakakitchenwares").messengerUrl("https://m.me/dhakakitchenwares")
                .city("Dhaka").area("Mirpur")
                .followers(14_100L).rating(4.3).reviewCount(280)
                .verified(false).codAvailable(true).sameDayDelivery(true).avgReplyTime("30 min")
                .categories(Arrays.asList("KITCHEN","APPLIANCE","FURNITURE"))
                .brands(Arrays.asList("walton","singer","prestige"))
                .tags(Arrays.asList("local-stock"))
                .source("fb_scrape").joinedAt(now.minusMonths(8)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        sellers.add(Seller.builder()
                .name("Bookworm Bangla").slug("bookworm-bangla").type("facebook")
                .url("https://facebook.com/bookwormbangla").messengerUrl("https://m.me/bookwormbangla")
                .city("Dhaka").area("Shahbag")
                .followers(26_700L).rating(4.8).reviewCount(640)
                .verified(true).codAvailable(true).sameDayDelivery(false).avgReplyTime("1 hour")
                .categories(Arrays.asList("BOOK"))
                .brands(Collections.emptyList())
                .tags(Arrays.asList("bangla-books","second-hand"))
                .source("portal").joinedAt(now.minusMonths(18)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        sellers.add(Seller.builder()
                .name("Smart Buy 24").slug("smart-buy-24").type("facebook")
                .url("https://facebook.com/smartbuy24bd").messengerUrl("https://m.me/smartbuy24bd")
                .city("Chittagong").area("Agrabad")
                .followers(12_200L).rating(4.2).reviewCount(180)
                .verified(false).codAvailable(true).sameDayDelivery(false).avgReplyTime("20 min")
                .categories(Arrays.asList("SMARTPHONE","LAPTOP","HEADPHONE"))
                .brands(Arrays.asList("xiaomi","realme","oppo"))
                .source("fb_scrape").joinedAt(now.minusMonths(5)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        sellers.add(Seller.builder()
                .name("Walton AC Hub").slug("walton-ac-hub").type("facebook")
                .url("https://facebook.com/waltonachub").messengerUrl("https://m.me/waltonachub")
                .city("Dhaka").area("Tejgaon")
                .followers(9_800L).rating(4.6).reviewCount(140)
                .verified(true).codAvailable(true).sameDayDelivery(true).avgReplyTime("10 min")
                .categories(Arrays.asList("AC","REFRIGERATOR","APPLIANCE"))
                .brands(Arrays.asList("walton","gree","midea"))
                .tags(Arrays.asList("installation-included","warranty"))
                .source("portal").joinedAt(now.minusMonths(6)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        sellers.add(Seller.builder()
                .name("Aarong Fashion Hub").slug("aarong-fashion-hub").type("facebook")
                .url("https://facebook.com/aarongfashionhub").messengerUrl("https://m.me/aarongfashionhub")
                .city("Dhaka").area("Gulshan")
                .followers(45_600L).rating(4.5).reviewCount(720)
                .verified(true).codAvailable(true).sameDayDelivery(true).avgReplyTime("12 min")
                .categories(Arrays.asList("FASHION","BEAUTY"))
                .brands(Arrays.asList("aarong","yellow","ecstasy"))
                .source("portal").joinedAt(now.minusMonths(20)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        sellers.add(Seller.builder()
                .name("Dhaka Cycle Shop").slug("dhaka-cycle-shop").type("facebook")
                .url("https://facebook.com/dhakacycleshop").messengerUrl("https://m.me/dhakacycleshop")
                .city("Dhaka").area("Bashundhara")
                .followers(7_200L).rating(4.4).reviewCount(95)
                .verified(false).codAvailable(true).sameDayDelivery(false).avgReplyTime("30 min")
                .categories(Arrays.asList("SPORTS","AUTOMOTIVE"))
                .brands(Arrays.asList("trek","giant","specialized"))
                .source("fb_scrape").joinedAt(now.minusMonths(4)).lastSeen(now).createdAt(now).updatedAt(now)
                .build());

        return sellers;
    }

    private List<Product> createDemoProducts() {
        LocalDateTime now = LocalDateTime.now();
        List<Product> products = new ArrayList<>();

        // --- Smartphones ---
        products.add(Product.builder()
                .name("Samsung Galaxy S24 Ultra 12/256GB")
                .slug("samsung-galaxy-s24-ultra-12-256gb")
                .category("Smartphones")
                .imageUrl("https://images.unsplash.com/photo-1610945415295-d9bbf067e59c?w=300")
                .description("The ultimate Galaxy experience with S Pen, titanium frame, and 200MP camera.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/samsung-galaxy-s24-ultra").price(154999.0).originalPrice(164999.0).discount(6.1).currency("BDT").inStock(true).rating(4.7).reviewCount(83).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/samsung-galaxy-s24-ultra").price(152500.0).originalPrice(164999.0).discount(7.6).currency("BDT").inStock(true).rating(4.6).reviewCount(45).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/samsung-galaxy-s24-ultra").price(149999.0).originalPrice(164999.0).discount(9.1).currency("BDT").inStock(true).rating(4.5).reviewCount(210).lastUpdated(now).build()
                ))
                .lowestPrice(149999.0).highestPrice(154999.0).averageRating(4.6).totalReviews(338)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Xiaomi 14 Ultra 16/512GB")
                .slug("xiaomi-14-ultra-16-512gb")
                .category("Smartphones")
                .imageUrl("https://images.unsplash.com/photo-1598327105666-5b89351aff97?w=300")
                .description("Leica-powered quad camera system with Snapdragon 8 Gen 3 processor.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/xiaomi-14-ultra").price(119999.0).originalPrice(129999.0).discount(7.7).currency("BDT").inStock(true).rating(4.5).reviewCount(32).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/xiaomi-14-ultra").price(115999.0).currency("BDT").inStock(true).rating(4.4).reviewCount(87).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/xiaomi-14-ultra").price(118500.0).currency("BDT").inStock(false).rating(4.3).reviewCount(19).lastUpdated(now).build()
                ))
                .lowestPrice(115999.0).highestPrice(119999.0).averageRating(4.4).totalReviews(138)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("iPhone 15 Pro Max 256GB")
                .slug("iphone-15-pro-max-256gb")
                .category("Smartphones")
                .imageUrl("https://images.unsplash.com/photo-1695048133142-1a20484d2569?w=300")
                .description("A17 Pro chip, titanium design, 5x optical zoom, Action button.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/iphone-15-pro-max").price(189999.0).originalPrice(199999.0).discount(5.0).currency("BDT").inStock(true).rating(4.8).reviewCount(156).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/iphone-15-pro-max").price(185000.0).originalPrice(199999.0).discount(7.5).currency("BDT").inStock(true).rating(4.9).reviewCount(72).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/iphone-15-pro-max").price(182999.0).currency("BDT").inStock(true).rating(4.7).reviewCount(320).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Ryans Computers").siteSlug("ryans").productUrl("https://www.ryanscomputers.com/iphone-15-pro-max").price(191500.0).currency("BDT").inStock(true).rating(4.8).reviewCount(44).lastUpdated(now).build()
                ))
                .lowestPrice(182999.0).highestPrice(191500.0).averageRating(4.8).totalReviews(592)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        // --- Laptops ---
        products.add(Product.builder()
                .name("ASUS ROG Strix G16 RTX 4060 Gaming Laptop")
                .slug("asus-rog-strix-g16-rtx-4060")
                .category("Laptops")
                .imageUrl("https://images.unsplash.com/photo-1603302576837-37561b2e2302?w=300")
                .description("Intel Core i7-13650HX, 16GB DDR5, 512GB SSD, RTX 4060 8GB, 16-inch 165Hz.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/asus-rog-strix-g16").price(149500.0).originalPrice(159999.0).discount(6.6).currency("BDT").inStock(true).rating(4.6).reviewCount(28).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Ryans Computers").siteSlug("ryans").productUrl("https://www.ryanscomputers.com/asus-rog-strix-g16").price(147999.0).originalPrice(159999.0).discount(7.5).currency("BDT").inStock(true).rating(4.5).reviewCount(15).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/asus-rog-strix-g16").price(151000.0).currency("BDT").inStock(true).rating(4.3).reviewCount(42).lastUpdated(now).build()
                ))
                .lowestPrice(147999.0).highestPrice(151000.0).averageRating(4.5).totalReviews(85)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Lenovo ThinkPad X1 Carbon Gen 11")
                .slug("lenovo-thinkpad-x1-carbon-gen-11")
                .category("Laptops")
                .imageUrl("https://images.unsplash.com/photo-1588872657578-7efd1f1555ed?w=300")
                .description("Intel Core i7-1365U, 16GB RAM, 512GB SSD, 14-inch 2.8K OLED display.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/lenovo-thinkpad-x1-carbon").price(185000.0).currency("BDT").inStock(true).rating(4.7).reviewCount(12).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Ryans Computers").siteSlug("ryans").productUrl("https://www.ryanscomputers.com/lenovo-thinkpad-x1-carbon").price(182500.0).currency("BDT").inStock(true).rating(4.8).reviewCount(8).lastUpdated(now).build()
                ))
                .lowestPrice(182500.0).highestPrice(185000.0).averageRating(4.8).totalReviews(20)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("HP Pavilion 15 Ryzen 7 Laptop")
                .slug("hp-pavilion-15-ryzen-7")
                .category("Laptops")
                .imageUrl("https://images.unsplash.com/photo-1496181133206-80ce9b88a853?w=300")
                .description("AMD Ryzen 7 7730U, 16GB DDR4, 512GB SSD, 15.6-inch FHD IPS.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/hp-pavilion-15").price(72999.0).originalPrice(79999.0).discount(8.8).currency("BDT").inStock(true).rating(4.3).reviewCount(45).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Ryans Computers").siteSlug("ryans").productUrl("https://www.ryanscomputers.com/hp-pavilion-15").price(71500.0).originalPrice(79999.0).discount(10.6).currency("BDT").inStock(true).rating(4.4).reviewCount(23).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/hp-pavilion-15").price(69999.0).currency("BDT").inStock(true).rating(4.2).reviewCount(67).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/hp-pavilion-15").price(73500.0).currency("BDT").inStock(false).rating(4.1).reviewCount(11).lastUpdated(now).build()
                ))
                .lowestPrice(69999.0).highestPrice(73500.0).averageRating(4.3).totalReviews(146)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        // --- Headphones ---
        products.add(Product.builder()
                .name("Sony WH-1000XM5 Wireless Headphones")
                .slug("sony-wh-1000xm5")
                .category("Headphones")
                .imageUrl("https://images.unsplash.com/photo-1618366712010-f4ae9c647dcb?w=300")
                .description("Industry-leading noise cancellation, 30-hour battery, crystal clear hands-free calling.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/sony-wh-1000xm5").price(35999.0).originalPrice(39999.0).discount(10.0).currency("BDT").inStock(true).rating(4.8).reviewCount(95).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/sony-wh-1000xm5").price(34500.0).originalPrice(39999.0).discount(13.7).currency("BDT").inStock(true).rating(4.7).reviewCount(40).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/sony-wh-1000xm5").price(33999.0).currency("BDT").inStock(true).rating(4.6).reviewCount(180).lastUpdated(now).build()
                ))
                .lowestPrice(33999.0).highestPrice(35999.0).averageRating(4.7).totalReviews(315)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Apple AirPods Pro 2nd Gen USB-C")
                .slug("apple-airpods-pro-2-usbc")
                .category("Headphones")
                .imageUrl("https://images.unsplash.com/photo-1600294037681-c80b4cb5b434?w=300")
                .description("Active Noise Cancellation, Adaptive Transparency, USB-C charging case.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/airpods-pro-2").price(36500.0).currency("BDT").inStock(true).rating(4.8).reviewCount(64).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/airpods-pro-2").price(35999.0).currency("BDT").inStock(true).rating(4.9).reviewCount(38).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/airpods-pro-2").price(34999.0).currency("BDT").inStock(true).rating(4.5).reviewCount(250).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Ryans Computers").siteSlug("ryans").productUrl("https://www.ryanscomputers.com/airpods-pro-2").price(37000.0).currency("BDT").inStock(true).rating(4.7).reviewCount(22).lastUpdated(now).build()
                ))
                .lowestPrice(34999.0).highestPrice(37000.0).averageRating(4.7).totalReviews(374)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("JBL Tune 760NC Wireless Headphones")
                .slug("jbl-tune-760nc")
                .category("Headphones")
                .imageUrl("https://images.unsplash.com/photo-1505740420928-5e560c06d30e?w=300")
                .description("Active noise cancelling, 50-hour battery life, multi-point connection, lightweight design.")
                .prices(List.of(
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/jbl-tune-760nc").price(7999.0).originalPrice(9500.0).discount(15.8).currency("BDT").inStock(true).rating(4.3).reviewCount(320).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/jbl-tune-760nc").price(8200.0).originalPrice(9500.0).discount(13.7).currency("BDT").inStock(true).rating(4.4).reviewCount(55).lastUpdated(now).build()
                ))
                .lowestPrice(7999.0).highestPrice(8200.0).averageRating(4.4).totalReviews(375)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        // --- Books ---
        products.add(Product.builder()
                .name("Misir Ali Samagra by Humayun Ahmed")
                .slug("misir-ali-samagra-humayun-ahmed")
                .category("Books")
                .imageUrl("https://images.unsplash.com/photo-1544716278-ca5e3f4abd8c?w=300")
                .description("Complete collection of Misir Ali stories by legendary Bangladeshi author Humayun Ahmed.")
                .prices(List.of(
                        SitePrice.builder().siteName("Rokomari").siteSlug("rokomari").productUrl("https://www.rokomari.com/misir-ali-samagra").price(850.0).originalPrice(1000.0).discount(15.0).currency("BDT").inStock(true).rating(4.9).reviewCount(2450).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/misir-ali-samagra").price(780.0).currency("BDT").inStock(true).rating(4.7).reviewCount(340).lastUpdated(now).build()
                ))
                .lowestPrice(780.0).highestPrice(850.0).averageRating(4.8).totalReviews(2790)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Atomic Habits by James Clear (Bangla Translation)")
                .slug("atomic-habits-bangla-translation")
                .category("Books")
                .imageUrl("https://images.unsplash.com/photo-1512820790803-83ca734da794?w=300")
                .description("Bangla translation of the bestselling self-improvement book on building good habits.")
                .prices(List.of(
                        SitePrice.builder().siteName("Rokomari").siteSlug("rokomari").productUrl("https://www.rokomari.com/atomic-habits-bangla").price(420.0).originalPrice(500.0).discount(16.0).currency("BDT").inStock(true).rating(4.6).reviewCount(890).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/atomic-habits-bangla").price(380.0).currency("BDT").inStock(true).rating(4.4).reviewCount(200).lastUpdated(now).build()
                ))
                .lowestPrice(380.0).highestPrice(420.0).averageRating(4.5).totalReviews(1090)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Himu Samagra by Humayun Ahmed")
                .slug("himu-samagra-humayun-ahmed")
                .category("Books")
                .imageUrl("https://images.unsplash.com/photo-1481627834876-b7833e8f5570?w=300")
                .description("Complete collection featuring the beloved character Himu by Humayun Ahmed.")
                .prices(List.of(
                        SitePrice.builder().siteName("Rokomari").siteSlug("rokomari").productUrl("https://www.rokomari.com/himu-samagra").price(950.0).originalPrice(1100.0).discount(13.6).currency("BDT").inStock(true).rating(4.9).reviewCount(3200).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/himu-samagra").price(880.0).currency("BDT").inStock(true).rating(4.6).reviewCount(410).lastUpdated(now).build()
                ))
                .lowestPrice(880.0).highestPrice(950.0).averageRating(4.8).totalReviews(3610)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        // --- Groceries ---
        products.add(Product.builder()
                .name("Pran Aromatic Chinigura Rice 5kg")
                .slug("pran-aromatic-chinigura-rice-5kg")
                .category("Groceries")
                .imageUrl("https://images.unsplash.com/photo-1586201375761-83865001e31c?w=300")
                .description("Premium quality aromatic Chinigura rice, perfect for special occasions and everyday meals.")
                .prices(List.of(
                        SitePrice.builder().siteName("Chaldal").siteSlug("chaldal").productUrl("https://chaldal.com/pran-chinigura-rice-5kg").price(1050.0).currency("BDT").inStock(true).rating(4.5).reviewCount(180).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/pran-chinigura-rice-5kg").price(1120.0).currency("BDT").inStock(true).rating(4.3).reviewCount(75).lastUpdated(now).build()
                ))
                .lowestPrice(1050.0).highestPrice(1120.0).averageRating(4.4).totalReviews(255)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Radhuni Mixed Masala 100gm (Pack of 5)")
                .slug("radhuni-mixed-masala-100gm-pack-5")
                .category("Groceries")
                .imageUrl("https://images.unsplash.com/photo-1596040033229-a9821ebd058d?w=300")
                .description("Authentic Bangladeshi spice blends for everyday cooking. Includes curry, biryani, and tandoori masala.")
                .prices(List.of(
                        SitePrice.builder().siteName("Chaldal").siteSlug("chaldal").productUrl("https://chaldal.com/radhuni-mixed-masala").price(350.0).currency("BDT").inStock(true).rating(4.6).reviewCount(420).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/radhuni-mixed-masala").price(380.0).currency("BDT").inStock(true).rating(4.4).reviewCount(95).lastUpdated(now).build()
                ))
                .lowestPrice(350.0).highestPrice(380.0).averageRating(4.5).totalReviews(515)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Nescafe Classic Instant Coffee 200g")
                .slug("nescafe-classic-instant-coffee-200g")
                .category("Groceries")
                .imageUrl("https://images.unsplash.com/photo-1559056199-641a0ac8b55e?w=300")
                .description("Rich and smooth instant coffee for a perfect cup every morning.")
                .prices(List.of(
                        SitePrice.builder().siteName("Chaldal").siteSlug("chaldal").productUrl("https://chaldal.com/nescafe-classic-200g").price(750.0).originalPrice(820.0).discount(8.5).currency("BDT").inStock(true).rating(4.5).reviewCount(310).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/nescafe-classic-200g").price(720.0).currency("BDT").inStock(true).rating(4.3).reviewCount(180).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/nescafe-classic-200g").price(790.0).currency("BDT").inStock(true).rating(4.2).reviewCount(25).lastUpdated(now).build()
                ))
                .lowestPrice(720.0).highestPrice(790.0).averageRating(4.3).totalReviews(515)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        // --- Fashion ---
        products.add(Product.builder()
                .name("Yellow Premium Cotton Panjabi for Men")
                .slug("yellow-premium-cotton-panjabi-men")
                .category("Fashion")
                .imageUrl("https://images.unsplash.com/photo-1594938298603-c8148c4dae35?w=300")
                .description("Handcrafted premium cotton panjabi with elegant embroidery work, perfect for festive occasions.")
                .prices(List.of(
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/yellow-cotton-panjabi").price(1850.0).originalPrice(2500.0).discount(26.0).currency("BDT").inStock(true).rating(4.2).reviewCount(89).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/yellow-cotton-panjabi").price(1950.0).originalPrice(2500.0).discount(22.0).currency("BDT").inStock(true).rating(4.3).reviewCount(34).lastUpdated(now).build()
                ))
                .lowestPrice(1850.0).highestPrice(1950.0).averageRating(4.3).totalReviews(123)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Aarong Jamdani Saree - Blue & White")
                .slug("aarong-jamdani-saree-blue-white")
                .category("Fashion")
                .imageUrl("https://images.unsplash.com/photo-1610030469983-98e550d6193c?w=300")
                .description("Traditional Dhakai Jamdani saree with intricate handwoven patterns in blue and white.")
                .prices(List.of(
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/aarong-jamdani-saree").price(8500.0).originalPrice(12000.0).discount(29.2).currency("BDT").inStock(true).rating(4.6).reviewCount(55).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/aarong-jamdani-saree").price(9200.0).originalPrice(12000.0).discount(23.3).currency("BDT").inStock(true).rating(4.7).reviewCount(20).lastUpdated(now).build()
                ))
                .lowestPrice(8500.0).highestPrice(9200.0).averageRating(4.7).totalReviews(75)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Nike Air Max 270 React Sneakers")
                .slug("nike-air-max-270-react-sneakers")
                .category("Fashion")
                .imageUrl("https://images.unsplash.com/photo-1542291026-7eec264c27ff?w=300")
                .description("Comfortable lifestyle sneakers combining two of Nike's best air technologies.")
                .prices(List.of(
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/nike-air-max-270-react").price(14500.0).originalPrice(18000.0).discount(19.4).currency("BDT").inStock(true).rating(4.4).reviewCount(145).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/nike-air-max-270-react").price(15200.0).originalPrice(18000.0).discount(15.6).currency("BDT").inStock(true).rating(4.5).reviewCount(28).lastUpdated(now).build()
                ))
                .lowestPrice(14500.0).highestPrice(15200.0).averageRating(4.5).totalReviews(173)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        products.add(Product.builder()
                .name("Samsung 32-inch Crystal UHD 4K Smart TV")
                .slug("samsung-32-crystal-uhd-4k-smart-tv")
                .category("Electronics")
                .imageUrl("https://images.unsplash.com/photo-1593359677879-a4bb92f829d1?w=300")
                .description("Crystal Processor 4K, HDR, Smart Hub, built-in streaming apps.")
                .prices(List.of(
                        SitePrice.builder().siteName("Startech").siteSlug("startech").productUrl("https://www.startech.com.bd/samsung-32-4k-tv").price(28999.0).originalPrice(34999.0).discount(17.1).currency("BDT").inStock(true).rating(4.4).reviewCount(62).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Ryans Computers").siteSlug("ryans").productUrl("https://www.ryanscomputers.com/samsung-32-4k-tv").price(27500.0).originalPrice(34999.0).discount(21.4).currency("BDT").inStock(true).rating(4.5).reviewCount(28).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Daraz").siteSlug("daraz").productUrl("https://www.daraz.com.bd/samsung-32-4k-tv").price(26999.0).currency("BDT").inStock(true).rating(4.3).reviewCount(210).lastUpdated(now).build(),
                        SitePrice.builder().siteName("Pickaboo").siteSlug("pickaboo").productUrl("https://www.pickaboo.com/samsung-32-4k-tv").price(29500.0).currency("BDT").inStock(false).rating(4.2).reviewCount(15).lastUpdated(now).build()
                ))
                .lowestPrice(26999.0).highestPrice(29500.0).averageRating(4.4).totalReviews(315)
                .lastScraped(now).createdAt(now).updatedAt(now).build());

        return products;
    }

    private List<Review> createDemoReviews(List<Product> products) {
        List<Review> reviews = new ArrayList<>();
        String[][] reviewData = {
                {"Tanvir Hasan", "5", "Absolutely love it!", "Best purchase I've made this year. The quality is outstanding and totally worth the price."},
                {"Fatima Begum", "4", "Very good product", "Happy with the purchase. Delivery was quick through Chaldal. Minor packaging issue but product is great."},
                {"Rahim Uddin", "5", "Excellent value", "Compared prices on multiple sites before buying. Got the best deal and the product exceeded expectations."},
                {"Nusrat Jahan", "3", "Decent but overpriced", "Product is okay but I found it cheaper on another site after buying. The quality is acceptable though."},
                {"Kamal Ahmed", "4", "Good quality", "Using it for a month now. No complaints. The build quality is solid."},
                {"Priya Das", "5", "Perfect gift", "Bought this as a gift and the recipient loved it. Fast delivery and good packaging."},
                {"Sohel Rana", "4", "Satisfied customer", "Good product for the price. The delivery was smooth and customer service was helpful."},
                {"Ayesha Siddiqua", "5", "Must buy!", "This is the best product in its category. I've recommended it to all my friends."},
                {"Rafiq Islam", "2", "Not as expected", "The product looks different from the photos. Quality could be better for this price range."},
                {"Sabrina Sultana", "4", "Great experience", "Smooth ordering process, fast delivery, and the product works perfectly."},
        };

        String[] siteNames = {"Startech", "Daraz", "Pickaboo", "Ryans Computers", "Rokomari", "Chaldal"};
        Random random = new Random(42); // Fixed seed for reproducibility

        for (Product product : products) {
            int reviewCount = 2 + random.nextInt(3); // 2-4 reviews per product
            for (int i = 0; i < reviewCount && i < reviewData.length; i++) {
                String[] data = reviewData[(products.indexOf(product) * 3 + i) % reviewData.length];
                reviews.add(Review.builder()
                        .productId(product.getId())
                        .siteName(siteNames[random.nextInt(siteNames.length)])
                        .reviewerName(data[0])
                        .rating(Integer.parseInt(data[1]))
                        .title(data[2])
                        .content(data[3])
                        .reviewDate(LocalDateTime.now().minusDays(random.nextInt(90)))
                        .verified(random.nextBoolean())
                        .build());
            }
        }

        return reviews;
    }

    private List<PriceHistory> createDemoPriceHistory(List<Product> products) {
        List<PriceHistory> history = new ArrayList<>();
        Random random = new Random(42);

        for (Product product : products) {
            for (SitePrice sitePrice : product.getPrices()) {
                if (sitePrice.getPrice() == null) continue;
                double basePrice = sitePrice.getPrice();
                // Generate 7 days of price history
                for (int day = 7; day >= 0; day--) {
                    double variation = basePrice * (0.95 + random.nextDouble() * 0.10); // +/- 5%
                    history.add(PriceHistory.builder()
                            .productId(product.getId())
                            .siteName(sitePrice.getSiteName())
                            .price(Math.round(variation * 100.0) / 100.0)
                            .currency("BDT")
                            .recordedAt(LocalDateTime.now().minusDays(day))
                            .build());
                }
            }
        }

        return history;
    }
}
