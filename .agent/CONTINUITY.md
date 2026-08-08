# Continuity

## [PLANS]

- 2026-08-08T08:47Z [USER] Finalize the reusable Lemon Squeezy payment backend, verify it, refresh the Understand graph, then use the existing deployment-prod CI path when checks pass.

## [DISCOVERIES]

- 2026-08-08T08:47Z [TOOL] `./gradlew clean test bootJar --no-daemon` passed; compilation reports two pre-existing deprecated Mongo `ensureIndex` warnings.
- 2026-08-08T08:47Z [TOOL] Docker is unavailable on this workstation, so the requested Gradle verification ran directly rather than in the repository Dockerfile.
- 2026-08-08T08:47Z [TOOL] The existing `.ua/knowledge-graph.json` predates the payment module and has no payment nodes; a refresh is required before committing source.

## [PROGRESS]

- 2026-08-08T08:47Z [TOOL] The safe production workflow builds with `clean test bootJar`, deploys only on `deployment-prod`, and requires systemd plus public actuator health recovery.
- 2026-08-08T09:06Z [CODE] Lemon Squeezy checkout, authenticated read/write, and license endpoints now receive raw response text and parse it with the local Jackson 2 mapper, avoiding Spring Boot 4's Jackson 3 converter mismatch; focused client tests pass.
