package com.damKemon.dam.kemon.intelligence;

public enum ProductCategory {
    SMARTPHONE("Smartphones",    50.0,   500_000.0),
    LAPTOP("Laptops",            10_000.0, 1_500_000.0),
    TABLET("Tablets",            3_000.0,  300_000.0),
    DESKTOP("Desktops & PC",     15_000.0, 800_000.0),
    HEADPHONE("Headphones & Audio", 200.0, 100_000.0),
    CAMERA("Cameras",            3_000.0,  800_000.0),
    SMARTWATCH("Smartwatches",   500.0,    300_000.0),
    GAMING("Gaming",             500.0,    500_000.0),
    TV("TVs",                    8_000.0,  800_000.0),
    APPLIANCE("Home Appliances", 1_000.0,  500_000.0),
    AC("Air Conditioners",       20_000.0, 400_000.0),
    REFRIGERATOR("Refrigerators",10_000.0, 400_000.0),
    KITCHEN("Kitchen",           100.0,    100_000.0),
    FASHION("Fashion",           100.0,    200_000.0),
    BEAUTY("Beauty & Care",      50.0,     50_000.0),
    BOOK("Books",                30.0,     20_000.0),
    GROCERY("Groceries",         10.0,     20_000.0),
    BABY("Baby & Kids",          50.0,     50_000.0),
    SPORTS("Sports & Outdoor",   100.0,    200_000.0),
    AUTOMOTIVE("Automotive",     50.0,     5_000_000.0),
    FURNITURE("Furniture",       500.0,    500_000.0),
    GENERAL("General",           1.0,      Double.MAX_VALUE);

    private final String label;
    private final double minPrice;
    private final double maxPrice;

    ProductCategory(String label, double minPrice, double maxPrice) {
        this.label = label;
        this.minPrice = minPrice;
        this.maxPrice = maxPrice;
    }

    public String getLabel() { return label; }
    public double getMinPrice() { return minPrice; }
    public double getMaxPrice() { return maxPrice; }

    public boolean isPlausiblePrice(Double price) {
        if (price == null) return false;
        return price >= minPrice && price <= maxPrice;
    }
}
