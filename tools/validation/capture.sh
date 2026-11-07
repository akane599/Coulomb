#!/usr/bin/env bash
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Read-only checkpoint of a Coulomb validation run (SPEC 16.6 protocols A, B and E).
#
#   tools/validation/capture.sh <adb-serial> <raw-out-dir> [package]
#
# Protocols B and E take three checkpoints: one when the night starts (screen off, unplugged), one
# when it ends, before the screen comes on, and one after the screen came on, when Coulomb has closed
# the night's session (ADR-0019). adb does not turn the screen on.
#
# Copies Coulomb's database and blob listing (debug build, through run-as) and reads the platform's
# own view of Coulomb: its process CPU, its foreground service, its alarms and its batterystats
# lines. Then run tools/validation/report.py on <raw-out-dir>. Keep <raw-out-dir> outside the
# repository: the database and dumps hold other apps' names (ADR-0004).
#
# Every command here only reads. Deliberately absent:
#   dumpsys batterystats --checkin  deletes batterystats-checkin.bin (ADR-0005); `-c` is used.
#   --reset, --write, settings put, appops set, pm grant, am force-stop: never.
set -euo pipefail

serial=${1:?adb serial}
out=${2:?raw output dir}
pkg=${3:-io.github.akane599.coulomb}
mkdir -p "$out"
[ -z "$(ls -A "$out")" ] || { echo "refusing: $out is not empty" >&2; exit 2; }

sh_() { timeout 120 adb -s "$serial" shell -T "$@"; }
app() { sh_ run-as "$pkg" "$@"; } # shell -T keeps stdout binary-safe and stderr apart

date -u +%Y-%m-%dT%H:%M:%SZ > "$out/captured_at.txt"
date +%s > "$out/host_wall_s.txt"
sh_ date +%s > "$out/device_wall_s.txt"
sh_ cat /proc/uptime > "$out/uptime.txt"
sh_ getconf CLK_TCK > "$out/clk_tck.txt"

# The database with its write-ahead log, so the copy holds every committed transaction.
for f in coulomb.db coulomb.db-wal coulomb.db-shm; do
  app cat "no_backup/$f" > "$out/$f" 2> "$out/$f.err" || rm -f "$out/$f"
  [ -s "$out/$f.err" ] || rm -f "$out/$f.err"
done
app ls -l files/snapshots > "$out/blobs.txt" 2>&1 || true

# Coulomb's process: CPU time since it started (fields 14-15) and its start (field 22), in ticks.
pid=$(app pidof "$pkg" | tr -d '\r' || true)
echo "${pid:-none}" > "$out/pid.txt"
if [ -n "$pid" ]; then app cat "/proc/$pid/stat" > "$out/proc_stat.txt"; fi

sh_ dumpsys activity services "$pkg" > "$out/services.txt" 2>&1 || true
# Each dump's exit status and length go to a .status file: an empty grep must not read as "none"
# when the dump itself failed or was cut short.
alarm=$(sh_ dumpsys alarm) && st=ok || st="failed $?"
printf '%s\n' "$alarm" | grep -n -F "$pkg" > "$out/alarm_coulomb.txt" || true
echo "$st $(printf '%s\n' "$alarm" | wc -l)" > "$out/alarm.status"
unset alarm
if sh_ dumpsys batterystats -c > "$out/batterystats.checkin.txt"; then st=ok; else st="failed $?"; fi
echo "$st $(wc -l < "$out/batterystats.checkin.txt")" > "$out/batterystats.status"
# The level for protocol B's level delta (BatteryService.dumpInternal `  level: `, android-16.0.0_r4
# L1641, android-17.0.0_r1 L1648).
sh_ dumpsys battery > "$out/battery.txt" 2>&1 || true
# The UID line is `appId=` on Android 17 (seen on the API 37 emulator) and `userId=` before it.
sh_ dumpsys package "$pkg" | grep -E '^\s+(userId|appId|versionName|versionCode|lastUpdateTime)=' > "$out/package.txt" || true

ls -l "$out"
