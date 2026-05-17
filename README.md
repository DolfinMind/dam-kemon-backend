# dam-kemon-backend

> **Damkemon** is a Bangladesh price comparison engine. Search any product → we scan
> 10+ Bangladeshi e-commerce sites (Daraz, Pickaboo, Startech, Walton, Chaldal,
> Rokomari, BD-Shop, Othoba, Priyoshop, Ryans) plus Facebook sellers, validate
> the results, dedupe across sites, and surface the cheapest.
>
> This repo is the **backend** — Spring Boot 4 on Java 17. The companion
> frontend lives at [Saif64/dam-kemon-frontend](https://github.com/Saif64/dam-kemon-frontend).

---

## Stack

| Layer | Choice |
|---|---|
| Runtime | Java 17 (toolchain) |
| Framework | Spring Boot 4.0.6 (Web MVC, Security, Validation, Cache) |
| Build | Gradle (wrapper included) |
| Database | MongoDB (Atlas in prod, optional locally — the app degrades gracefully) |
| Scraping | jsoup for static HTML, Playwright + Chromium for JS-rendered sites (Daraz, Walton) |
| Cache | Caffeine, in-process, 10 min TTL on search responses |
| Scheduling | Spring `@Scheduled` — daily price snapshot at 03:00 |

---

## Quick start

### Prerequisites

- **JDK 17** (`java -version` should report 17+)
- **MongoDB** — Atlas connection string, or skip Mongo entirely; the app will still serve `/api/search` without persistence
- **Chromium** — auto-downloaded by Playwright on first run. Set `BROWSER_ENABLED=false` to disable JS-rendered scrapers on hosts without a browser

### 1. Configure environment

```bash
cp .env.example .env
# edit .env, set MONGODB_URI + CORS_ALLOWED_ORIGINS
```

Spring Boot picks up `.env` automatically via `spring.config.import`.

### 2. Run

```bash
./gradlew bootRun
```

The app starts on **http://localhost:8080**. First boot downloads Chromium (~150 MB) if `BROWSER_ENABLED=true`.

Smoke test:

```bash
curl http://localhost:8080/api/sites
curl "http://localhost:8080/api/search?q=iphone%2015"
```

### 3. Build a fat jar

```bash
./gradlew bootJar
java -jar build/libs/dam-kemon-backend-0.0.1-SNAPSHOT.jar
```

---

## Environment variables

Full list in [`.env.example`](.env.example). Required ones:

| Var | Purpose |
|---|---|
| `MONGODB_URI` | MongoDB connection string. App boots without it but persistence is disabled. |
| `CORS_ALLOWED_ORIGINS` | Comma-separated list. Defaults to the Vite dev server (`http://localhost:5173`). |
| `SERVER_PORT` | HTTP port, defaults `8080`. |
| `BROWSER_ENABLED` | `true` enables Playwright/Chromium. Set `false` on bare hosts. |

Scraper and browser tunables (timeouts, retries, per-host delay, cron) are all in `.env.example`.

---

## REST surface

| Method | Path | Notes |
|---|---|---|
| `GET` | `/api/search?q=...` | Live multi-site search. **Cached** 10 min (normalized key). |
| `GET` | `/api/products` | Paginated catalog |
| `GET` | `/api/products/{id}` | Product detail |
| `GET` | `/api/products/{id}/history` | Price history series |
| `GET` | `/api/products/{id}/reviews` | Aggregated reviews |
| `GET` | `/api/compare?ids=A,B,C` | Side-by-side spec table with per-row winner |
| `GET` | `/api/sites` | Registered scrapers + their categories |
| `GET` | `/api/sellers` | Facebook seller directory (filter by `category`, `city`, `verified`) |
| `GET` | `/api/sellers/{id}` | Seller detail |
| `GET` | `/api/dashboard/stats` | Cached aggregate stats |
| `POST` | `/api/scrape` | Trigger an async scrape job |
| `GET` | `/api/admin/cache/stats` | Cache hit ratio + Playwright stats |
| `DELETE` | `/api/admin/cache/search` | Manual flush |

---

## How a search request flows

```
client → SearchController → SearchService
                                ├── Caffeine cache (10 min, normalized key)
                                ├── QueryClassifier (Aho-Corasick over 305 kw + 50 brands)
                                ├── ScraperEngine
                                │     ├── routes by intent.categories → drops irrelevant sites
                                │     ├── fans out to N scrapers concurrently (5s per-scraper cap)
                                │     └── each scraper: jsoup or Playwright → CSS selectors → PriceParser
                                ├── ResultValidator (price-range + token-coverage + accessory blacklist)
                                ├── MinHash + LSH dedup (128 hashes / 32 bands)
                                └── MongoDB upsert (best-effort)
```

The routing step is what fixed *"Walton AC was returning Rokomari (a books site) at ৳0.101"*.

---

## Repository layout

```
src/main/java/com/damKemon/dam/kemon/
├── DamKemonApplication.java   # @EnableCaching @EnableScheduling
├── config/                    # Security, CORS, cache, Playwright bean
├── controller/                # REST endpoints (Search, Compare, Seller, Dashboard, Cache)
├── service/                   # SearchService (cached hot path), CompareService, schedulers
├── scraper/
│   ├── EcommerceScraper.java  # interface — getSupportedCategories(), search()
│   ├── BaseScraper.java       # retries, UA rotation, per-host throttle, jsoup fetch
│   ├── BrowserFetcher.java    # Playwright + Chromium singleton
│   ├── ScraperEngine.java     # intent-driven routing + concurrent fan-out
│   └── impl/                  # 10 site-specific scrapers
├── intelligence/
│   ├── QueryClassifier.java   # rule-based intent (category, brands, confidence)
│   ├── ProductCategory.java   # enum + plausible price ranges
│   ├── PriceParser.java       # ৳ / commas / Bengali numerals, rejects v0.101
│   ├── ResultValidator.java   # Jaccard + accessory blacklist + price-range
│   ├── MinHashLSH.java        # cross-site product dedup (WIRED)
│   ├── AhoCorasick.java       # single-pass multi-pattern keyword scan (WIRED)
│   ├── TrigramIndex.java      # typo-tolerant autocomplete (ready, not yet wired)
│   └── Shingler.java          # k-shingles, used by MinHashLSH + TrigramIndex
├── model/                     # @Document entities — Product, SitePrice, Review, Seller, ...
├── repository/                # Spring Data interfaces
└── dto/                       # request/response shapes
```

---

## Useful Gradle tasks

```bash
./gradlew bootRun                 # run with dev-tools live reload
./gradlew test                    # JUnit 5 test suite
./gradlew bootJar                 # build runnable jar
./gradlew dependencyInsight --dependency caffeine   # debug a dep
```

---

## Production notes

- Run behind a reverse proxy that routes `/api/*` to the JVM. The frontend bundle is served as static files.
- Set `BROWSER_ENABLED=false` if the host lacks Chromium and you can live without Daraz / Walton results.
- The daily `PriceSnapshotScheduler` writes one row per product per site at 03:00 server time. Disable with `PRICE_HISTORY_ENABLED=false`.
- One `CacheManager` backs all `@Cacheable` annotations; `unless = "totalResults == 0"` keeps empty failures out of the cache.

---

## See also

- [Frontend repo](https://github.com/Saif64/dam-kemon-frontend) — Vite + React UI
