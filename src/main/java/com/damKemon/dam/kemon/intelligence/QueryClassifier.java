package com.damKemon.dam.kemon.intelligence;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Classifies a free-text search query into product categories, brands, and model
 * tokens so the scraper engine can route to the right sites and filter results.
 *
 * Strategy:
 *   1. Normalize (lower, strip punctuation, collapse whitespace).
 *   2. Tokenize.
 *   3. Score each ProductCategory by matched keyword hits + brand affinity.
 *   4. Pull out model-like tokens (alphanumeric mixed) for fuzzy matching later.
 */
@Service
public class QueryClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryClassifier.class);

    // category keyword dictionary — English + romanized Bangla
    private static final Map<ProductCategory, Set<String>> KW = new EnumMap<>(ProductCategory.class);
    private static final Map<String, Set<ProductCategory>> BRAND_CATEGORIES = new HashMap<>();
    private static final Pattern MODEL_PATTERN = Pattern.compile("^[a-z0-9]*\\d+[a-z0-9]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUNCT = Pattern.compile("[^a-z0-9\\s]");

    static {
        KW.put(ProductCategory.SMARTPHONE, Set.of(
            "phone","mobile","smartphone","iphone","android","galaxy","redmi","oppo","vivo","realme",
            "pixel","ipad","pro max","ultra","plus","mini","note","mobail","ফোন","mobiles"
        ));
        KW.put(ProductCategory.LAPTOP, Set.of(
            "laptop","notebook","macbook","thinkpad","pavilion","ideapad","vivobook","zenbook",
            "rog","tuf","predator","nitro","inspiron","latitude","probook","elitebook","gaming laptop","ল্যাপটপ"
        ));
        KW.put(ProductCategory.TABLET, Set.of(
            "tab","tablet","ipad","galaxy tab","mi pad","matepad","fire hd"
        ));
        KW.put(ProductCategory.DESKTOP, Set.of(
            "desktop","pc","cpu","ryzen build","gaming pc","prebuilt","monitor","graphics card","gpu",
            "motherboard","ram ddr","ssd","nvme","power supply","psu","cooler","case"
        ));
        KW.put(ProductCategory.HEADPHONE, Set.of(
            "headphone","headphones","headset","earphone","earphones","earbud","earbuds","airpods",
            "buds","wh-1000","tws","wireless headphone","jbl","bose","skullcandy","sennheiser","audio"
        ));
        KW.put(ProductCategory.CAMERA, Set.of(
            "camera","dslr","mirrorless","gopro","insta360","lens","tripod","camcorder","drone","action camera"
        ));
        KW.put(ProductCategory.SMARTWATCH, Set.of(
            "watch","smartwatch","mi band","amazfit","galaxy watch","apple watch","fitness band","wearable"
        ));
        KW.put(ProductCategory.GAMING, Set.of(
            "ps5","ps4","xbox","nintendo","switch","controller","keyboard","mouse","gaming chair",
            "playstation","steam deck","joystick"
        ));
        KW.put(ProductCategory.TV, Set.of(
            "tv","television","smart tv","led tv","oled","qled","4k tv","8k tv","android tv","fire tv","টিভি"
        ));
        KW.put(ProductCategory.AC, Set.of(
            "ac","air conditioner","split ac","window ac","inverter ac","portable ac","aircon","এসি"
        ));
        KW.put(ProductCategory.REFRIGERATOR, Set.of(
            "fridge","refrigerator","freezer","deep freezer","mini fridge","ফ্রিজ"
        ));
        KW.put(ProductCategory.APPLIANCE, Set.of(
            "microwave","oven","blender","grinder","mixer","washing machine","dryer","iron",
            "rice cooker","pressure cooker","air fryer","induction","kettle","toaster","geyser","heater",
            "vacuum","fan","ceiling fan","table fan","stand fan"
        ));
        KW.put(ProductCategory.KITCHEN, Set.of(
            "pan","pot","cookware","knife","cutlery","utensil","dinner set","plate","bowl","cup","mug","glass"
        ));
        KW.put(ProductCategory.FASHION, Set.of(
            "saree","panjabi","kurta","shirt","t-shirt","jeans","pant","shoe","sneaker","sandal","watch",
            "wallet","bag","handbag","sunglass","cap","jacket","sweater","hoodie","dupatta","salwar",
            "kameez","blouse","lehenga","jamdani","শাড়ি","পাঞ্জাবি"
        ));
        KW.put(ProductCategory.BEAUTY, Set.of(
            "cream","lotion","serum","lipstick","mascara","foundation","face wash","shampoo","conditioner",
            "perfume","fragrance","deodorant","cosmetic","makeup","skincare","cetaphil","nivea","ponds"
        ));
        KW.put(ProductCategory.BOOK, Set.of(
            "book","books","novel","poetry","story","ebook","kobita","upanyash","textbook","guide","হইল",
            "samagra","atomic habits","humayun ahmed","misir ali","himu","rabindranath","হুমায়ুন","বই"
        ));
        KW.put(ProductCategory.GROCERY, Set.of(
            "rice","oil","sugar","flour","atta","masala","dal","lentil","chinigura","biryani rice",
            "tea","coffee","milk","powder","biscuit","chocolate","sauce","ketchup","noodles","pasta",
            "egg","fish","meat","chicken","vegetable","fruit","spice","salt","pran","radhuni","তেল","চাল"
        ));
        KW.put(ProductCategory.BABY, Set.of(
            "diaper","baby food","formula","stroller","baby walker","feeder","bottle","toy","kids","children"
        ));
        KW.put(ProductCategory.SPORTS, Set.of(
            "cricket","bat","football","basketball","jersey","yoga mat","dumbbell","treadmill","gym",
            "running shoe","cycle","bicycle"
        ));
        KW.put(ProductCategory.AUTOMOTIVE, Set.of(
            "car","bike","motorcycle","helmet","tire","tyre","battery","engine oil","car charger","dashcam"
        ));
        KW.put(ProductCategory.FURNITURE, Set.of(
            "sofa","bed","mattress","table","chair","wardrobe","cabinet","shelf","desk","drawer"
        ));

        // brand → category affinity (a brand can map to multiple categories)
        addBrand("walton", ProductCategory.AC, ProductCategory.REFRIGERATOR, ProductCategory.APPLIANCE, ProductCategory.TV, ProductCategory.SMARTPHONE);
        addBrand("samsung", ProductCategory.SMARTPHONE, ProductCategory.TV, ProductCategory.APPLIANCE, ProductCategory.TABLET, ProductCategory.SMARTWATCH, ProductCategory.REFRIGERATOR, ProductCategory.AC);
        addBrand("lg",       ProductCategory.TV, ProductCategory.REFRIGERATOR, ProductCategory.AC, ProductCategory.APPLIANCE);
        addBrand("apple",    ProductCategory.SMARTPHONE, ProductCategory.LAPTOP, ProductCategory.TABLET, ProductCategory.HEADPHONE, ProductCategory.SMARTWATCH);
        addBrand("xiaomi",   ProductCategory.SMARTPHONE, ProductCategory.SMARTWATCH, ProductCategory.HEADPHONE, ProductCategory.APPLIANCE);
        addBrand("redmi",    ProductCategory.SMARTPHONE);
        addBrand("oppo",     ProductCategory.SMARTPHONE);
        addBrand("vivo",     ProductCategory.SMARTPHONE);
        addBrand("realme",   ProductCategory.SMARTPHONE);
        addBrand("oneplus",  ProductCategory.SMARTPHONE);
        addBrand("google",   ProductCategory.SMARTPHONE);
        addBrand("asus",     ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.GAMING);
        addBrand("lenovo",   ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.TABLET);
        addBrand("hp",       ProductCategory.LAPTOP, ProductCategory.DESKTOP);
        addBrand("dell",     ProductCategory.LAPTOP, ProductCategory.DESKTOP);
        addBrand("msi",      ProductCategory.LAPTOP, ProductCategory.GAMING);
        addBrand("acer",     ProductCategory.LAPTOP, ProductCategory.DESKTOP);
        addBrand("sony",     ProductCategory.HEADPHONE, ProductCategory.TV, ProductCategory.CAMERA, ProductCategory.GAMING);
        addBrand("bose",     ProductCategory.HEADPHONE);
        addBrand("jbl",      ProductCategory.HEADPHONE);
        addBrand("anker",    ProductCategory.HEADPHONE, ProductCategory.APPLIANCE);
        addBrand("canon",    ProductCategory.CAMERA);
        addBrand("nikon",    ProductCategory.CAMERA);
        addBrand("gopro",    ProductCategory.CAMERA);
        addBrand("dji",      ProductCategory.CAMERA);
        addBrand("daikin",   ProductCategory.AC);
        addBrand("gree",     ProductCategory.AC);
        addBrand("midea",    ProductCategory.AC, ProductCategory.APPLIANCE);
        addBrand("panasonic",ProductCategory.AC, ProductCategory.APPLIANCE, ProductCategory.TV);
        addBrand("haier",    ProductCategory.AC, ProductCategory.REFRIGERATOR, ProductCategory.APPLIANCE);
        addBrand("singer",   ProductCategory.APPLIANCE, ProductCategory.TV, ProductCategory.REFRIGERATOR);
        addBrand("vision",   ProductCategory.APPLIANCE, ProductCategory.TV);
        addBrand("philips",  ProductCategory.APPLIANCE, ProductCategory.BEAUTY);
        addBrand("aarong",   ProductCategory.FASHION, ProductCategory.BEAUTY);
        addBrand("yellow",   ProductCategory.FASHION);
        addBrand("ecstasy",  ProductCategory.FASHION);
        addBrand("nike",     ProductCategory.FASHION, ProductCategory.SPORTS);
        addBrand("adidas",   ProductCategory.FASHION, ProductCategory.SPORTS);
        addBrand("puma",     ProductCategory.FASHION, ProductCategory.SPORTS);
        addBrand("nivea",    ProductCategory.BEAUTY);
        addBrand("cetaphil", ProductCategory.BEAUTY);
        addBrand("loreal",   ProductCategory.BEAUTY);
        addBrand("lakme",    ProductCategory.BEAUTY);
        addBrand("pran",     ProductCategory.GROCERY);
        addBrand("radhuni",  ProductCategory.GROCERY);
        addBrand("nescafe",  ProductCategory.GROCERY);
        addBrand("nestle",   ProductCategory.GROCERY);
        addBrand("ispahani", ProductCategory.GROCERY);
        addBrand("humayun ahmed", ProductCategory.BOOK);
        addBrand("rokomari", ProductCategory.BOOK);
    }

    private static void addBrand(String brand, ProductCategory... cats) {
        BRAND_CATEGORIES.put(brand, new HashSet<>(Arrays.asList(cats)));
    }

    // ====== Aho-Corasick automata built once at startup ======
    // One automaton matches every (category × keyword) pair in a single pass
    // over the query. Payload encodes weight (3 for multi-word, 2 for token)
    // and the category to credit.
    private AhoCorasick<KwHit> keywordAutomaton;
    private AhoCorasick<BrandHit> brandAutomaton;

    private record KwHit(ProductCategory category, int weight, boolean multiWord) {}
    private record BrandHit(String brand, Set<ProductCategory> categories) {}

    @PostConstruct
    void buildAutomata() {
        keywordAutomaton = new AhoCorasick<>();
        for (Map.Entry<ProductCategory, Set<String>> e : KW.entrySet()) {
            for (String kw : e.getValue()) {
                boolean multi = kw.contains(" ");
                keywordAutomaton.add(kw, new KwHit(e.getKey(), multi ? 3 : 2, multi));
            }
        }
        keywordAutomaton.build();

        brandAutomaton = new AhoCorasick<>();
        for (Map.Entry<String, Set<ProductCategory>> e : BRAND_CATEGORIES.entrySet()) {
            brandAutomaton.add(e.getKey(), new BrandHit(e.getKey(), e.getValue()));
        }
        brandAutomaton.build();

        int kwCount = KW.values().stream().mapToInt(Set::size).sum();
        log.info("QueryClassifier: built Aho-Corasick with {} keywords + {} brands",
                kwCount, BRAND_CATEGORIES.size());
    }

    public QueryIntent classify(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return QueryIntent.builder().original("").normalized("").confidence(0).build();
        }

        String normalized = PUNCT.matcher(rawQuery.toLowerCase()).replaceAll(" ")
                .replaceAll("\\s+", " ").trim();

        // ===== single Aho-Corasick pass picks up every brand + keyword =====
        // O(|query| + matches) instead of O(brands + categories × keywords).
        List<String> detectedBrands = new ArrayList<>();
        Set<ProductCategory> brandAffinity = new LinkedHashSet<>();
        for (AhoCorasick.Hit<BrandHit> hit : brandAutomaton.findAll(normalized)) {
            detectedBrands.add(hit.payload().brand());
            brandAffinity.addAll(hit.payload().categories());
        }

        Map<ProductCategory, Integer> scores = new EnumMap<>(ProductCategory.class);
        for (AhoCorasick.Hit<KwHit> hit : keywordAutomaton.findAll(normalized)) {
            scores.merge(hit.payload().category(), hit.payload().weight(), Integer::sum);
        }
        // brand affinity bonus
        for (ProductCategory c : brandAffinity) scores.merge(c, 1, Integer::sum);

        String[] tokens = normalized.split("\\s+");

        // model-looking tokens (e.g. "s24", "wh-1000xm5", "x1")
        List<String> modelTokens = new ArrayList<>();
        for (String tok : tokens) {
            if (tok.length() >= 2 && MODEL_PATTERN.matcher(tok).matches()) {
                modelTokens.add(tok);
            }
        }

        // sort categories by score desc, keep top categories
        List<ProductCategory> ranked = new ArrayList<>(scores.keySet());
        ranked.sort((a, b) -> scores.get(b) - scores.get(a));

        // confidence: relative gap between top and second
        double confidence;
        if (ranked.isEmpty()) {
            ranked.add(ProductCategory.GENERAL);
            confidence = 0.0;
        } else if (ranked.size() == 1) {
            confidence = Math.min(1.0, scores.get(ranked.get(0)) / 5.0);
        } else {
            int top = scores.get(ranked.get(0));
            int second = scores.get(ranked.get(1));
            confidence = Math.min(1.0, (top - second + 1) / 5.0);
        }

        // Only keep categories with at least half the top score (multi-category queries)
        List<ProductCategory> finalCategories = new ArrayList<>();
        if (!scores.isEmpty()) {
            int top = scores.get(ranked.get(0));
            for (ProductCategory c : ranked) {
                if (scores.get(c) * 2 >= top) finalCategories.add(c);
            }
        } else {
            finalCategories.add(ProductCategory.GENERAL);
        }

        QueryIntent intent = QueryIntent.builder()
                .original(rawQuery)
                .normalized(normalized)
                .categories(finalCategories)
                .brands(detectedBrands)
                .keywords(Arrays.asList(tokens))
                .modelTokens(modelTokens)
                .confidence(confidence)
                .build();

        log.debug("Query '{}' -> categories={} brands={} confidence={}",
                rawQuery, finalCategories, detectedBrands, confidence);
        return intent;
    }
}
