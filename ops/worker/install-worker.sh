#!/usr/bin/env bash
#
# Install the out-of-process crawl worker: a oneshot service (same jar, worker
# profile, NO HTTP server, memory-capped + OOM-preferred) plus a nightly timer.
# The API service is left crawl-free, so a runaway crawl can never take it down.
#
# Runtime details (user, paths, env file, jar, java) are DERIVED from the live
# API unit so nothing is hardcoded to a particular server layout. Safe to re-run
# on every deploy (idempotent: regenerates the unit + re-enables the timer).
#
# Tunables (env): WORKER_MEMORY_MAX (default 700M), WORKER_SWAP_MAX (default 2G).

set -euo pipefail

APP=damkemon-prod-app.service
WORKER=damkemon-prod-worker.service
TIMER=damkemon-prod-worker.timer
SRC="$(cd "$(dirname "$0")" && pwd)"

# ── derive runtime from the live API unit (fall back to sensible defaults) ──
USER_NAME="$(systemctl show -p User --value "$APP" 2>/dev/null || true)"
USER_NAME="${USER_NAME:-$(whoami)}"
WDIR="$(systemctl show -p WorkingDirectory --value "$APP" 2>/dev/null || true)"
WDIR="${WDIR:-/home/$USER_NAME/server}"
ENVFILE="$(systemctl show -p EnvironmentFiles --value "$APP" 2>/dev/null | awk '{print $1}' || true)"
JAVA="$(command -v java || echo /usr/bin/java)"
JAR="$(ls -t "$WDIR"/*.jar 2>/dev/null | grep -v -- '-plain\.jar' | head -1 || true)"

if [[ -z "$JAR" ]]; then
  echo "WARN: no runnable jar found in $WDIR — worker NOT installed."
  echo "      (the API unit is $APP; check its WorkingDirectory)"
  exit 0
fi

ENVLINE=""
[[ -n "$ENVFILE" ]] && ENVLINE="EnvironmentFile=-$ENVFILE"
MEM_MAX="${WORKER_MEMORY_MAX:-700M}"
SWAP_MAX="${WORKER_SWAP_MAX:-2G}"

echo "Installing $WORKER  (user=$USER_NAME wdir=$WDIR jar=$(basename "$JAR") mem=$MEM_MAX)"

# Worker JVM: cap heap to the cgroup + exit-on-OOM so a runaway crawl can't
# thrash. MemoryMax is the hard ceiling; OOMScoreAdjust makes the KERNEL kill
# the worker (never the API) first under pressure. Type=oneshot: it runs the
# pipeline and exits, freeing all Chromium/parse memory back to the OS.
sudo tee "/etc/systemd/system/$WORKER" >/dev/null <<UNIT
[Unit]
Description=Dam Kemon crawl/index worker (out-of-process, oneshot)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=$USER_NAME
WorkingDirectory=$WDIR
$ENVLINE
Environment=SPRING_PROFILES_ACTIVE=production,worker
Environment="JAVA_TOOL_OPTIONS=-XX:+ExitOnOutOfMemoryError -XX:MaxRAMPercentage=70"
MemoryMax=$MEM_MAX
MemorySwapMax=$SWAP_MAX
OOMScoreAdjust=800
TimeoutStartSec=3600
Nice=10
ExecStart=$JAVA -jar $JAR --spring.profiles.active=production,worker --app.role=worker --spring.main.web-application-type=none
UNIT

sudo install -m 0644 "$SRC/$TIMER" "/etc/systemd/system/$TIMER"

sudo systemctl daemon-reload
sudo systemctl enable --now "$TIMER"

echo "=== worker installed ==="
systemctl is-enabled "$TIMER" 2>/dev/null || true
systemctl list-timers "$TIMER" --no-pager 2>/dev/null || true
echo "Run one crawl now with:  sudo systemctl start $WORKER"
echo "Tail it with:            sudo journalctl -u $WORKER -f"
