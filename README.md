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
| `GET` | `/api/search?q=...` | DB-first search; cached 60s; rate-limited per IP |
| `GET` | `/api/search/suggest?q=...&limit=8` | Autocomplete prefix matches |
| `GET` | `/api/products` | Paginated catalog |
| `GET` | `/api/products/{idOrSlug}` | Product detail (accepts ID or slug) |
| `GET` | `/api/products/{idOrSlug}/history` | Price history series |
| `GET` | `/api/compare?ids=A,B,C` | Side-by-side spec table |
| `GET` | `/api/sellers` | Facebook seller directory (manual) |
| `GET` | `/api/dashboard/stats` | Catalog stats |
| `GET` | `/api/stats/live` | Active anon users, searches in last 60s/24h |
| `GET` | `/api/stats/trending?limit=10` | Top search terms in last 24h |
| `GET` | `/api/stats/hot-drops?limit=12` | Products with current price ≥10% below 7-day peak |
| `POST`| `/api/events/view` | Anon product-view beacon (no PII) |
| `POST`| `/api/events/click` | Anon outbound-seller-click beacon |
| `POST`| `/api/shops/submit` | Public shop submission → `pending_shops` |
| `POST`| `/api/admin/index/run` | Kick off nightly indexer manually |
| `POST`| `/api/admin/index/retry` | Re-fire just the shops with `needsRetry=true` |
| `POST`| `/api/admin/index/shop/{slug}` | Re-index a single shop |
| `GET` | `/api/admin/index/status` | Last/current indexer run summary |
| `GET` | `/api/admin/shops` | All shops + per-shop health, recent runs, stats |
| `POST`| `/api/admin/shops/{slug}/status` | Operator override of shop status |
| `GET` | `/api/admin/pending-shops` | Awaiting-review submissions |
| `POST`| `/api/admin/pending-shops/{id}/approve` | Promote to `shops` collection |
| `POST`| `/api/admin/pending-shops/{id}/reject` | Reject with optional note |
| `POST`| `/api/admin/hot-drops/rebuild` | Manual hot-drops rebuild |
| `POST`| `/api/scrape` | Legacy — now just triggers the indexer |
| `POST`| `/api/auth/request-link` | Email-magic-link sign-in start (rate-limited per email) |
| `POST`| `/api/auth/verify` | Exchange `{email, token}` for a 30d JWT |
| `GET` | `/api/auth/me` | Current signed-in user from `Authorization: Bearer …` |
| `GET` | `/api/account/saved-searches` | Per-user saved searches |
| `POST`| `/api/account/saved-searches` | Save a new search to alert on |
| `GET` | `/api/account/wishlist` | Per-user wishlist with hydrated products |
| `POST`| `/api/account/wishlist` | Add a product to wishlist |
| `POST`| `/api/fcommerce/sellers/submit` | Public Facebook-shop self-listing |
| `POST`| `/api/fcommerce/sellers/{slug}/products/upload` | CSV upload of seller's inventory |
| `GET` | `/api/products/by-ids?ids=a,b,c` | Bulk hydration for the recently-viewed rail |
| `GET` | `/api/products/{id}/history/daily?days=30` | Forward-filled gap-free daily series |
| `POST`| `/api/admin/discover-shops` | Walk e-cab + BASIS, queue candidates into `pending_shops` |
| `GET` | `/api/admin/audit-log` | Last N admin-endpoint hits |
| `GET` | `/api/admin/stats/overview` | DAU/MAU + last indexer run |
| `GET` | `/api/admin/stats/zero-results` | Searches that returned nothing |
| `GET` | `/api/admin/stats/shop-ctr` | Click-through rate per shop |
| `GET` | `/api/admin/stats/top-products` | Top viewed + top clicked |
| `GET` | `/api/img?u=URL` | Image proxy with CDN-friendly cache headers |
| `GET` | `/sitemap.xml` | Sitemap of every product page + static routes |
| `GET` | `/robots.txt` | Crawler policy with sitemap link |
| `GET` | `/actuator/health` | Liveness + readiness (Mongo + `synthetic` canary) |
| `GET` | `/actuator/info` | App name + version |

