#!/usr/bin/env bash
#
# Liveness watchdog for the Damkemon API. Restart=always (the systemd drop-in)
# covers a clean crash/OOM-kill, but NOT a process that is still alive yet
# unresponsive (threads pinned by a runaway crawl, GC thrash). This catches
# that case: if the liveness probe is unreachable for a few consecutive checks
# — past a generous boot grace — restart the service.
#
# Deliberately conservative so it can never restart-loop or fire on a mere
# search slowdown:
#   * probes /actuator/health/liveness only (cheap; stays UP when only search
#     quality dips, so a slow search never triggers a restart),
#   * leaves the service alone for GRACE seconds after it (re)started, which
#     covers deploys, crashes and our own restarts uniformly, and
#   * requires THRESHOLD consecutive failures before acting.
#
# Runs as root via damkemon-watchdog.timer (every 60s). No `set -e`: arithmetic
# conditionals legitimately return non-zero and must not abort the script.

set -uo pipefail

SVC=damkemon-prod-app.service
URL=http://127.0.0.1:8080/actuator/health/liveness
STATE=/var/lib/damkemon-watchdog
GRACE=240        # seconds of quiet after a (re)start, so a slow boot can't loop
THRESHOLD=3      # consecutive failed checks (~3 min) before restarting

mkdir -p "$STATE"
FAILS="$STATE/fails"

# Only police the service when it is supposed to be running.
systemctl is-active --quiet "$SVC" || exit 0

# Boot grace: skip while the unit started less than GRACE seconds ago.
started=$(systemctl show -p ActiveEnterTimestamp --value "$SVC" 2>/dev/null)
if [ -n "$started" ]; then
  s=$(date -d "$started" +%s 2>/dev/null || echo 0)
  if [ "$s" -gt 0 ] && [ "$(( $(date +%s) - s ))" -lt "$GRACE" ]; then
    echo 0 > "$FAILS"
    exit 0
  fi
fi

# Healthy → reset the counter and we're done.
if curl -fsS --max-time 8 "$URL" >/dev/null 2>&1; then
  echo 0 > "$FAILS"
  exit 0
fi

# Unhealthy → count it; restart once we cross the threshold.
n=$(( $(cat "$FAILS" 2>/dev/null || echo 0) + 1 ))
echo "$n" > "$FAILS"
if [ "$n" -ge "$THRESHOLD" ]; then
  logger -t damkemon-watchdog "liveness unreachable ${n}x — restarting ${SVC}"
  echo 0 > "$FAILS"
  systemctl restart "$SVC"
fi
