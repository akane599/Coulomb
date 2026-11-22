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
# Every command here only reads, apart from a passing copy of the database in Coulomb's own cache,
# removed when the capture ends. Deliberately absent:
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

# The database with its write-ahead log, as one moment (audit OPS-001). Both are first copied on
# the device into Coulomb's cache: the log, the database, then the log again. Only a checkpoint
# changes the database, and it copies frames the log already holds; a write between the two log
# copies changes the log, so two equal copies mean the database copy and the log agree. Otherwise
# it tries again. The -shm index is left out: SQLite rebuilds it from the log, and a copy taken at
# another moment could describe other frames. The copies then cross the link, which can take
# minutes (28 MB after 4 days), so they get 15 minutes each.
db=no_backup/coulomb.db
tmp=cache/validation-capture
clean() { app rm -f "$tmp.db" "$tmp.wal1" "$tmp.wal2" > /dev/null 2>&1 || true; }
trap clean EXIT
copied=
for attempt in 1 2 3 4 5; do
  clean
  w1=0; w2=0
  app cp "$db-wal" "$tmp.wal1" 2> /dev/null && w1=1
  app cp "$db" "$tmp.db" || { echo "capture failed: copying the database on the device" >&2; exit 1; }
  app cp "$db-wal" "$tmp.wal2" 2> /dev/null && w2=1
  if [ "$w1$w2" = 00 ]; then copied=1; break; fi
  if [ "$w1$w2" = 11 ]; then
    # Two digests, or the comparison itself failed: a missing tool must not read as a change.
    sums=$(app sha256sum "$tmp.wal1" "$tmp.wal2" | tr -d '\r' | awk '{ print $1 }') || sums=
    [ "$(printf '%s\n' "$sums" | grep -c -E '^[0-9a-f]{64}$')" = 2 ] ||
      { echo "capture failed: could not compare the log copies" >&2; exit 1; }
    if [ "$(printf '%s\n' "$sums" | sort -u | wc -l)" = 1 ]; then copied=1; break; fi
  fi
  echo "the database was written while it was copied; again ($attempt)" >&2
done
[ -n "$copied" ] || { echo "capture failed: the database kept changing while it was copied" >&2; exit 1; }
echo "$attempt" > "$out/db_attempts.txt"
fetch() {
  if timeout 900 adb -s "$serial" shell -T run-as "$pkg" cat "$1" > "$out/$2" 2> "$out/$2.err"; then
    [ -s "$out/$2.err" ] || rm -f "$out/$2.err"
  else
    st=$?
    rm -f "$out/$2"
    echo "capture failed: fetching $2 exited $st" >&2
    exit 1
  fi
}
fetch "$tmp.db" coulomb.db
[ "$w1" = 0 ] || fetch "$tmp.wal1" coulomb.db-wal
clean
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
