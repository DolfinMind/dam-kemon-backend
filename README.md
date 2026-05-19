# dam-kemon-backend

> **Damkemon** is a Bangladesh price-comparison engine. We nightly index 60+
> BD e-commerce shops into MongoDB, then serve user searches instantly from
> the catalog with cross-shop dedup + grouped pricing.
>
> This repo is the **backend** — Spring Boot 4 on Java 17. The companion
> frontend lives at [Saif64/dam-kemon-frontend](https://github.com/Saif64/dam-kemon-frontend).

---

## How it works

```
   nightly cron (03:00)
        │
        ▼
┌─────────────────────┐    ┌─────────────────────┐    ┌─────────────────────┐
│  BulkIndexer        │───▶│  SitemapCrawler     │───▶│  ExtractorRegistry  │
│  loop over shops    │    │  shop's sitemap.xml │    │  schema.org/OG +    │
│  (60 in shops.json) │    │  → product URL list │    │  per-site overrides │
└─────────────────────┘    └─────────────────────┘    └─────────────────────┘
                                                                │
                                                                ▼
                                                        ┌──────────────┐
                                                        │  MinHashLSH  │
                                                        │  cross-shop  │
                                                        │  dedup       │
                                                        └──────────────┘
                                                                │
                                                                ▼
                                                        ┌──────────────┐
                                                        │   MongoDB    │
                                                        │   products   │
                                                        └──────┬───────┘
                                                               │
                                                               ▼
                                                  ┌───────────────────────┐
            user search ──────────────────────▶   │ CatalogSearchService  │
            (instant)                              │ Mongo $text + rank    │
                                                  └───────────────────────┘
```

**Key design choices:**

- **Pre-indexed, not live-scraped.** Search hits the DB, never the network.
  No rate-limit visibility to users, no flaky live extraction.
- **Sitemap-driven crawl.** Each shop publishes a `sitemap.xml` listing all
  product URLs — one fetch per shop discovers thousands of products.
- **schema.org / Open Graph generic extractor** parses any well-formed
  WooCommerce / Shopify / Magento product page without site-specific code.
  Daraz/Pickaboo/Startech have hand-tuned extractors as fast paths.
- **MinHash + LSH cross-shop dedup.** "Samsung Galaxy S24 12/256GB" on shop A
  is merged with "Galaxy S24 256GB" on shop B into one product with two
  prices.
- **Search runs against MongoDB `$text` index.** Falls back to case-
  insensitive regex when the index isn't built yet.
- **Caffeine cache** sits in front of search responses (60s TTL).

---

## Quick start

### Prereqs

- **JDK 17** (`java -version` should report 17+)
- **MongoDB** — Atlas connection string. Required.
- **Chromium (optional)** — auto-downloaded by Playwright on first run if
  `BROWSER_ENABLED=true`. Off by default.

### 1. Set up MongoDB Atlas (free M0, 512MB)

1. Sign up at <https://www.mongodb.com/cloud/atlas/register>.
2. Create a **Cluster** — pick the free **M0 Shared** tier.
3. **Database Access** → add a user with username + password (write it down).
4. **Network Access** → "Allow access from anywhere" (`0.0.0.0/0`) for dev,
   or your laptop's IP for tighter security.
5. **Cluster → Connect → Drivers → Java**, copy the URI. Replace `<password>`
   with the user password and append the db name `/damkemon` before the `?`.

```
mongodb+srv://damkemon:YOUR_PASSWORD@cluster0.abc123.mongodb.net/damkemon?retryWrites=true&w=majority
```

### 2. Configure

```bash
cp .env.example .env
# paste your MONGODB_URI
# leave the indexer/scraper tunables alone unless you want to tweak
```

### 3. Boot

```bash
./gradlew bootRun
```

The app starts on **http://localhost:8080** and on first boot will:

1. Load `shops.json` → upsert 70 shops into the `shops` collection.
2. Create text indexes on `products.name` + `products.description`.
3. Wait for either the nightly 03:00 cron or a manual `POST /api/admin/index/run`.

### 4. Trigger the first index manually

```bash
curl -X POST http://localhost:8080/api/admin/index/run
# poll until done
watch -n 5 'curl -s http://localhost:8080/api/admin/index/status'
```

Expect 30–90 minutes for the first full run (60 shops × ~500 products each,
with politeness throttles). Subsequent runs are incremental (URL match
→ price refresh instead of re-insert).

### 5. Search

```bash
curl 'http://localhost:8080/api/search?q=iphone+17'
curl 'http://localhost:8080/api/search/suggest?q=iph'
```

---

## Environments

Three Spring profiles + matching `.env` templates ship with the repo:

| Profile | YAML overlay | Env template | Use for |
|---|---|---|---|
| _(default)_ | `application.yml` | `.env.example` | local dev with `./gradlew bootRun` |
| `staging` | `application-staging.yml` | `.env.staging.example` | shared QA/staging box (lighter indexer, DEBUG logs, no Chromium) |
| `production` | `application-production.yml` | `.env.production.example` | live (full-throttle indexer, Chromium for SPA shops, WARN root log) |

Activate a profile via `SPRING_PROFILES_ACTIVE`:

```bash
# Staging
cp .env.staging.example .env.staging
# fill in MONGODB_URI etc.
export $(cat .env.staging | xargs) && SPRING_PROFILES_ACTIVE=staging ./gradlew bootRun

# Production (typically from a built jar in Docker / k8s)
SPRING_PROFILES_ACTIVE=production java -jar build/libs/dam-kemon-backend-0.0.1-SNAPSHOT.jar
```

The profile-specific YAML overlays the dev defaults — anything not declared
in `application-staging.yml` / `application-production.yml` falls through.
Env vars always win over YAML, so secrets stay outside source control.

**Never commit the populated `.env*` files** — `.gitignore` keeps every
`.env*` except `.env.example` and `.env.*.example`. In production you should
use a secrets manager (AWS / GCP / Vault) for `MONGODB_URI` rather than a
file on disk.

---

## REST surface

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/search?q=...` | DB-first search; cached 60s |
| `GET` | `/api/search/suggest?q=...&limit=8` | Autocomplete prefix matches |
| `GET` | `/api/products` | Paginated catalog |
| `GET` | `/api/products/{idOrSlug}` | Product detail (accepts ID or slug) |
| `GET` | `/api/products/{idOrSlug}/history` | Price history series |
| `GET` | `/api/compare?ids=A,B,C` | Side-by-side spec table |
| `GET` | `/api/sellers` | Facebook seller directory (manual) |
| `GET` | `/api/dashboard/stats` | Catalog stats |
| `POST`| `/api/admin/index/run` | Kick off nightly indexer manually |
| `GET` | `/api/admin/index/status` | Last/current indexer run summary |
| `GET` | `/api/admin/shops` | All shops + per-shop last-indexed stats |
| `POST`| `/api/scrape` | Legacy — now just triggers the indexer |

---

## Environment variables

Full list in [`.env.example`](.env.example). Required:

| Var | Purpose |
|---|---|
| `MONGODB_URI` | Atlas connection string. Without it the app boots but does nothing useful. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated. Defaults to the Vite dev server. |

Indexer tunables:

| Var | Default | Purpose |
|---|---|---|
| `INDEXER_CRON` | `0 0 3 * * *` | When to auto-run. |
| `INDEXER_SCHEDULED` | `true` | Set `false` to disable nightly run. |
| `INDEXER_MAX_URLS_PER_SHOP` | `1500` | Sitemap cap. |
| `INDEXER_MAX_PRODUCTS_PER_SHOP` | `500` | Effective per-run product cap. |
| `INDEXER_PER_HOST_PARALLELISM` | `2` | Concurrent fetches against a single shop. |
| `INDEXER_GLOBAL_PARALLELISM` | `24` | Total concurrent fetches. |

---

## Repository layout

```
src/main/java/com/damKemon/dam/kemon/
├── DamKemonApplication.java     # @EnableCaching @EnableScheduling
├── config/                      # Security, CORS, Mongo, cache
├── controller/                  # SearchController, AdminController, ...
├── service/
│   ├── CatalogSearchService.java   # DB-first search (instant)
│   ├── CompareService.java         # /api/compare
│   ├── ProductService.java         # /api/products/{idOrSlug}
│   ├── PriceSnapshotScheduler.java # daily 04:00 price-history snapshot
│   └── ScrapingService.java        # legacy /api/scrape (triggers indexer)
├── indexer/                     # nightly catalog crawl
│   ├── ShopCatalogBootstrap.java   # seeds shops.json on boot
│   ├── SitemapCrawler.java         # sitemap.xml(.gz) → product URLs
│   ├── BulkIndexer.java            # orchestrator (per-host throttled)
│   └── IndexingScheduler.java      # @Scheduled cron
├── scraper/                     # per-URL product extraction
│   ├── BaseScraper.java            # HTTP fetch helpers
│   ├── BrowserFetcher.java         # Playwright + Chromium (optional)
│   ├── ProductExtractor.java       # interface
│   ├── ExtractorRegistry.java      # routes URL → extractor
│   ├── GenericProductExtractor.java   # schema.org/JSON-LD/OG fallback
│   └── impl/                       # Daraz, Pickaboo, Startech overrides
├── intelligence/                # query understanding + dedup
│   ├── QueryClassifier.java        # Aho-Corasick over 305 kw + 50 brands
│   ├── MinHashLSH.java             # cross-shop product dedup
│   ├── ResultValidator.java        # accessory filter + token coverage
│   ├── PriceParser.java            # ৳, commas, Bengali numerals
│   └── ProductCategory.java        # enum + plausible price ranges
├── model/                       # @Document entities
└── repository/                  # Spring Data interfaces

src/main/resources/
├── application.yml
└── shops.json                   # 60-shop BD catalog
```

---

## Useful Gradle tasks

```bash
./gradlew bootRun                 # run with dev-tools live reload
./gradlew test                    # JUnit 5 test suite
./gradlew bootJar                 # build runnable jar
```

---

## Roadmap

- **Phase 2**: auto-discover new shops (crawl e-cab.com.bd member directory),
  "submit your shop" form, ~300 shops.
- **Phase 3**: F-commerce (Facebook sellers) onboarding flow, price-drop
  alerts via Telegram, search relevance ML (typo tolerance, synonyms).
