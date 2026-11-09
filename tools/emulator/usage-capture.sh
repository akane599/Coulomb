#!/usr/bin/env bash
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# S4 captures on the emulator, unplugged and plugged in (ADR-0035, Fixtures).
#
#   tools/emulator/usage-capture.sh <emulator-serial> <raw-out-dir>
#
# Fakes the plug state, which reaches setOnBatteryLocked: an unplug at 90% or more after charging
# resets the statistics (BatteryStatsImpl, ADR-0035). That is why it refuses any device that is not
# an emulator, and why it always ends with `dumpsys battery reset`, even when a step fails.
#
# Writes, next to each S4 read, the S5 proto of the same moment, so a test can compare the two
# sessions' starts. Output is RAW: keep <raw-out-dir> outside the repository and redact it with
# tools/fixtures/redact.py before anything is committed (ADR-0004).
set -euo pipefail

serial=${1:?emulator serial}
out=${2:?raw output dir}
settle=${SETTLE_S:-60}
mkdir -p "$out"
[ -z "$(ls -A "$out")" ] || { echo "refusing: $out is not empty" >&2; exit 2; }

sh_() { timeout 120 adb -s "$serial" shell -T "$@" </dev/null; }
log() { echo "$(date -u +%H:%M:%SZ) $*" | tee -a "$out/run.log" >&2; }

[ "$(sh_ getprop ro.boot.qemu | tr -d '\r')" = 1 ] || [ "$(sh_ getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || {
  echo "refusing: $serial is not an emulator" >&2
  exit 2
}

# From here on the plug state may be faked: put it back however the script ends.
trap 'sh_ dumpsys battery reset >/dev/null || echo "WARNING: dumpsys battery reset failed" >&2' EXIT

capture() { # capture <name>: S4 and S5 at one moment
  sh_ dumpsys batterystats --usage --proto > "$out/usage/$1.bin"
  sh_ dumpsys batterystats --proto > "$out/usage/$1.s5.bin"
  log "$1: S4 $(wc -c < "$out/usage/$1.bin") bytes, S5 $(wc -c < "$out/usage/$1.s5.bin") bytes"
}

mkdir -p "$out/usage"
date -u +%Y-%m-%dT%H:%M:%SZ > "$out/captured_at.txt"
sh_ getprop ro.build.fingerprint > "$out/fingerprint.txt"

log "unplug; settling ${settle}s"
sh_ dumpsys battery unplug
sleep "$settle"
capture unplugged

log "plug in (AC); settling ${settle}s"
sh_ dumpsys battery set ac 1
sh_ dumpsys battery set status 2
sleep "$settle"
capture plugged

sh_ dumpsys battery > "$out/usage/battery-plugged.txt"
log "done; the trap resets the battery state"
