# Loopback Ingest Without Admin Key Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the local Python crawler ingest through Java without `ADMIN_API_KEY` while preserving authentication on public and unrelated admin requests.

**Architecture:** Add one narrow exception in `AdminGateFilter` for the exact ingest path when the direct peer is loopback and no forwarding headers exist. Remove key handling from the Python transport; keep the backend key configuration unchanged for every other admin endpoint.

**Tech Stack:** Java 17, Spring Security/MockHttpServletRequest, Python 3.9, aiohttp, unittest.

## Global Constraints

- Only `/api/admin/catalog/ingest` receives the loopback exception.
- IPv4 and IPv6 loopback are allowed; forwarded requests are denied.
- Python continues calling `http://127.0.0.1:8080` by default.
- No new dependency or service.

---

### Task 1: Protect the keyless backend exception

**Files:**
- Create: `src/test/java/com/damKemon/dam/kemon/config/AdminGateFilterTest.java`
- Modify: `src/main/java/com/damKemon/dam/kemon/config/SecurityConfig.java`

**Interfaces:**
- Consumes: `SecurityConfig.AdminGateFilter(String expectedKey)`.
- Produces: direct loopback access to the exact ingest path without changing other gate behavior.

- [ ] **Step 1: Write failing filter tests**

Use Spring mock requests to assert: direct `127.0.0.1` ingest reaches the chain;
the same request with `X-Forwarded-For` returns 401; and another admin path from
loopback returns 401.

```java
@Test
void allowsOnlyDirectLoopbackIngestWithoutCredentials() throws Exception {
    MockHttpServletRequest direct = request("/api/admin/catalog/ingest", "127.0.0.1");
    MockHttpServletResponse directResponse = new MockHttpServletResponse();
    MockFilterChain directChain = new MockFilterChain();
    new SecurityConfig.AdminGateFilter("secret").doFilter(direct, directResponse, directChain);
    assertNotNull(directChain.getRequest());

    MockHttpServletRequest forwarded = request("/api/admin/catalog/ingest", "127.0.0.1");
    forwarded.addHeader("X-Forwarded-For", "203.0.113.10");
    MockHttpServletResponse forwardedResponse = new MockHttpServletResponse();
    new SecurityConfig.AdminGateFilter("secret").doFilter(
            forwarded, forwardedResponse, new MockFilterChain());
    assertEquals(401, forwardedResponse.getStatus());
}
```

- [ ] **Step 2: Verify the focused test fails**

Run:

```bash
./gradlew test --tests '*AdminGateFilterTest'
```

Expected: the direct-loopback assertion fails with HTTP 401.

- [ ] **Step 3: Add the minimal gate exception**

Before JWT/key checks, allow the request only when the path is exactly the ingest
path, `getRemoteAddr()` is `127.0.0.1`, `::1`, or the expanded IPv6 loopback, and
`Forwarded`, `X-Forwarded-For`, and `X-Real-IP` are all absent.

```java
if (isDirectLoopbackIngest(req, path)) {
    chain.doFilter(req, res);
    return;
}

private static boolean isDirectLoopbackIngest(HttpServletRequest req, String path) {
    String remote = req.getRemoteAddr();
    boolean loopback = "127.0.0.1".equals(remote) || "::1".equals(remote)
            || "0:0:0:0:0:0:0:1".equals(remote);
    return "/api/admin/catalog/ingest".equals(path) && loopback
            && req.getHeader("Forwarded") == null
            && req.getHeader("X-Forwarded-For") == null
            && req.getHeader("X-Real-IP") == null;
}
```

- [ ] **Step 4: Verify focused and full backend suites**

```bash
./gradlew test --tests '*AdminGateFilterTest'
./gradlew test
```

Expected: both builds succeed.

- [ ] **Step 5: Commit backend behavior**

```bash
git add src/main/java/com/damKemon/dam/kemon/config/SecurityConfig.java \
  src/test/java/com/damKemon/dam/kemon/config/AdminGateFilterTest.java
git commit -m "feat(security): allow direct loopback ingest"
```

### Task 2: Remove the crawler key dependency

**Files:**
- Modify: `backend_ingest.py`
- Modify: `tests/test_backend_ingest.py`
- Modify: `README.md`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: loopback exception from Task 1.
- Produces: `BackendIngestClient(session, url=None, timeout_seconds=30)` with no key environment or header.

- [ ] **Step 1: Change the transport test first**

Instantiate `BackendIngestClient` without a key, flush one valid offer, and assert
the server received no `X-Admin-Key` header.

```python
client = BackendIngestClient(self.session, self.url)
result = await client.flush_once(self.state)
self.assertEqual(250, result.acked)
self.assertIsNone(self.requests[0][0])
```

- [ ] **Step 2: Verify the focused Python test fails**

```bash
python3 -m unittest tests.test_backend_ingest.BackendIngestClientTest.test_flushes_at_most_250_and_acknowledges_only_success -v
```

Expected: construction fails because `ADMIN_API_KEY` is currently required.

- [ ] **Step 3: Remove key handling**

Delete the constructor parameter, environment lookup, validation, and request
header. Preserve batching, acknowledgement, and retry behavior.

```python
def __init__(self, session, url=None, timeout_seconds=30):
    self.session = session
    self.url = url or os.getenv(
        "BACKEND_INGEST_URL",
        "http://127.0.0.1:8080/api/admin/catalog/ingest",
    )
    self.timeout = aiohttp.ClientTimeout(total=timeout_seconds)

# In flush_once:
async with self.session.post(self.url, json=body, timeout=self.timeout) as response:
    ...
```

- [ ] **Step 4: Update operator documentation**

Remove `ADMIN_API_KEY` from crawler requirements. State that the backend may keep
it for other admin routes and that the crawler endpoint is direct-loopback only.

- [ ] **Step 5: Verify all Python behavior**

```bash
python3 -m unittest discover -s tests -v
python3 -m compileall -q -x '(^|/)(.worktrees|graphify-out)(/|$)' .
```

Expected: all tests pass and compilation exits zero.

- [ ] **Step 6: Commit Python behavior**

```bash
git add backend_ingest.py tests/test_backend_ingest.py README.md ARCHITECTURE.md
git commit -m "feat: ingest locally without admin key"
```

### Task 3: Verify and push

**Files:** No planned source changes.

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: deployed backend commit and pushed Python `main`.

- [ ] **Step 1: Run final verification**

```bash
./gradlew test && ./gradlew bootJar
python3 -m unittest discover -s tests -v
```

Expected: both repositories exit zero.

- [ ] **Step 2: Push backend then Python**

```bash
git push origin deployment-prod
git push origin main
```

Expected: both pushes succeed and the backend production workflow completes.
