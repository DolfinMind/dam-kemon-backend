# Loopback catalog ingest without an admin key

## Decision

The continuous Python crawler will call the existing catalog ingest endpoint
without `ADMIN_API_KEY`. The exception is limited to a direct loopback request
to exactly `/api/admin/catalog/ingest`. All other `/api/admin/**` routes retain
their current JWT or admin-key protection.

Clearing `ADMIN_API_KEY` globally is rejected because the current development
fallback would open every admin route. Direct Python MongoDB writes are rejected
because they bypass the backend category gate and merge logic.

## Boundary

`AdminGateFilter` permits the no-key request only when:

- the path exactly matches the ingest endpoint;
- the servlet peer address is IPv4 or IPv6 loopback; and
- `Forwarded`, `X-Forwarded-For`, and `X-Real-IP` are absent.

This distinguishes the crawler's direct `127.0.0.1:8080` call from requests
forwarded through the public reverse proxy. A non-loopback or forwarded request
continues through the existing JWT/admin-key gate.

## Python and operations

`BackendIngestClient` no longer reads, validates, or sends `ADMIN_API_KEY`.
The production service continues using the loopback backend URL. Documentation
removes the crawler-side key requirement; the backend key may remain configured
for unrelated operator endpoints.

## Verification

Backend filter tests cover direct loopback allowance, forwarded-loopback denial,
and protection of another admin route. Python transport tests assert that ingest
succeeds without an admin header. Full Python and backend suites plus the backend
production JAR build must pass before both production branches are pushed.
