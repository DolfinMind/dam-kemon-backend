# Damkemon acquisition and conversion audit handoff

Updated: 2026-07-15 (Asia/Dhaka)

## Objective

Audit the signed-in production admin and the complete public website, explain why Damkemon has low traffic and weak activation, remove trust/conversion blockers, improve organic discovery and catalog quality, and deploy a measurable shopper-to-seller funnel.

## Production evidence before the release

- The live admin reported 16 human visitors, 17 searches and a 29.4% search conversion rate on 2026-07-15. Traffic volume—not only on-site conversion—was the primary bottleneck.
- The user funnel reported 8 accounts and zero activated users even though rows showed searches and outbound clicks. Activation identity and click attribution were inconsistent.
- Google indexed duplicate `www` and apex homepages, utility/auth pages, `/api/r/...` redirect URLs, query-string browse pages, irrelevant catalog products, generic titles and fractional BDT prices.
- Common commercial searches such as `iphone 14`, `asus vivobook`, `galaxy s24`, `samsung` and `laptop i5` appeared in the admin no-results report.
- Exact `iphone 14` search ranked refurbished and compatibility/accessory inventory ahead of a normal device.
- Anonymous visitors were shown an incomplete offer set and asked to sign up before seeing core price-comparison value.
- The landing experience automatically triggered Google One Tap and an exit/newsletter modal. Newsletter clicks were recorded even when no message was sent.
- The public Protect flow claimed safety/escrow-style benefits that the product did not provide; its confirm/dispute controls were not wired into a supported buyer-resolution operation.
- Trust copy claimed the score used price history, delivery reliability, account age, stock depth and disputes. The actual calculation is an editorial baseline plus available buyer/listing ratings, trust votes, recommendations and a small verified-buyer adjustment.
- Synthetic seeded reviews were publicly visible and fed seller trust, undermining the credibility needed for conversion.
- The initial JavaScript bundle was roughly 981 kB (281 kB gzip), with visual libraries on the landing path.

## Shipped implementation

### Frontend

- Stable, indexable `/category/:category` pages; legacy `/browse?category=...` redirects to them.
- Route-specific titles, descriptions, canonicals and robots directives. Search, auth, account, seller-directory and utility pages are noindexed appropriately.
- Static `robots.txt` and `sitemap.xml`; one canonical host is documented in nginx.
- Native product links preserve the originating search query through product and outbound seller navigation.
- Anonymous visitors see the full seller comparison; the signup price gate was removed.
- Automatic Google One Tap, the exit-intent modal, the automatic newsletter modal, Beta badges and fake newsletter click tracking were removed.
- Protect UI and public route were removed. The old buyer-safety guide URL redirects to an honest risk-check guide.
- Seller-score, verdict, delivery and authenticity language now describes available evidence without promising safety or genuineness.
- Focused homepage/category messaging replaces complete-market and guaranteed-best-price claims.
- Secondary routes are lazy loaded; Three.js/framer-motion were removed. The main bundle is now about 342 kB (110 kB gzip).
- Admin polling runs every 30 seconds and only while visible.
- ESLint now matches the project’s TypeScript-checked JS/JSX approach and runs with zero errors.

### Backend

- Public product responses expose all visible offers instead of redacting the core comparison for anonymous visitors.
- Affiliate clicks are the authoritative activation event; anonymous identity and ObjectId/string joins are normalized.
- Public catalog/product/sitemap responses are limited to focused categories, valid product types and products with visible offers.
- Accessories, dummy/display items and compatibility listings are filtered for device intent. Used/refurbished/open-box inventory is penalized unless the shopper asks for that condition.
- Search/category behavior has regression coverage for common device queries and catalog visibility.
- Dynamic sitemap includes stable category pages and only focused products.
- Search/social crawlers receive server-rendered product title, canonical, robots, structured AggregateOffer data and visible seller prices. Human browsers still receive the SPA.
- `www` to apex canonical routing and crawler product routing are documented in nginx examples.
- Shop discovery probes a candidate before activation; publisher/domain blocklists and per-shop indexing deadlines prevent low-quality or hanging sources from polluting the catalog.
- Catalog remerge/classification removes irrelevant, accessory-misfiled and empty-offer inventory.
- Seeded reviews are excluded from public review/trust evidence and new synthetic publication is disabled.
- The unsupported Protect controller/service/model/repository stack was deleted, closing unused anonymous report/order/dispute endpoints.
- Admin visitor, activation, outbound and operator metrics were corrected and covered by tests.

