# Remove the legacy admin shared secret

## Root cause

The crawler transport stopped sending the legacy shared secret, but the same
mechanism remained in backend security, configuration templates, scripts, and
documentation. Production also deploys the backend automatically while the
Python repository has no deployment workflow, so the running crawler can remain
older than its pushed branch.

## Security model

The legacy shared-secret authentication path is deleted. Admin routes require an
admin-role JWT. The single catalog-ingest exception remains restricted to the
exact ingest path, a loopback peer, and the absence of reverse-proxy forwarding
headers. No other route receives anonymous access, including development.

## Removal scope

- Delete the backend configuration property, environment-template entries,
  request-header comparison, CORS exposure, and audit attribution.
- Update backend/frontend comments and operator documentation to JWT-only admin
  access.
- Change the production operator script to obtain an owner JWT.
- Remove stale crawler documentation and tests that refer to the deleted shared
  secret.
- Delete the superseded loopback-secret design and plan documents.

The unrelated constant-time comparison used for messenger verification remains.

## Production activation

The backend production workflow gains a final SSH step after backend restart. It
fast-forwards the existing Python checkout, installs declared dependencies, installs
the crawler unit, and restarts it. The step fails if the checkout cannot update or
the service cannot start, making crawler deployment mismatch visible.

## Verification

Backend tests prove that an unauthenticated non-ingest admin route is rejected even
without shared-secret configuration, an admin JWT still passes, and direct loopback
ingest still passes. A repository scan must find no legacy variable/header names in
tracked active files. Full backend tests/JAR build and all Python tests/compilation
must pass before pushing backend first and Python second.
