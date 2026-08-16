# P0 Search Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore specific brand/model search without reintroducing catalog scans that can crash the web JVM.

**Architecture:** Build the existing trigram index synchronously from a lightweight Mongo projection and store product IDs instead of full documents. Batch-load the bounded hit set, keep `$text` as secondary recall, reject search while the required index is unavailable, and expose index state through existing health reporting.

**Tech Stack:** Java 17, Spring Boot 4, Spring Data MongoDB, JUnit 5, Mockito, Gradle.

## Global Constraints

- Computing and mobile catalog only; no public crawler/indexing copy.
- No Atlas enablement, regex recall scan, schema migration, or new dependency.
- Preserve the last good trigram index when refresh fails.
- Push `deployment-prod` only after focused tests, full tests, and `bootJar` pass.

---

### Task 1: Lightweight, observable trigram lifecycle

**Files:**
- Modify: `src/main/java/com/damKemon/dam/kemon/repository/ProductRepository.java`
- Modify: `src/main/java/com/damKemon/dam/kemon/intelligence/TrigramSearchIndex.java`
- Create: `src/test/java/com/damKemon/dam/kemon/intelligence/TrigramSearchIndexTest.java`

**Interfaces:**
- Produces: `findAllSearchDocuments(): List<Product>` with `_id`, `name`, `brands` only.
- Produces: `isReady(): boolean` and `status(): Map<String,Object>`.
- Preserves: `rebuild()`, `topK(...)`, `size()`, `isEnabled()`.

- [ ] **Step 1: Write failing lifecycle tests**

Cover projection use, last-good-index retention, and non-empty-catalog failure:

```java
@Test
void rebuildUsesProjectionAndStoresIds() {
    when(repo.findAllSearchDocuments()).thenReturn(List.of(Product.builder()
            .id("s24").name("Samsung Galaxy S24 Ultra")
            .brands(List.of("Samsung")).build()));
    TrigramSearchIndex index = enabledIndex(repo);
    index.rebuild();
    assertTrue(index.isReady());
    assertEquals("s24", index.topK("samsung galaxy", 5, 0).get(0).id());
    verify(repo, never()).findAll();
}

@Test
void failedRefreshKeepsLastGoodIndex() {
    Product phone = Product.builder().id("s24").name("Samsung Galaxy S24 Ultra").build();
    when(repo.findAllSearchDocuments()).thenReturn(List.of(phone))
            .thenThrow(new DataRetrievalFailureException("mongo unavailable"));
    TrigramSearchIndex index = enabledIndex(repo);
    index.rebuild();
    index.rebuild();
    assertTrue(index.isReady());
    assertEquals(1, index.size());
    assertEquals("DataRetrievalFailureException", index.status().get("lastFailure"));
}

@Test
void nonEmptyCatalogCannotBecomeReadyWithEmptyIndex() {
    when(repo.findAllSearchDocuments()).thenReturn(List.of());
    when(repo.count()).thenReturn(1L);
    TrigramSearchIndex index = enabledIndex(repo);
    index.rebuild();
    assertFalse(index.isReady());
}
```

`enabledIndex` constructs `TrigramSearchIndex` and sets `enabled=true` with `ReflectionTestUtils`.

- [ ] **Step 2: Run the new test and confirm failure**

```bash
./gradlew test --tests '*TrigramSearchIndexTest'
```

Expected: compilation/test failure because the projection and status methods do not exist and rebuild calls `findAll()`.

- [ ] **Step 3: Add the lightweight repository query**

```java
@Query(value = "{}", fields = "{ '_id' : 1, 'name' : 1, 'brands' : 1 }")
List<Product> findAllSearchDocuments();
```

- [ ] **Step 4: Implement synchronous startup and atomic status**

Make `TrigramSearchIndex` implement `ApplicationRunner`; remove `ApplicationReadyEvent`, `EventListener`, and `CompletableFuture`.

