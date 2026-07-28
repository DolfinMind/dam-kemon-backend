#!/usr/bin/env bash
set -uo pipefail

STATE="${XDG_STATE_HOME:-$HOME/.local/state}/damkemon-watchdog"
mkdir -p "$STATE"
exec 9>"$STATE/loop.lock"
flock -n 9 || exit 0

while true; do
  "$HOME/selfheal/damkemon-watchdog.sh"
  sleep 60
done
