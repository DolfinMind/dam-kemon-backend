# syntax=docker/dockerfile:1.6
#
# Damkemon backend — production container.
#
# Two-stage build:
#   1. gradle:jdk17 image runs `./gradlew bootJar` to produce the fat jar.
#   2. Microsoft's official Playwright Java image hosts the runtime —
#      ships with Chromium + every native dependency Playwright needs,
#      so we don't have to re-derive the apt-get list for ourselves.
#
# Pin the runtime tag to the same Playwright version declared in
# build.gradle (1.50.0). If you bump that dep, bump the FROM line too.
#
# Build:    docker build -t damkemon-backend:latest .
# Run:      docker run --rm -p 8080:8080 \
#             -e MONGODB_URI='mongodb+srv://...' \
#             -e CORS_ALLOWED_ORIGINS='https://damkemon.com' \
#             -e ADMIN_API_KEY='...' \
#             -e AUTH_JWT_SECRET='...' \
#             damkemon-backend:latest
#
# Other env vars worth setting in prod:
#   INDEXER_CRON           override the 3 AM schedule
#   PRICE_HISTORY_CRON     override 4 AM snapshot schedule
#   AFFILIATE_DARAZ_ID     and other AFFILIATE_<SHOP>_ID partner codes
#   SEARCH_ATLAS_ENABLED   true if the Atlas Search index is provisioned

# ─── Stage 1: build ───────────────────────────────────────────────
FROM gradle:8.10.0-jdk17-jammy AS build

WORKDIR /workspace

# Pull deps in a separate layer so iterating on Java sources reuses
# the cache. The `|| true` swallows transient "dependencies" task
# failures that don't matter for the actual bootJar step.
COPY build.gradle settings.gradle gradlew ./
COPY gradle ./gradle
RUN gradle --no-daemon dependencies > /tmp/deps.log 2>&1 || true

COPY src ./src
RUN gradle --no-daemon bootJar -x test \
 && mv build/libs/*.jar build/libs/app.jar

# ─── Stage 2: runtime ─────────────────────────────────────────────
FROM mcr.microsoft.com/playwright/java:v1.50.0-jammy

LABEL org.opencontainers.image.title="dam-kemon-backend"
LABEL org.opencontainers.image.description="Damkemon — Bangladesh price comparison engine."
LABEL org.opencontainers.image.source="https://github.com/DolfinMind/dam-kemon-backend"

# The indexer + price-snapshot + hot-drops crons all assume Asia/Dhaka
# local time. Without TZ the JVM runs UTC and a 03:00 cron actually
# fires at 09:00 BDT — exactly the bug that left the catalog 7 days
# stale during development.
ENV TZ=Asia/Dhaka

# Container-aware JVM tuning. Java 17 auto-detects cgroup limits, but
# being explicit avoids surprises on older runtimes. MaxRAMPercentage
# is the right knob — gives the JVM 75% of whatever the container is
# allocated rather than a hard MB number that breaks if you resize.
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Dhaka -Dfile.encoding=UTF-8"

WORKDIR /app
COPY --from=build /workspace/build/libs/app.jar /app/app.jar

EXPOSE 8080

# Spring Boot Actuator exposes /actuator/health when included as a
# dependency (it is, per build.gradle line 26). Docker uses this to
# decide whether the container is ready for traffic.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# exec form via sh so $JAVA_OPTS expands.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
