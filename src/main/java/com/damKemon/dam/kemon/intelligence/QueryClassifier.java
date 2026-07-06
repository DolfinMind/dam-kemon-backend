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
 *
 * <p>Accessories get a heavier keyword weight than the parent category so that
 * "iPhone case" / "laptop bag" / "watch strap" classify as ACCESSORY, not as
 * SMARTPHONE / LAPTOP / SMARTWATCH. Keyword dictionaries mix English, romanized
 * Bangla and Bengali script; many keywords intentionally belong to more than one
 * category (Aho-Corasick keeps every payload, so each match credits all of them).
 */
@Service
public class QueryClassifier {

    private static final Logger log = LoggerFactory.getLogger(QueryClassifier.class);

    // category keyword dictionary — English + romanized Bangla + Bengali script
    private static final Map<ProductCategory, Set<String>> KW = new EnumMap<>(ProductCategory.class);
    private static final Map<String, Set<ProductCategory>> BRAND_CATEGORIES = new HashMap<>();
    private static final Pattern MODEL_PATTERN = Pattern.compile("^[a-z0-9]*\\d+[a-z0-9]*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PUNCT = Pattern.compile("[^a-z0-9\\s]");

    /** Unambiguous out-of-scope objects that IN-scope brands also make (Oraimo/
     *  Xiaomi/Anker sell vacuums, grooming gear, kitchen appliances). A bare object
     *  word otherwise only TIES the brand-name keyword ("oraimo" scores HEADPHONE
     *  too) and leaks in as "Headphones & Audio". A hit here forces the out-of-scope
     *  category so the focus gate drops it. Words with a computing sense (fan, iron,
     *  mouse) are deliberately excluded — those can be PC parts. */
    private static final Set<String> HARD_OUT_OF_SCOPE = Set.of(
            "vacuum","blender","kettle","toaster","microwave","trimmer","shaver","hairdryer");

    /** Context words, not object words — they describe a LINE or a mode, and at
     *  full weight they hijack other objects: "GALAXY Drone Toy" filed as a
     *  smartphone, "Mobile 4G LTE Router" as a smartphone, a CCTV cam with
     *  "…Audio…" in the name as Headphones & Audio (all seen on the live rails).
     *  Weight 1: still enough to win when nothing else matches ("mobile" alone
     *  → smartphones), never enough to beat a real object word. */
    private static final Set<String> CONTEXT_WORDS = Set.of(
            "galaxy","note","ultra","mobile","mobiles","mobail","audio");

    /** Dedup-safe set builder (Set.of throws on duplicates; this doesn't). */
    private static Set<String> kw(String... s) { return new HashSet<>(Arrays.asList(s)); }

    static {
        KW.put(ProductCategory.SMARTPHONE, kw(
            "phone","mobile","smartphone","smart phone","handset","cellphone","cell phone","iphone","android",
            "galaxy","redmi","oppo","vivo","realme","poco","iqoo","oneplus","pixel","nothing phone","honor",
            "huawei","nokia","motorola","moto","symphony","itel","tecno","infinix","lava","5g phone","dual sim",
            "feature phone","button phone","pro max","ultra","note","mobail","mobiles","one plus","oneplus nord",
            "ফোন","মোবাইল","হ্যান্ডসেট","আইফোন"
        ));
        KW.put(ProductCategory.LAPTOP, kw(
            "laptop","notebook","ultrabook","macbook","macbook air","macbook pro","thinkpad","pavilion","ideapad",
            "vivobook","zenbook","rog","tuf","predator","nitro","inspiron","latitude","probook","elitebook",
            "gaming laptop","chromebook","core i3","core i5","core i7","ryzen 5","ryzen 7","ল্যাপটপ"
        ));
        KW.put(ProductCategory.TABLET, kw(
            "tab","tablet","ipad","galaxy tab","mi pad","redmi pad","matepad","lenovo tab","fire hd","drawing tablet",
            "ট্যাব","ট্যাবলেট"
        ));
        KW.put(ProductCategory.DESKTOP, kw(
            "desktop","pc","pc case","casing","tower pc","gaming pc","prebuilt","all in one pc","ryzen build","cpu",
            "processor","motherboard","graphics card","gpu","ram","ddr4","ddr5","power supply","psu","cpu cooler",
            "aio cooler","pc build","ডেস্কটপ"
        ));
        KW.put(ProductCategory.MONITOR, kw(
            "monitor","gaming monitor","ips monitor","led monitor","curved monitor","ultrawide","4k monitor",
            "144hz","165hz","computer monitor","মনিটর"
        ));
        KW.put(ProductCategory.STORAGE, kw(
            "ssd","hdd","hard disk","hard drive","nvme","sata ssd","portable ssd","external hdd","external ssd",
            "pendrive","pen drive","usb drive","flash drive","memory card","micro sd","sd card","memory stick",
            "পেনড্রাইভ","মেমোরি কার্ড"
        ));
        KW.put(ProductCategory.NETWORKING, kw(
            "router","wifi router","wi-fi router","mesh wifi","access point","range extender","wifi extender",
            "network switch","modem","ont","onu","lan card","wifi adapter","usb wifi","powerline","রাউটার",
            // pocket/SIM routers name themselves "mobile …" — must not read as phones
            "pocket router","mobile router","4g router","lte router","5g router","sim router",
            "mobile wifi","mobile hotspot","mifi"
        ));
        KW.put(ProductCategory.PRINTER, kw(
            "printer","scanner","all in one printer","inkjet","laserjet","laser printer","photocopier","ink cartridge",
            "toner","ink tank","barcode printer","প্রিন্টার"
        ));
        KW.put(ProductCategory.SECURITY, kw(
            "cctv","cctv camera","ip camera","security camera","dvr","nvr","dome camera","bullet camera","wifi camera",
            "doorbell camera","smart lock","fingerprint lock","access control","সিসিটিভি",
            // surveillance-cam vocabulary (Hikvision/Dahua line names + form factors)
            "turret camera","ptz camera","poe camera","network camera","analog camera",
            "colorvu","darkfighter","hilook","xvr","acusense"
        ));
        KW.put(ProductCategory.HEADPHONE, kw(
            "headphone","headphones","headset","earphone","earphones","earbud","earbuds","airpods","buds","tws",
            "true wireless","neckband","bluetooth headphone","gaming headset","over ear","in ear","wh-1000",
            "speaker","bluetooth speaker","portable speaker","soundbar","woofer","home theater","microphone","mic",
            "audio","noise cancelling","noise cancellation","active noise","qcy","soundpeats","soundcore","tribit",
            "oraimo","havit","edifier","হেডফোন","ইয়ারফোন","স্পিকার"
        ));
        KW.put(ProductCategory.CAMERA, kw(
            "camera","dslr","mirrorless","gopro","insta360","camera lens","camera tripod","camcorder","drone",
            "action camera","webcam","instant camera","gimbal","ক্যামেরা"
        ));
        KW.put(ProductCategory.SMARTWATCH, kw(
            "smartwatch","smart watch","mi band","amazfit","galaxy watch","apple watch","fitness band",
            "fitness tracker","smart band","wearable","kids watch","ঘড়ি","স্মার্টওয়াচ"
        ));
        KW.put(ProductCategory.GAMING, kw(
            "ps5","ps4","xbox","nintendo","nintendo switch","controller","gamepad","dualshock","dualsense",
            "gaming chair","playstation","steam deck","joystick","gaming console","game console","gaming keyboard",
            "gaming mouse","vr headset","oculus","meta quest"
        ));
        KW.put(ProductCategory.TV, kw(
            "tv","television","smart tv","led tv","oled","qled","4k tv","8k tv","android tv","google tv","fire tv",
            "tv box","android box","projector","টিভি","টেলিভিশন"
        ));
        KW.put(ProductCategory.AC, kw(
            "ac","air conditioner","split ac","window ac","inverter ac","portable ac","aircon","1 ton ac",
            "1.5 ton ac","2 ton ac","cassette ac","এসি","এয়ার কন্ডিশনার"
        ));
        KW.put(ProductCategory.REFRIGERATOR, kw(
            "fridge","refrigerator","freezer","deep freezer","mini fridge","double door fridge","single door fridge",
            "side by side","non frost","beverage cooler","water dispenser","ফ্রিজ"
        ));
        KW.put(ProductCategory.APPLIANCE, kw(
            "microwave","microwave oven","oven","convection oven","blender","grinder","mixer","hand mixer","juicer",
            "washing machine","front load","top load","dryer","iron","steam iron","rice cooker","pressure cooker",
            "air fryer","induction cooker","kettle","toaster","geyser","water heater","room heater","vacuum cleaner",
            "water purifier","water filter","fan","ceiling fan","table fan","stand fan","exhaust fan","sewing machine",
            "ওভেন","ফ্যান","ওয়াশিং মেশিন"
        ));
        KW.put(ProductCategory.KITCHEN, kw(
            "pan","frying pan","non stick","cookware","cookware set","knife","knife set","cutlery","utensil",
            "dinner set","plate","bowl","mug","karai","kadai","casserole","hotpot","tiffin","water bottle","flask",
            "thermos","chopping board","হাঁড়ি","রান্নার সরঞ্জাম"
        ));
        KW.put(ProductCategory.FASHION, kw(
            "saree","panjabi","kurta","kurti","shirt","t-shirt","jeans","pant","trouser","shoe","sneaker","sandal",
            "loafer","heels","keds","slipper","wallet","handbag","sunglass","cap","jacket","sweater","hoodie",
            "dupatta","salwar","kameez","blouse","lehenga","jamdani","three piece","two piece","abaya","hijab",
            "borka","fatua","belt","tie","scarf","shawl","পোশাক","জামা","শাড়ি","পাঞ্জাবি","জুতা","ব্যাগ"
        ));
        KW.put(ProductCategory.BEAUTY, kw(
            "cream","lotion","moisturizer","serum","lipstick","mascara","foundation","face wash","face mask",
            "sheet mask","shampoo","conditioner","perfume","fragrance","deodorant","cosmetic","makeup","skincare",
            "sunscreen","sunblock","toner","body wash","hair oil","hair color","trimmer","shaver","kajal","eyeliner",
            "bb cream","cc cream","ক্রিম","প্রসাধনী"
        ));
        KW.put(ProductCategory.BOOK, kw(
            "book","books","novel","poetry","story book","ebook","kobita","upanyash","textbook","guide book",
            "academic book","admission book","islamic book","quran","tafsir","hadith","children book","comic",
            "magazine","dictionary","bcs","ielts","বই","কোরআন","গল্পের বই","কবিতা","উপন্যাস"
        ));
        KW.put(ProductCategory.GROCERY, kw(
            "rice","oil","sugar","flour","atta","masala","dal","lentil","chinigura","basmati","miniket","tea",
            "coffee","milk","milk powder","biscuit","chocolate","sauce","ketchup","noodles","pasta","ghee","honey",
            "mustard oil","soybean oil","dates","khejur","nuts","cashew","almond","muri","chira","gur","detergent",
            "soap","toothpaste","tissue","spice","salt","তেল","চাল","চিনি","ডাল","আটা","মসলা","মধু","ঘি"
        ));
        KW.put(ProductCategory.BABY, kw(
            "diaper","diapers","nappy","baby diaper","pant diaper","pull ups","wet wipes","baby wipes",
            "baby food","formula","baby formula","infant formula","infant milk","stroller","baby walker",
            "feeder","feeding bottle","breast pump","baby lotion","baby shampoo","baby oil","baby powder",
            "baby soap","baby wash","baby cream","baby carrier","high chair","crib","cerelac",
            "শিশু","বেবি","ডায়াপার"
        ));
        KW.put(ProductCategory.SPORTS, kw(
            "cricket bat","cricket ball","football","basketball","jersey","football jersey","world cup jersey",
            "supporter jersey","national flag","hand flag","country flag","stick flag","car flag","bunting",
            "yoga mat","dumbbell","treadmill","gym","running shoe","cycle","bicycle","badminton","racket",
            "skipping rope","resistance band","protein","whey","supplement","camping","tent","fishing",
            "সাইকেল","ক্রিকেট","জার্সি","পতাকা"
        ));
        KW.put(ProductCategory.AUTOMOTIVE, kw(
            "car","bike","motorcycle","scooter","helmet","tyre","tire","engine oil","lubricant","spark plug",
            "car charger","dashcam","dash cam","car cover","seat cover","car perfume","car stereo","গাড়ি","বাইক","মোটরসাইকেল"
        ));
        KW.put(ProductCategory.FURNITURE, kw(
            "sofa","bed","mattress","dining table","center table","study table","computer table","office chair",
            "chair","wardrobe","cabinet","shelf","book shelf","shoe rack","tv cabinet","dressing table","drawer",
            "সোফা","টেবিল","চেয়ার","খাট","আলমারি"
        ));
        KW.put(ProductCategory.ACCESSORY, kw(
            "case","cover","back cover","phone case","mobile case","screen protector","tempered glass",
            "glass protector","charger","fast charger","wall charger","charging cable","usb cable","type c cable",
            "type-c cable","data cable","lightning cable","power bank","powerbank","adapter","otg","phone holder",
            "phone stand","mobile stand","selfie stick","ring light","stylus","watch strap","watch band","laptop bag",
            "laptop sleeve","laptop stand","mouse pad","mousepad","keyboard cover","sleeve","pouch","phone grip",
            "popsocket","cable organizer","cooling pad","usb hub","type c hub","converter","airpods case","phone skin",
            "card holder","phone cooler","cooling fan","powerport","power port","wireless charger","magsafe",
            "laptop charger","camera bag","car mount","bike mount","tablet case","gaming trigger",
            // charger/cable/hub items that name themselves by spec or brand, not "charger"
            "gan charger","pd charger","usb c hub","usb-c hub","usb c to hdmi","usb-c to hdmi","type c hub",
            "docking station","power strip","extension socket","airtag","laptop backpack","card reader",
            "acefast","ugreen","wiwu","ldnio","baseus charger","anker charger",
            // input peripherals are computer accessories (often tagged gaming/stationery by brand)
            "mouse","wireless mouse","gaming mouse","keyboard","wireless keyboard","bluetooth keyboard",
            "mechanical keyboard","gaming keyboard","keypad","webcam","trackpad","stylus pen","s pen",
            "apple pencil","smart pen","presentation remote","presenter","graphics tablet","kvm switch",
            "কভার","কেস","চার্জার","ক্যাবল","পাওয়ার ব্যাংক"
        ));
        KW.put(ProductCategory.HEALTH, kw(
            "bp machine","blood pressure machine","glucometer","thermometer","pulse oximeter","oximeter","nebulizer",
            "first aid","hand sanitizer","sanitizer","vitamin","medicine","wheelchair","walking stick","hearing aid",
            "weight scale","weighing machine","heating pad","ওষুধ","মাস্ক"
        ));
        KW.put(ProductCategory.TOYS, kw(
            "toy","toys","remote control car","rc car","lego","building blocks","doll","puzzle","board game","ludo",
            "action figure","soft toy","teddy bear","kids cycle","খেলনা","পুতুল",
            "drone toy","toy drone","rc drone","kids toy"
        ));
        KW.put(ProductCategory.STATIONERY, kw(
            "pen","ball pen","gel pen","pencil","diary","a4 paper","printing paper","file","folder","marker",
            "highlighter","stapler","sticky note","calculator","geometry box","exercise book","khata","কলম","খাতা"
        ));
        KW.put(ProductCategory.POWER_BACKUP, kw(
            "ips","ups","inverter","solar panel","solar","generator","battery backup","power backup","ips battery",
            "voltage stabilizer","stabilizer","solar charge controller","আইপিএস","জেনারেটর"
        ));
        KW.put(ProductCategory.LIGHTING, kw(
            "led bulb","light bulb","tube light","led light","led strip","chandelier","ceiling light","wall light",
            "spot light","flood light","table lamp","night light","fairy light","switch socket","mcb",
            "circuit breaker","extension cord","multiplug","বাতি","লাইট"
        ));
        KW.put(ProductCategory.TOOLS, kw(
            "drill machine","drill","screwdriver","wrench","spanner","hammer","plier","tool kit","tool box",
            "grinder machine","welding machine","measuring tape","soldering iron","multimeter","hand saw",
            "power tool","hardware","ড্রিল"
        ));
        KW.put(ProductCategory.PET, kw(
            "pet food","dog food","cat food","fish food","pet shampoo","cat litter","aquarium","bird cage",
            "pet toy","leash","pet bed","বিড়াল","কুকুর","পোষা প্রাণী"
        ));
        KW.put(ProductCategory.MUSICAL, kw(
            "guitar","acoustic guitar","electric guitar","piano","keyboard piano","harmonium","tabla","drum kit",
            "ukulele","violin","flute","amplifier","mixer console","সেতার","গিটার","হারমোনিয়াম","তবলা"
        ));
        KW.put(ProductCategory.EYEWEAR, kw(
            "eyeglass","eye glass","spectacles","power glass","reading glass","contact lens","prescription glass",
            "blue light glass","optical frame"
        ));
        KW.put(ProductCategory.JEWELLERY, kw(
            "jewellery","jewelry","gold","silver","diamond ring","necklace","earring","bracelet","bangle",
            "pendant","ring gold","ornament","nupur","payel","গহনা","অলংকার","সোনা"
        ));

        // brand → category affinity (a brand can map to multiple categories)
        addBrand("walton", ProductCategory.AC, ProductCategory.REFRIGERATOR, ProductCategory.APPLIANCE, ProductCategory.TV, ProductCategory.SMARTPHONE);
        addBrand("samsung", ProductCategory.SMARTPHONE, ProductCategory.TV, ProductCategory.APPLIANCE, ProductCategory.TABLET, ProductCategory.SMARTWATCH, ProductCategory.REFRIGERATOR, ProductCategory.AC, ProductCategory.MONITOR, ProductCategory.STORAGE);
        addBrand("lg",       ProductCategory.TV, ProductCategory.REFRIGERATOR, ProductCategory.AC, ProductCategory.APPLIANCE, ProductCategory.MONITOR);
        addBrand("apple",    ProductCategory.SMARTPHONE, ProductCategory.LAPTOP, ProductCategory.TABLET, ProductCategory.HEADPHONE, ProductCategory.SMARTWATCH);
        addBrand("xiaomi",   ProductCategory.SMARTPHONE, ProductCategory.SMARTWATCH, ProductCategory.HEADPHONE, ProductCategory.APPLIANCE, ProductCategory.TV);
        addBrand("redmi",    ProductCategory.SMARTPHONE);
        addBrand("poco",     ProductCategory.SMARTPHONE);
        addBrand("oppo",     ProductCategory.SMARTPHONE);
        addBrand("vivo",     ProductCategory.SMARTPHONE);
        addBrand("iqoo",     ProductCategory.SMARTPHONE);
        addBrand("realme",   ProductCategory.SMARTPHONE);
        addBrand("oneplus",  ProductCategory.SMARTPHONE);
        addBrand("google",   ProductCategory.SMARTPHONE);
        addBrand("nothing",  ProductCategory.SMARTPHONE, ProductCategory.HEADPHONE);
        addBrand("honor",    ProductCategory.SMARTPHONE, ProductCategory.LAPTOP);
        addBrand("huawei",   ProductCategory.SMARTPHONE, ProductCategory.SMARTWATCH, ProductCategory.NETWORKING);
        addBrand("nokia",    ProductCategory.SMARTPHONE);
        addBrand("motorola", ProductCategory.SMARTPHONE);
        addBrand("symphony", ProductCategory.SMARTPHONE);
        addBrand("itel",     ProductCategory.SMARTPHONE);
        addBrand("tecno",    ProductCategory.SMARTPHONE);
        addBrand("infinix",  ProductCategory.SMARTPHONE);
        addBrand("lava",     ProductCategory.SMARTPHONE);
        addBrand("asus",     ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.GAMING, ProductCategory.MONITOR, ProductCategory.NETWORKING);
        addBrand("lenovo",   ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.TABLET, ProductCategory.MONITOR);
        addBrand("hp",       ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.PRINTER, ProductCategory.MONITOR);
        addBrand("dell",     ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.MONITOR);
        addBrand("msi",      ProductCategory.LAPTOP, ProductCategory.GAMING, ProductCategory.MONITOR, ProductCategory.DESKTOP);
        addBrand("acer",     ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.MONITOR);
        addBrand("gigabyte", ProductCategory.LAPTOP, ProductCategory.DESKTOP, ProductCategory.MONITOR);
        addBrand("benq",     ProductCategory.MONITOR);
        addBrand("viewsonic",ProductCategory.MONITOR);
        addBrand("aoc",      ProductCategory.MONITOR);
        addBrand("corsair",  ProductCategory.DESKTOP, ProductCategory.GAMING, ProductCategory.ACCESSORY);
        addBrand("logitech", ProductCategory.ACCESSORY, ProductCategory.GAMING);
        addBrand("razer",    ProductCategory.GAMING, ProductCategory.ACCESSORY);
        addBrand("a4tech",   ProductCategory.ACCESSORY);
        addBrand("rapoo",    ProductCategory.ACCESSORY);
        addBrand("fantech",  ProductCategory.GAMING, ProductCategory.ACCESSORY);
        addBrand("havit",    ProductCategory.HEADPHONE, ProductCategory.ACCESSORY);
        addBrand("baseus",   ProductCategory.ACCESSORY);
        addBrand("remax",    ProductCategory.ACCESSORY);
        addBrand("ugreen",   ProductCategory.ACCESSORY, ProductCategory.NETWORKING);
        addBrand("joyroom",  ProductCategory.ACCESSORY);
        addBrand("anker",    ProductCategory.ACCESSORY, ProductCategory.HEADPHONE);
        addBrand("sony",     ProductCategory.HEADPHONE, ProductCategory.TV, ProductCategory.CAMERA, ProductCategory.GAMING);
        addBrand("bose",     ProductCategory.HEADPHONE);
        addBrand("jbl",      ProductCategory.HEADPHONE);
        addBrand("edifier",  ProductCategory.HEADPHONE);
        addBrand("boat",     ProductCategory.HEADPHONE);
        addBrand("marshall", ProductCategory.HEADPHONE);
        addBrand("canon",    ProductCategory.CAMERA, ProductCategory.PRINTER);
        addBrand("nikon",    ProductCategory.CAMERA);
        addBrand("gopro",    ProductCategory.CAMERA);
        addBrand("dji",      ProductCategory.CAMERA);
        addBrand("transcend",ProductCategory.STORAGE);
        addBrand("sandisk",  ProductCategory.STORAGE);
        addBrand("seagate",  ProductCategory.STORAGE);
        addBrand("kingston", ProductCategory.STORAGE, ProductCategory.DESKTOP);
        addBrand("adata",    ProductCategory.STORAGE, ProductCategory.DESKTOP);
        addBrand("tp-link",  ProductCategory.NETWORKING);
        addBrand("tplink",   ProductCategory.NETWORKING);
        addBrand("tenda",    ProductCategory.NETWORKING);
        addBrand("mikrotik", ProductCategory.NETWORKING);
        addBrand("netgear",  ProductCategory.NETWORKING);
        addBrand("epson",    ProductCategory.PRINTER);
        addBrand("brother",  ProductCategory.PRINTER);
        addBrand("hikvision",ProductCategory.SECURITY);
        addBrand("dahua",    ProductCategory.SECURITY);
        addBrand("ezviz",    ProductCategory.SECURITY);
        addBrand("daikin",   ProductCategory.AC);
        addBrand("gree",     ProductCategory.AC);
        addBrand("midea",    ProductCategory.AC, ProductCategory.APPLIANCE);
        addBrand("panasonic",ProductCategory.AC, ProductCategory.APPLIANCE, ProductCategory.TV);
        addBrand("haier",    ProductCategory.AC, ProductCategory.REFRIGERATOR, ProductCategory.APPLIANCE);
        addBrand("hitachi",  ProductCategory.REFRIGERATOR, ProductCategory.AC, ProductCategory.APPLIANCE);
        addBrand("whirlpool",ProductCategory.REFRIGERATOR, ProductCategory.APPLIANCE);
        addBrand("toshiba",  ProductCategory.TV, ProductCategory.APPLIANCE, ProductCategory.STORAGE);
        addBrand("tcl",      ProductCategory.TV, ProductCategory.AC);
        addBrand("hisense",  ProductCategory.TV, ProductCategory.AC, ProductCategory.REFRIGERATOR);
        addBrand("singer",   ProductCategory.APPLIANCE, ProductCategory.TV, ProductCategory.REFRIGERATOR);
        addBrand("vision",   ProductCategory.APPLIANCE, ProductCategory.TV, ProductCategory.REFRIGERATOR);
        addBrand("conion",   ProductCategory.APPLIANCE, ProductCategory.AC);
        addBrand("marcel",   ProductCategory.APPLIANCE, ProductCategory.TV, ProductCategory.REFRIGERATOR);
        addBrand("minister", ProductCategory.APPLIANCE, ProductCategory.REFRIGERATOR, ProductCategory.TV);
        addBrand("jamuna",   ProductCategory.APPLIANCE, ProductCategory.TV, ProductCategory.REFRIGERATOR);
        addBrand("butterfly",ProductCategory.APPLIANCE);
        addBrand("rfl",      ProductCategory.APPLIANCE, ProductCategory.KITCHEN, ProductCategory.FURNITURE);
        addBrand("philips",  ProductCategory.APPLIANCE, ProductCategory.BEAUTY);
        addBrand("dyson",    ProductCategory.APPLIANCE);
        addBrand("aarong",   ProductCategory.FASHION, ProductCategory.BEAUTY);
        addBrand("yellow",   ProductCategory.FASHION);
        addBrand("ecstasy",  ProductCategory.FASHION);
        addBrand("fabrilife",ProductCategory.FASHION);
        addBrand("bata",     ProductCategory.FASHION);
        addBrand("apex",     ProductCategory.FASHION);
        addBrand("nike",     ProductCategory.FASHION, ProductCategory.SPORTS);
        addBrand("adidas",   ProductCategory.FASHION, ProductCategory.SPORTS);
        addBrand("puma",     ProductCategory.FASHION, ProductCategory.SPORTS);
        addBrand("nivea",    ProductCategory.BEAUTY);
        addBrand("cetaphil", ProductCategory.BEAUTY);
        addBrand("loreal",   ProductCategory.BEAUTY);
        addBrand("lakme",    ProductCategory.BEAUTY);
        addBrand("maybelline",ProductCategory.BEAUTY);
        addBrand("garnier",  ProductCategory.BEAUTY);
        addBrand("himalaya", ProductCategory.BEAUTY, ProductCategory.HEALTH);
        addBrand("pran",     ProductCategory.GROCERY);
        addBrand("radhuni",  ProductCategory.GROCERY);
        addBrand("nescafe",  ProductCategory.GROCERY);
        addBrand("nestle",   ProductCategory.GROCERY);
        addBrand("ispahani", ProductCategory.GROCERY);
        addBrand("garmin",   ProductCategory.SMARTWATCH, ProductCategory.SPORTS);
        addBrand("fitbit",   ProductCategory.SMARTWATCH);
        addBrand("amazfit",  ProductCategory.SMARTWATCH);
        addBrand("casio",    ProductCategory.SMARTWATCH, ProductCategory.FASHION);
        addBrand("humayun ahmed", ProductCategory.BOOK);
        addBrand("rokomari", ProductCategory.BOOK);
        // power backup / electrical
        addBrand("luminous",  ProductCategory.POWER_BACKUP);
        addBrand("sukam",     ProductCategory.POWER_BACKUP);
        addBrand("apc",       ProductCategory.POWER_BACKUP);
        addBrand("rahimafrooz", ProductCategory.POWER_BACKUP, ProductCategory.AUTOMOTIVE);
        addBrand("hamko",     ProductCategory.POWER_BACKUP, ProductCategory.AUTOMOTIVE);
        addBrand("energypac", ProductCategory.POWER_BACKUP, ProductCategory.LIGHTING);
        addBrand("superstar", ProductCategory.LIGHTING, ProductCategory.APPLIANCE);
        addBrand("transtec",  ProductCategory.LIGHTING, ProductCategory.APPLIANCE);
        addBrand("click",     ProductCategory.LIGHTING);
        // tools
        addBrand("bosch",     ProductCategory.TOOLS, ProductCategory.APPLIANCE);
        addBrand("dewalt",    ProductCategory.TOOLS);
        addBrand("makita",    ProductCategory.TOOLS);
        addBrand("stanley",   ProductCategory.TOOLS);
        addBrand("ingco",     ProductCategory.TOOLS);
        addBrand("total tools", ProductCategory.TOOLS);
        // musical
        addBrand("yamaha",    ProductCategory.MUSICAL);
        addBrand("fender",    ProductCategory.MUSICAL);
        addBrand("givson",    ProductCategory.MUSICAL);
        // pet
        addBrand("pedigree",  ProductCategory.PET);
        addBrand("whiskas",   ProductCategory.PET);
        addBrand("drools",    ProductCategory.PET);
        // baby & kids — many baby products don't say "baby" in the name, so the
        // brand carries the signal (e.g. "Pampers Pant System Large").
        addBrand("pampers",   ProductCategory.BABY);
        addBrand("huggies",   ProductCategory.BABY);
        addBrand("mamypoko",  ProductCategory.BABY);
        addBrand("mamy poko", ProductCategory.BABY);
        addBrand("lactogen",  ProductCategory.BABY);
        addBrand("cerelac",   ProductCategory.BABY);
        addBrand("chicco",    ProductCategory.BABY);
        addBrand("johnson's baby", ProductCategory.BABY);
        addBrand("johnsons baby",  ProductCategory.BABY);
        // eyewear
        addBrand("rayban",    ProductCategory.EYEWEAR, ProductCategory.FASHION);
        // jewellery
        addBrand("apan jewellers", ProductCategory.JEWELLERY);
        addBrand("amin jewellers", ProductCategory.JEWELLERY);
    }

    private static void addBrand(String brand, ProductCategory... cats) {
        BRAND_CATEGORIES.put(brand, new HashSet<>(Arrays.asList(cats)));
    }

    // ====== Aho-Corasick automata built once at startup ======
    // One automaton matches every (category × keyword) pair in a single pass
    // over the query. Payload encodes weight (3 for multi-word, 2 for token;
    // accessories get 5/4 so "iPhone case" beats SMARTPHONE) and the category.
    private AhoCorasick<KwHit> keywordAutomaton;
    private AhoCorasick<BrandHit> brandAutomaton;

    private record KwHit(ProductCategory category, int weight, boolean multiWord) {}
    private record BrandHit(String brand, Set<ProductCategory> categories) {}

    @PostConstruct
    void buildAutomata() {
        keywordAutomaton = new AhoCorasick<>();
        for (Map.Entry<ProductCategory, Set<String>> e : KW.entrySet()) {
            ProductCategory cat = e.getKey();
            for (String kw : e.getValue()) {
                boolean multi = kw.contains(" ");
                int weight;
                if (cat == ProductCategory.ACCESSORY) {
                    // "iPhone case" must beat SMARTPHONE.
                    weight = multi ? 5 : 4;
                } else if (cat == ProductCategory.BABY || cat == ProductCategory.PET) {
                    // A compound keyword like "baby lotion" / "pet shampoo" /
                    // "baby food" must beat the generic grocery/beauty word it
                    // contains ("lotion", "shampoo", "milk"+"rice"). Single tokens
                    // stay at 2 so ambiguous ones ("formula" in "Cocoa Butter
                    // Formula", "feeder") don't hijack unrelated products.
                    weight = multi ? 5 : 2;
                } else {
                    weight = multi ? 3 : 2;
                }
                if (CONTEXT_WORDS.contains(kw)) weight = 1;
                keywordAutomaton.add(kw, new KwHit(cat, weight, multi));
            }
        }
        keywordAutomaton.build();

        brandAutomaton = new AhoCorasick<>();
        for (Map.Entry<String, Set<ProductCategory>> e : BRAND_CATEGORIES.entrySet()) {
            brandAutomaton.add(e.getKey(), new BrandHit(e.getKey(), e.getValue()));
        }
        brandAutomaton.build();

        int kwCount = KW.values().stream().mapToInt(Set::size).sum();
        log.info("QueryClassifier: built Aho-Corasick with {} keywords + {} brands across {} categories",
                kwCount, BRAND_CATEGORIES.size(), KW.size());
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
        // Per-category brand bonus. A brand that makes exactly ONE kind of thing
        // (hikvision→security, tp-link→networking, redmi→phones) is the strongest
        // signal in the whole name and gets +3 — it must beat a stray generic
        // keyword (the "…ColorVu **Audio**… Camera" filed under Headphones bug).
        // Diversified brands (samsung, xiaomi) stay at +1: they say little.
        Map<ProductCategory, Integer> brandBonus = new EnumMap<>(ProductCategory.class);
        for (AhoCorasick.Hit<BrandHit> hit : brandAutomaton.findAll(normalized)) {
            detectedBrands.add(hit.payload().brand());
            int w = hit.payload().categories().size() == 1 ? 3 : 1;
            for (ProductCategory c : hit.payload().categories()) {
                brandBonus.merge(c, w, Integer::max);
            }
        }

        Map<ProductCategory, Integer> scores = new EnumMap<>(ProductCategory.class);
        boolean accessoryHit = false;
        for (AhoCorasick.Hit<KwHit> hit : keywordAutomaton.findAll(normalized)) {
            scores.merge(hit.payload().category(), hit.payload().weight(), Integer::sum);
            // An explicit accessory keyword ("case", "screen protector", "charging
            // cable", "laptop bag"…) trumps the parent category, even when the
            // product name repeats the parent keyword ("iPhone case for iPhone 15").
            if (hit.payload().category() == ProductCategory.ACCESSORY) accessoryHit = true;
        }
        brandBonus.forEach((c, w) -> scores.merge(c, w, Integer::sum));

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

        // Accessory trump: an explicit accessory keyword wins the primary slot.
        if (accessoryHit) {
            finalCategories.remove(ProductCategory.ACCESSORY);
            finalCategories.add(0, ProductCategory.ACCESSORY);
        }

        // Out-of-scope object override: if the name plainly names a vacuum/blender/
        // etc., that wins the primary slot outright — even over a same-brand audio
        // keyword — so the category-focus gate drops it instead of shelving a vacuum
        // under "Headphones & Audio". Whole-word (token) match, so "vacuum" can't hit
        // a substring. ponytail: a targeted override, not a general reweighting.
        for (String tok : tokens) {
            if (HARD_OUT_OF_SCOPE.contains(tok)) {
                finalCategories.remove(ProductCategory.APPLIANCE);
                finalCategories.add(0, ProductCategory.APPLIANCE);
                break;
            }
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