## GitHub release checkpoint

- Frontend: `DolfinMind/dam-kemon-frontend`, branch `agent/conversion-funnel-audit`, commit `d092c8c`, PR `#2` targeting `deployment-prod`.
- Backend: `DolfinMind/dam-kemon-backend`, branch `agent/conversion-funnel-audit`, commit `0d02a8d` plus this handoff commit, PR `#2` targeting `deployment-prod`.
- `deployment-prod` triggers the production GitHub Actions workflow in both repositories. Never push or merge it accidentally.

## Validation completed before deployment

- Backend: `./gradlew test` passes.
- Frontend: `npm run build` and `npm run typecheck` pass.
- Frontend: `npm run lint` has zero errors (11 intentional fast-refresh warnings).
- Frontend: `npm audit --audit-level=high` reports zero vulnerabilities.
- `git diff --check` passes in both repositories.
- Chrome verified stable category routing, product/search query attribution, one canonical per page, noindex controls, full offer links, removed Protect routing and an interruption-free landing page after more than ten seconds.
- The root project knowledge graph was refreshed with `graphify update .`.

## Production verification checklist

After both production workflows complete:

1. Confirm `/`, `/category/laptops`, `/search?q=iphone+14`, a product page and a seller redirect all return expected content.
2. Confirm `/browse?category=laptops` and the old buyer-safety guide URL redirect to their stable replacements.
3. Confirm `/protect` returns to the homepage and `/api/protect/**` is absent.
4. Confirm search/auth/sellers routes are noindexed and public category/product pages have a single apex canonical.
5. Confirm anonymous product API responses include the cheapest named seller and all visible offers.
6. Confirm `iphone 14` ranks a normal phone ahead of refurbished inventory; an explicit `iphone 14 refurbished` query may rank refurbished inventory normally.
7. Confirm `robots.txt` and dynamic `sitemap.xml` use focused category/product URLs and exclude utility pages.
8. Confirm Googlebot receives server-rendered product HTML with JSON-LD and visible seller prices.
9. Confirm admin Users/Traffic records a new anonymous search and outbound click as activation without requiring signup.
10. Watch both GitHub Actions logs and application health before considering the release complete.

## Remaining work that is intentionally separate

- The historical synthetic review rows still exist in MongoDB, but are hidden and no longer affect public trust. Exact deletion/rollback is destructive and should use the local `seed_reviews_batch_seed_20260712T151608Z.json` sidecar only after an explicit database-cleanup decision.
- Google index cleanup is not immediate. Submit the new sitemap in Search Console, request reindexing for representative category/product pages, and monitor duplicate/excluded URLs for several weeks.
- Product/SEO fixes improve discoverability and conversion but do not create a distribution channel by themselves. The next growth phase should earn links and repeat demand through genuinely useful comparison pages, price alerts and editorial category guides—not fabricated activity.
- Use a 7-day and 30-day cohort after deployment. Primary KPIs: human organic visitors, searches per visitor, zero-result rate, product-view rate, outbound seller clicks per search, activated anonymous users, returning visitors and top landing pages.

## Guardrails

- Do not restore forced signup before price comparison, automatic One Tap, exit-intent modals, fake review publication or unsupported safety guarantees.
- Do not interpret a seller score as insurance, verification of an individual offer or a guarantee of delivery.
- Do not merge broad catalog categories back into public discovery until classification precision and visible-offer coverage are demonstrated.
- Do not use raw request counts as visitor counts; keep bot filtering and authoritative outbound-click attribution intact.
