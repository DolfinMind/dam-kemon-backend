#!/usr/bin/env bash
#
# Idempotently install the prod self-heal: a Restart=always drop-in plus a
# liveness watchdog timer. Run on the production server by the deploy workflow
# after the app is updated. Safe to re-run on every deploy.

set -euo pipefail

SRC="$(cd "$(dirname "$0")" && pwd)"
SVC=damkemon-prod-app.service

# 1) Restart on crash / OOM-kill.
sudo mkdir -p "/etc/systemd/system/${SVC}.d"
sudo install -m 0644 "$SRC/10-self-heal.conf" "/etc/systemd/system/${SVC}.d/10-self-heal.conf"

# 2) Liveness watchdog for the hung-but-alive case.
sudo install -m 0755 "$SRC/damkemon-watchdog.sh"      /usr/local/bin/damkemon-watchdog.sh
sudo install -m 0644 "$SRC/damkemon-watchdog.service" /etc/systemd/system/damkemon-watchdog.service
sudo install -m 0644 "$SRC/damkemon-watchdog.timer"   /etc/systemd/system/damkemon-watchdog.timer

sudo systemctl daemon-reload
sudo systemctl enable --now damkemon-watchdog.timer

echo "=== self-heal installed ==="
echo "-- restart policy --"
systemctl show "$SVC" -p Restart -p RestartSec
echo "-- watchdog timer --"
systemctl is-enabled damkemon-watchdog.timer || true
systemctl is-active  damkemon-watchdog.timer || true