```java
private volatile boolean ready;
private volatile Instant lastSuccess;
private volatile String lastFailure;

@Override
public void run(ApplicationArguments args) {
    if (enabled) rebuild();
}

public synchronized void rebuild() {
    TrigramIndex next = new TrigramIndex();
    try {
        List<Product> rows = productRepository.findAllSearchDocuments();
        for (Product p : rows) {
            if (p.getId() == null || p.getName() == null) continue;
            String name = p.getName();
            if (p.getBrands() != null && !p.getBrands().isEmpty())
                name += " " + String.join(" ", p.getBrands());
            next.add(p.getId(), name, p.getId());
        }
        if (next.size() == 0 && (!rows.isEmpty() || productRepository.count() > 0))
            throw new IllegalStateException("catalog is non-empty but trigram index is empty");
        indexRef.set(next);
        ready = true;
        lastSuccess = Instant.now();
        lastFailure = null;
    } catch (RuntimeException e) {
        lastFailure = e.getClass().getSimpleName();
        log.warn("Trigram rebuild aborted, keeping previous index: {}", e.getMessage());
    }
}

public boolean isReady() { return !enabled || ready; }

public Map<String, Object> status() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("enabled", enabled);
    out.put("ready", isReady());
    out.put("size", size());
    if (lastSuccess != null) out.put("lastSuccess", lastSuccess.toString());
    if (lastFailure != null) out.put("lastFailure", lastFailure);
    return out;
}
```

Keep scheduled/manual rebuild calls unchanged. Log product count and duration after a successful swap.

- [ ] **Step 5: Run lifecycle tests**

```bash
./gradlew test --tests '*TrigramSearchIndexTest'
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 2: Batch-hydrate fuzzy IDs and reject unavailable search

**Files:**
- Modify: `src/main/java/com/damKemon/dam/kemon/service/CatalogSearchService.java`
- Modify: `src/test/java/com/damKemon/dam/kemon/service/CatalogSearchTypoRecallTest.java`
- Modify: `src/test/java/com/damKemon/dam/kemon/service/CatalogSearchServiceRecallTest.java`
- Create: `src/test/java/com/damKemon/dam/kemon/service/CatalogSearchAvailabilityTest.java`

**Interfaces:**
- Consumes: trigram hit `id()` and inherited `findAllById(Iterable<String>)`.
- Produces: HTTP 503 via Spring `ResponseStatusException` when recall is unavailable.

- [ ] **Step 1: Update fixtures and add a failing availability test**

Replace `when(repo.findAll())` in the real-trigram fixture:

```java
when(repo.findAllSearchDocuments()).thenReturn(catalog);
when(repo.findAllById(any())).thenAnswer(invocation -> {
    Iterable<String> requested = invocation.getArgument(0);
    Set<String> ids = new HashSet<>();
    requested.forEach(ids::add);
    return catalog.stream().filter(p -> ids.contains(p.getId())).toList();
});
```

Stub `when(trigram.isReady()).thenReturn(true)` in mock-trigram fixtures. Add:

```java
@Test
void unavailableTrigramReturnsServiceUnavailable() {
    TrigramSearchIndex trigram = mock(TrigramSearchIndex.class);
    when(trigram.isReady()).thenReturn(false);
    CatalogSearchService service = serviceWith(trigram);
    ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.search("iphone 14"));
    assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
}
```

`serviceWith` supplies mocks for the constructor; the readiness guard must run before their use.

- [ ] **Step 2: Run and confirm failure**

```bash
./gradlew test --tests '*CatalogSearchAvailabilityTest' --tests '*CatalogSearchTypoRecallTest'
```

Expected: availability test fails; typo tests fail because trigram payloads are IDs.

- [ ] **Step 3: Add the readiness guard**

At the start of the full `search(...)` overload:

```java
if (!trigram.isReady())
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Search is temporarily unavailable");
```

- [ ] **Step 4: Hydrate bounded fuzzy hits in one query**

```java
private List<Product> loadFuzzyProducts(List<TrigramIndex.Hit> hits) {
    List<String> ids = hits.stream().map(TrigramIndex.Hit::id).distinct().toList();
    if (ids.isEmpty()) return List.of();
    Map<String, Product> byId = new LinkedHashMap<>();
    for (Product p : productRepository.findAllById(ids))
        if (p.getId() != null) byId.put(p.getId(), p);
    List<Product> out = new ArrayList<>(ids.size());
    for (String id : ids) if (byId.containsKey(id)) out.add(byId.get(id));
    return out;
}
```

In `textOrRegexSearch`, filter hits by the existing Jaccard/coverage rules, call `loadFuzzyProducts` once, and merge the returned products. In `autocomplete`, replace the `payload instanceof Product` block with `loadFuzzyProducts(fuzzy)`, retaining visibility, deduplication, and limit checks.

- [ ] **Step 5: Run all search tests**

```bash
./gradlew test --tests '*CatalogSearch*' --tests '*TrigramSearchIndexTest'
```

Expected: `BUILD SUCCESSFUL` for specific model, typo, relevance, availability, pagination, and autocomplete paths.

- [ ] **Step 6: Commit Tasks 1 and 2 together**

```bash
git add src/main/java/com/damKemon/dam/kemon/repository/ProductRepository.java \
  src/main/java/com/damKemon/dam/kemon/intelligence/TrigramSearchIndex.java \
  src/main/java/com/damKemon/dam/kemon/service/CatalogSearchService.java \
  src/test/java/com/damKemon/dam/kemon/intelligence/TrigramSearchIndexTest.java \
  src/test/java/com/damKemon/dam/kemon/service/CatalogSearchTypoRecallTest.java \
  src/test/java/com/damKemon/dam/kemon/service/CatalogSearchServiceRecallTest.java \
  src/test/java/com/damKemon/dam/kemon/service/CatalogSearchAvailabilityTest.java
