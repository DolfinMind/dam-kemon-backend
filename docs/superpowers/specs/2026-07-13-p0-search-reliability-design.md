# P0 search reliability — design

Date: 2026-07-13 · Status: approved

## Problem

Production returns products for broad category queries but zero results for
specific queries whose products exist, including `iphone 14`, `samsung galaxy`,
`galaxy s24`, `vivobook 15`, and `laptop i5`. `/actuator/health` is consequently
`DOWN` through the existing synthetic search monitor.

The failure is in candidate recall, before ranking or the frontend. With Atlas
Search disabled, the main search depends on Mongo `$text` plus the in-memory
trigram index. `$text` failures are swallowed and the trigram index starts empty,
then rebuilds asynchronously from full `Product` documents, including large
price arrays. If both sources are unavailable, the API incorrectly reports a
valid empty result. Autocomplete still finds the same products through its
separate regex path.

Unanchored regex recall is not a safe fallback: it was removed after
full-collection scans caused CPU spikes and web-JVM crashes.

## Decision

Make the existing trigram index the reliable baseline recall source:

- Build it from a lightweight `id + name + brands` Mongo projection, never
  `ProductRepository.findAll()`.
- Store product IDs in trigram hits. After matching, fetch the bounded hit set
  through one `findAllById` query; normal ranking remains unchanged.
- Build synchronously before the application becomes ready. Scheduled rebuilds
  continue to build a replacement and atomically swap only after success.
- Keep Mongo `$text` as a secondary candidate source. Do not enable Atlas Search
  or restore collection-scanning regex fallbacks in this repair.
- If the catalog contains products but the trigram index cannot initialize,
  search must return an explicit service-unavailable response and health details
  must name the failed/empty index. It must never present infrastructure failure
  as "0 products found." An actually empty catalog may still return zero results.

This is a backend-only release with no schema migration and no new dependency.

## Components and data flow

1. `ProductRepository` exposes a lightweight search projection containing only
   ID, name, and brands. Existing indexer projections remain unchanged.
2. `TrigramSearchIndex` loads that projection at startup and on its existing
   refresh schedule. It records readiness, indexed-product count, last success,
   and the last failure message.
3. `TrigramIndex` continues calculating the same Jaccard and query-coverage
   scores, but its payload is the product ID rather than a full mutable product.
4. `CatalogSearchService` collects qualifying trigram IDs, loads those products
   once, merges them with `$text` candidates, and runs the existing visibility,
   relevance, accessory, facet, price, and ranking stages.
5. The existing synthetic monitor remains the end-to-end production guard.
   Health output also exposes trigram readiness and size so the next failure is
   diagnosable without guessing.

## Error handling

- A failed refresh keeps the last good index; readers never observe a partial or
  empty replacement.
- A failed initial build is logged with the underlying Mongo error and marks
  search unavailable when the catalog is non-empty.
- A `$text` failure remains non-fatal when trigram recall is ready, but is logged
  with enough context to identify the query path.
- Search-unavailable responses use the existing frontend 503 degradation path.
- No request is allowed to trigger a full-catalog scan or index rebuild.

## Verification

Automated tests must prove:

- Rebuild uses the lightweight projection and never `findAll()`.
- A failed refresh preserves the previous working index.
- With `$text` returning no candidates, real trigram recall returns appropriate
  products for brand-plus-model and typo queries.
- Trigram IDs are batch-loaded and still pass visibility, relevance, accessory,
  price-intent, ranking, and pagination behavior.
- A non-empty catalog with an unavailable index produces service-unavailable,
  while a healthy search with no relevant products produces a normal empty result.
- Existing search, recall, relevance, and typo tests remain green.

Before deployment: run the focused search tests, then `./gradlew test` and
`./gradlew bootJar`.

After deployment, the release is accepted only when:

- `iphone 14`, `samsung galaxy`, `galaxy s24`, `vivobook 15`, and `laptop i5`
  return relevant nonzero results from known production inventory.
- Broad category, typo, price-intent, accessory-toggle, pagination, and
  autocomplete smoke checks still work.
- Search requests do not produce 5xx responses, latency does not regress against
  the pre-deploy broad-query baseline, and `/actuator/health` returns `UP` after
  the synthetic monitor runs.

## Release and rollback

Ship this as one backend commit on `deployment-prod`. Do not bundle anonymous
comparison gating, post-auth intent recovery, saved-search placement, newsletter
modal behavior, or analytics cleanup; those form the next buyer-journey release.

Rollback is a revert of the backend implementation commit followed by redeploy.
There is no data migration to undo.
