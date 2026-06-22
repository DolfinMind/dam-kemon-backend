#!/usr/bin/env bash
###############################################################################
# ci-crawl.sh — ONE deep crawl pass, then exit. Runs anywhere with a JDK + the
# MONGODB_URI env var (GitHub Actions, a cron box, the prod server, your laptop).
# Crawls every ACTIVE shop with a high per-shop cap (more products from each
# seller) and merges cross-shop SKUs (more sellers per product). Free: plain
# HTTP, no proxy. Exits when the catalog plateaus or MAX_MINUTES elapses.
###############################################################################
set -uo pipefail
cd "$(dirname "$0")"

PORT=${PORT:-8090}
HEAP=${HEAP:-5g}
PER_SHOP=${PER_SHOP:-5000}
MAX_MINUTES=${MAX_MINUTES:-180}
JAR=build/libs/dam-kemon-0.0.1-SNAPSHOT.jar
LOG=/tmp/ci-crawl-app.log

[ -z "${MONGODB_URI:-}" ] && { echo "::error::MONGODB_URI is not set"; exit 1; }
[ -f "$JAR" ] || { echo "building jar…"; ./gradlew bootJar -x test -q --no-daemon || exit 1; }

count() { curl -s -m 20 "http://localhost:$PORT/api/products?page=0&size=1" 2>/dev/null \
            | grep -o '"totalElements":[0-9]*' | head -1 | grep -o '[0-9]*'; }
up()    { curl -s -o /dev/null -m 8 -w '%{http_code}' "http://localhost:$PORT/api/admin/jobs" 2>/dev/null; }

echo "booting app (heap=$HEAP per-shop=$PER_SHOP budget=${MAX_MINUTES}m)…"
nohup env MONGODB_URI="$MONGODB_URI" SERVER_PORT="$PORT" \
  BROWSER_ENABLED="${BROWSER_ENABLED:-false}" INDEXER_SCHEDULED=false DISCOVERY_ENABLED=false \
  INDEXER_MAX_PRODUCTS_PER_SHOP="$PER_SHOP" INDEXER_RUN_BUDGET_MINUTES="$MAX_MINUTES" \
  CATEGORY_FOCUS_ENABLED="${CATEGORY_FOCUS_ENABLED:-false}" \
  INDEXER_GLOBAL_PARALLELISM="${PARALLELISM:-40}" \
  java -Xmx"$HEAP" -jar "$JAR" > "$LOG" 2>&1 &
APP=$!
trap 'kill $APP 2>/dev/null' EXIT

for i in $(seq 1 120); do [ "$(up)" = "200" ] && break; sleep 5; done
[ "$(up)" = "200" ] || { echo "::error::app failed to boot"; tail -30 "$LOG"; exit 1; }

before=$(count); before=${before:-0}
echo "products before: $before — firing indexer-nightly…"
curl -s -m 15 -X POST "http://localhost:$PORT/api/admin/jobs/indexer-nightly/run" >/dev/null 2>&1

deadline=$(( $(date +%s) + MAX_MINUTES*60 ))
flat=0; last=$before
while [ "$(date +%s)" -lt "$deadline" ] && [ "$flat" -lt 10 ]; do
  sleep 60
  [ "$(up)" = "200" ] || { echo "app died (likely OOM) — raise HEAP"; break; }
  now=$(count); now=${now:-$last}
  echo "[$(date -u +%H:%M:%S)] products=$now (+$((now-before)))"
  if [ "$now" -le "$last" ]; then flat=$((flat+1)); else flat=0; fi
  last=$now
done

after=$(count); after=${after:-$before}
echo "=================================================="
echo " crawl pass done — products $before -> $after  (+$((after-before)))"
echo "=================================================="