git commit -m "fix(search): restore reliable product recall"
```

---

### Task 3: Report index readiness and verify

**Files:**
- Modify: `src/main/java/com/damKemon/dam/kemon/config/SyntheticHealthIndicator.java`
- Create: `src/test/java/com/damKemon/dam/kemon/config/SyntheticHealthIndicatorTest.java`

**Interfaces:**
- Consumes: `TrigramSearchIndex.isReady()` and `status()`.
- Preserves: existing synthetic canary health behavior.

- [ ] **Step 1: Write failing health tests**

```java
@Test
void unavailableTrigramMakesHealthDownImmediately() {
    when(trigram.isReady()).thenReturn(false);
    when(trigram.status()).thenReturn(Map.of("ready", false, "size", 0));
    Health health = new SyntheticHealthIndicator(monitor, trigram).health();
    assertEquals(Status.DOWN, health.getStatus());
    assertEquals(trigram.status(), health.getDetails().get("trigram"));
}

@Test
void readyTrigramKeepsSyntheticUp() {
    when(trigram.isReady()).thenReturn(true);
    when(trigram.status()).thenReturn(Map.of("ready", true, "size", 50));
    when(monitor.latest()).thenReturn(Map.of("ok", true));
    assertEquals(Status.UP, new SyntheticHealthIndicator(monitor, trigram).health().getStatus());
}
```

- [ ] **Step 2: Run and confirm failure**

```bash
./gradlew test --tests '*SyntheticHealthIndicatorTest'
```

Expected: compilation failure because the health indicator does not accept trigram state.

- [ ] **Step 3: Include trigram status in health**

Inject `TrigramSearchIndex`. Return `DOWN` immediately when `!trigram.isReady()`. Otherwise preserve the current unknown/up/down synthetic logic and add `.withDetail("trigram", trigram.status())` to every result.

- [ ] **Step 4: Run focused and full verification**

```bash
./gradlew test --tests '*SyntheticHealthIndicatorTest' --tests '*CatalogSearch*' --tests '*TrigramSearchIndexTest'
./gradlew test
./gradlew bootJar
```

Expected: every command ends with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit health reporting**

```bash
git add src/main/java/com/damKemon/dam/kemon/config/SyntheticHealthIndicator.java \
  src/test/java/com/damKemon/dam/kemon/config/SyntheticHealthIndicatorTest.java
git commit -m "fix(health): report search index readiness"
```

---

### Task 4: Push and prove production recovery

**Files:** None.

- [ ] **Step 1: Confirm only intended commits will be pushed**

```bash
git status -sb
git log --oneline origin/deployment-prod..HEAD
```

Expected: only design, plan, and reviewed implementation commits are ahead; unrelated untracked files remain uncommitted.

- [ ] **Step 2: Push deployment**

```bash
git push origin deployment-prod
```

Expected: success and backend deployment workflow trigger.

- [ ] **Step 3: Prove known queries after deployment**

```bash
for q in 'iphone 14' 'samsung galaxy' 'galaxy s24' 'vivobook 15' 'laptop i5'; do
  curl -fsSG 'https://www.damkemon.com/api/search' \
    --data-urlencode "q=$q" --data 'size=5' |
    jq -e '.totalResults > 0 and (.products | length) > 0'
done
```

Expected: `true` for every query and exit zero.

- [ ] **Step 4: Prove surrounding behavior and health**

Smoke-check broad category, known typo, `phone under 20000`, pagination, accessory toggle, autocomplete, and response time. Then run:

```bash
curl -fsS https://www.damkemon.com/actuator/health | jq -e '.status == "UP"'
```

Expected: relevant nonzero results, no 5xx, no latency regression, working autocomplete, and health `true` after the synthetic run.

- [ ] **Step 5: If production fails, stop and diagnose**

Capture the failed query, deployment logs, trigram health details, and candidate-source behavior. Do not stack a second speculative patch; form and test one new hypothesis first.