**`/api/admin/*` requires `X-Admin-Key` header** when `ADMIN_API_KEY` is set
in the environment. Local dev leaves it blank (a `WARN` on boot reminds
you). Staging + production must set it:

```bash
ADMIN_API_KEY=$(openssl rand -hex 24)
curl -H "X-Admin-Key: $ADMIN_API_KEY" -X POST https://api/admin/index/run
```

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

Shipped (Phase 1): pre-indexed catalog over 69 BD shops, sitemap +
homepage-walk + Playwright discovery, schema.org/JSON-LD/OG/CSS extractor
chain, cross-shop MinHash/LSH dedup with normalised matching keys, DB-first
search with `$text` + partial-regex + substring fallback, autosuggest,
admin-key gate, actuator health, staging + production Spring profiles.

What's left to make this an honest product, ordered by impact.

### Phase 2 — operate the catalog

| | Item | Notes |
|---|---|---|
| ✅ | **Admin console** at `/admin` | React SPA — indexer controls, shop manager grid, pending-shops review, operator stats, audit log. Gated by an admin-role JWT or the legacy `X-Admin-Key` header. |
| ✅ | Per-shop quality scoring | Last 7 runs roll into `active / degraded / dormant`. Auto-disable after 3 consecutive failures. |
| ✅ | Retry queue for partial shops | Failed/empty shops flagged `needsRetry=true`; re-fired by `POST /api/admin/index/retry` and a 04:00 cron. |
| ✅ | Shop-discovery crawler | `ShopDiscoveryService` walks e-cab + BASIS directories, dedups against known shops, queues candidates into `pending_shops`. Trigger via `POST /api/admin/discover-shops`. |
| ✅ | "Submit your shop" public form | `POST /api/shops/submit` writes to `pending_shops`; admin approves at `/admin/pending-shops`. |
| ✅ | Price-history visualisation | New `GET /api/products/{id}/history/daily` returns a gap-free, forward-filled daily series; chart degrades gracefully to it when per-shop history is sparse. |
| ✅ | Drop the search-time `$text` index requirement | `AtlasSearchService` does a `$search` aggregation with fuzzy + autocomplete when `SEARCH_ATLAS_ENABLED=true`. Falls back to `$text` + regex transparently. |
| ✅ | F-commerce onboarding | Public `/fcommerce/signup` flow: register the page, upload a CSV of inventory (name, price, image, url, category). Products land in the main catalog with the seller's slug. |

### Phase 3 — analytics + telemetry

These are the user-traffic features you mentioned. None are wired yet.

**Anonymous event tracking** (no PII, no cookies-required):

| | Item | Where |
|---|---|---|
| ✅ | `search_event { query, totalResults, ts, anonId }` | `events` collection. Async write on every `/api/search` hit, TTL 30d. |
| ✅ | `click_event { productId, sellerSlug, anonId, ts }` | `POST /api/events/click` from `SearchProductCard` + `PriceComparisonTable` via `sendBeacon`. |
| ✅ | `view_event { productId, anonId, ts }` | `POST /api/events/view` fired on ProductDetail mount. |
| ✅ | Anonymous user id | `localStorage` UUID, sent on every request via `X-Anon-Id` header. |
| ✅ | Server-side rate limiter | In-memory token bucket per IP on `/api/search*` — `RATE_LIMIT_CAPACITY` / `RATE_LIMIT_REFILL_PER_SEC` envs. |

**Live counters on the public site** (driven by the event collection):

