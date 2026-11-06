#!/usr/bin/env bash
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Read-only capture of the SPEC 16.2 fixture set plus the SPEC 4.6 shell-level probes.
#
#   tools/fixtures/capture.sh <adb-serial> <raw-out-dir>
#
# Writes RAW, unredacted output. Keep <raw-out-dir> outside the repository, then run
# tools/fixtures/redact.py to produce fixtures/<device>/<build>/ (ADR-0004).
#
# Every command here only reads. Deliberately absent:
#   dumpsys batterystats --checkin  deletes batterystats-checkin.bin and dumps the previous
#                                   cycle (AOSP BatteryStatsService, ADR-0005); `-c` is used.
#   --reset, --write, settings put, appops set, pm grant: never.
set -euo pipefail

serial=${1:?adb serial}
out=${2:?raw output dir}
mkdir -p "$out/probe"
status="$out/status.tsv"
printf 'file\tcommand\texit\tbytes\tstderr\n' > "$status"

sh_() { timeout 120 adb -s "$serial" shell -T "$@"; }

run() { # run <file> <device command...>
  local file=$1; shift
  local rc=0
  sh_ "$@" > "$out/$file" 2> "$out/$file.err" || rc=$?
  local bytes; bytes=$(wc -c < "$out/$file")
  local err; err=$(head -c 200 "$out/$file.err" | tr '\t\n' '  ')
  local cmd; cmd=$(printf '%s' "$*" | tr '\t\n' '  ' | tr -s ' ')
  printf '%s\t%s\t%s\t%s\t%s\n' "$file" "$cmd" "$rc" "$bytes" "$err" >> "$status"
  [ -s "$out/$file.err" ] || rm -f "$out/$file.err"
}

date -u +%Y-%m-%dT%H:%M:%SZ > "$out/captured_at.txt"

# Literal device identifiers for redact.py to mask; this file never leaves <raw-out-dir>.
{ sh_ getprop ro.serialno; sh_ getprop ro.boot.serialno; } 2>/dev/null | grep -v '^$' > "$out/.secrets" || true
# Package lists for redact.py's pseudonymisation (ADR-0008). Like .secrets they are never copied into
# the repository; redact.py refuses to run without .packages-system.
sh_ pm list packages -s > "$out/.packages-system"
sh_ pm list packages -3 > "$out/.packages-third-party" || true

# Device state before capture (README).
run probe/id.txt id
run probe/uptime.txt cat /proc/uptime
sh_ getprop | grep -E '^\[(ro\.build\.|ro\.product\.(model|manufacturer|brand|device|name)\]|ro\.board\.platform|ro\.soc\.)' \
  | grep -viE 'serial' > "$out/probe/getprop.txt" || true

# SPEC 16.2 fixture set.
run batterystats.help.txt     dumpsys batterystats -h
run batterystats.proto.bin    dumpsys batterystats --proto
run batterystats.checkin.txt  dumpsys batterystats -c
run batterystats.history.txt  dumpsys batterystats --history
run alarm.txt                 dumpsys alarm
run deviceidle.txt            dumpsys deviceidle
run power.txt                 dumpsys power
run jobscheduler.txt          dumpsys jobscheduler
run suspend_control.txt       dumpsys suspend_control_internal
run battery.txt               dumpsys battery
run thermal.txt               dumpsys thermalservice

# SPEC 4.6 probes that a shell (uid 2000) can answer, i.e. what the T2 worker will see.
run probe/dumpsys-services.txt dumpsys -l
run probe/powerstats.txt       dumpsys powerstats
run probe/power_supply-ls.txt  ls -l /sys/class/power_supply/
# adb shell joins its arguments into one string that the device shell parses, so multi-step
# probes are passed as a single single-quoted string.
# Attribute values under each power_supply node; unreadable files are reported, not skipped.
run probe/power_supply-values.txt 'for d in /sys/class/power_supply/*/; do for f in "$d"*; do [ -f "$f" ] || continue; v=$(timeout 2 head -c 160 "$f" 2>&1 | tr "\n" " ") || v="<error>"; echo "$f=$v"; done; done'
run probe/wakeup.txt 'ls /sys/class/wakeup 2>&1 | head -5; echo "count=$(ls /sys/class/wakeup 2>/dev/null | wc -l)"; for f in name active_count total_time_ms; do echo "wakeup0/$f=$(cat /sys/class/wakeup/wakeup0/$f 2>&1)"; done'
# Settings: only keys that look battery-related leave the device pipe.
for ns in global secure system; do
  sh_ settings list "$ns" 2>/dev/null \
    | grep -iE '^[^=]*(batt|protect|charg|adaptive_?power|power_?sav|low_power|doze|device_idle|psm|thermal)[^=]*=' \
    | sed "s/^/$ns: /" >> "$out/probe/settings-battery.txt" || true
done

echo "raw capture complete: $out"