| | Item | Behaviour |
|---|---|---|
| ✅ | "X users searching now" pill on Home | Distinct `anonId` count of search events in the last 60s, served from `GET /api/stats/live`. |
| ✅ | Trending searches | Top search terms in the last 24h with hit counts, surfaced as `TrendingStrip` on Home. `GET /api/stats/trending`. |
| ✅ | "Hot drops" feed | Products whose current `lowestPrice` is ≥10% below the 7-day peak. Rebuilt nightly at 05:00. `GET /api/stats/hot-drops`. |
| ✅ | "Recently viewed" rail | localStorage keeps the last 12 product IDs, Home hydrates via `GET /api/products/by-ids`. |

**Operator-facing counters** (in the admin console at `/admin/stats`):

| | Item |
|---|---|
| ✅ | DAU / MAU (unique anonIds per day / month) — `GET /api/admin/stats/overview` |
| ✅ | Zero-result searches — `GET /api/admin/stats/zero-results` |
| ✅ | Click-through rate per shop — `GET /api/admin/stats/shop-ctr` |
| ✅ | Indexer run summary (latest) — shown on `/admin/indexer` |
| ✅ | Top products by view + by click — `GET /api/admin/stats/top-products` |
| ✅ | Synthetic monitor (canary searches) surfaced on `/actuator/health` |

### Admin console scope

`/admin` SPA gated by an admin-role JWT (issued via the magic-link flow)
or the legacy `X-Admin-Key` header.

| | Item |
|---|---|
| ✅ | Sign-in via magic-link flow — first registered user becomes admin automatically |
| ✅ | Indexer page: live status polling, "run nightly", "retry failed", "discover shops", "rebuild hot drops" |
| ✅ | Shop manager: per-shop health, recent runs, manual reindex, enable/disable |
| ✅ | Pending shops: review queue, one-click approve/reject for both submitted + auto-discovered shops |
| ✅ | Operator stats: DAU/MAU, zero-result leaderboard, CTR per shop, top viewed/clicked products |
| ✅ | Audit log: append-only record of every admin endpoint hit (TTL 90d) |

### Phase 4 — user accounts

| | Item |
|---|---|
| ✅ | Sign-up / sign-in via email magic link (no passwords). First user auto-promoted to `admin`. |
| ✅ | Saved searches — `/api/account/saved-searches`, surfaced on `/account` |
| ✅ | Price-drop alerts via email (daily cron diffs current vs `lastSeenLowest`) |
| ✅ | Wishlist — per-user, with heart toggle on ProductDetail |
| ⬜ | Per-user search history (visible only when signed in, never sold) |
| ⬜ | Google OAuth — requires external credentials, not yet wired |

### Phase 5 — SEO + growth

| | Item |
|---|---|
| ⬜ | Server-side render product detail pages (or pre-render via Vite SSG) |
| ✅ | `/sitemap.xml` of every product + static pages (served by the backend) |
| ⬜ | Open Graph image generator per product (so WhatsApp/FB shares look real) |
| ✅ | Schema.org `Product` markup on product pages → Google Shopping eligibility |
| ✅ | `robots.txt` policy with sitemap reference |
| ✅ | Bundle code-splitting (React.lazy + suspense on every non-hot-path route) |
| ✅ | Image proxy via `/api/img?u=...` — fixes mixed-content + hotlink-block, adds aggressive cache headers |

### Operational hardening (cross-cutting)

| | Item |
|---|---|
| ⬜ | Backup MongoDB Atlas nightly to S3 (Atlas free tier has no backups) |
| ✅ | Sentry wired via `spring-boot-starter-sentry-jakarta`; no-op until `SENTRY_DSN` is set |
| ✅ | Per-IP rate limit on `/api/search*` (in-memory token bucket — see `RateLimiter`) |
| ✅ | `/actuator/health` includes a `synthetic` indicator that flips DOWN when canary searches regress |
| ⬜ | Log shipping to Loki / Datadog (config-only; depends on infra) |
| ✅ | Synthetic monitoring fires every 15 min over multiple canary queries |
| ✅ | JUnit + Mockito coverage for `JwtService`, `AuthService`, `ShopHealthService`, `RateLimiter`, `SearchController`, `SubmitShopController` |
