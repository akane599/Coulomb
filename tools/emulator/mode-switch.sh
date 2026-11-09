#!/usr/bin/env bash
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Shizuku mode-switch test on the API 37 emulator (ADR-0028 §5, SPEC 16.3).
#
#   tools/emulator/mode-switch.sh <emulator-serial> <out-dir> [package]
#
# Needs a userdebug emulator with the thedjchi Shizuku fork installed, Coulomb's debug build
# authorized in it and its collector running. Refuses any device that is not an emulator.
#
# Runs root -> kill -> shell, then shell -> root, while Coulomb collects. Screen toggles trigger the
# heavy snapshots (SPEC 7.2). Between snapshots it writes marker records to /dev/kmsg as root: user
# records that carry "coulomb-test-", which only a debuggable build's worker lets through (ADR-0029
# §2). A userspace wakelock (/sys/power/wake_lock) gives S9 a source to see. Then it copies Coulomb's
# database and runs verify_mode_switch.py beside this script, which checks the mode tags, that no
# span shows a zero it did not measure, and that no record is counted twice. This script checks
# that Coulomb did not crash.
#
# It changes emulator state only: the Shizuku server, kernel log records, one wakelock and the
# screen. The Shizuku server ends in root mode.
set -euo pipefail

serial=${1:?emulator serial}
out=${2:?output dir}
pkg=${3:-io.github.akane599.coulomb}
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
[ -z "$(ls -A "$out")" ] || { echo "refusing: $out is not empty" >&2; exit 2; }

# adb joins its arguments into one remote command line: quote anything with spaces inside it.
sh_() { timeout 60 adb -s "$serial" shell -T "$@" | tr -d '\r'; }
as_root() { sh_ "su 0 sh -c '$1'"; }
sql() { sh_ "run-as $pkg sqlite3 no_backup/coulomb.db \"$1\""; }
# Logs go to stderr, so a failure inside $(...) still shows. Under pipefail, no reader in a pipe
# may stop early (grep -q, awk exit, head): the writer dies of SIGPIPE and the pipe fails.
log() { echo "$(date +%H:%M:%S) $*" | tee -a "$out/run.log" >&2; }
fail() { log "FAIL: $*"; exit 1; }
note() { echo "$*" >> "$out/manifest.txt"; }

[ "$(sh_ getprop ro.boot.qemu)" = 1 ] || [ "$(sh_ getprop ro.kernel.qemu)" = 1 ] || {
  echo "refusing: $serial is not an emulator" >&2
  exit 2
}
[ "$(sh_ getprop ro.build.type)" != user ] || { echo "refusing: su needs a userdebug build" >&2; exit 2; }

apk=$(sh_ pm path moe.shizuku.privileged.api | sed -n 's/^package://p')
apk=${apk%%$'\n'*}
[ -n "$apk" ] || fail "the Shizuku fork is not installed"
case $(sh_ getprop ro.product.cpu.abi) in
  x86_64) libdir=x86_64 ;;
  arm64-v8a) libdir=arm64 ;;
  *) fail "unexpected ABI" ;;
esac
starter="${apk%/base.apk}/lib/$libdir/libshizuku.so"

# The Shizuku server: its user, and starting it from a root or a shell session. A root session
# makes a root server and a shell session a shell one; adb root does not change a running server.
# Its comm is "main" (app_process), so pkill -x cannot find it; ps shows its process name.
server_user() { sh_ "ps -A -o USER,NAME" | awk '$2 == "shizuku_server" && !n++ { print $1 }'; }
server_pid() { sh_ "ps -A -o PID,NAME" | awk '$2 == "shizuku_server" && !n++ { print $1 }'; }
kill_server() {
  local pid
  pid=$(server_pid)
  [ -z "$pid" ] || as_root "kill $pid" > /dev/null
}
wait_for() { # <seconds> <command...>
  local n=$1
  shift
  for _ in $(seq "$n"); do
    "$@" && return 0
    sleep 1
  done
  return 1
}
no_server() { [ -z "$(server_user)" ]; }
server_is() { [ "$(server_user)" = "$1" ]; }
stop_server() {
  kill_server
  wait_for 15 no_server || fail "the Shizuku server is still running"
  # A watchdog would bring it back within about 12 s; none may, or the mode would not change.
  sleep 15
  no_server || fail "the Shizuku server came back on its own"
  log "Shizuku server stopped"
}
start_server() { # root|shell
  if [ "$1" = root ]; then as_root "$starter"; else sh_ "$starter"; fi >> "$out/starter.log" 2>&1 || true
  wait_for 20 server_is "$1" || fail "no $1 Shizuku server after starting it"
  log "Shizuku server running as $1"
}

# Snapshots. A screen toggle triggers a heavy one; each prints "<id> <kind> <mode>", where the mode
# is 1 (shell), 2 (root) or empty (no connection).
last_id() { sql "select ifnull(max(id), 0) from snapshot"; }
awake() { [[ $(sh_ "dumpsys power | grep mWakefulness=") == *Awake* ]]; }
asleep() { ! awake; }
toggle() {
  if awake; then
    sh_ input keyevent KEYCODE_SLEEP
    wait_for 10 asleep || fail "the screen did not turn off"
  else
    sh_ input keyevent KEYCODE_WAKEUP
    wait_for 10 awake || fail "the screen did not turn on"
  fi
}
wait_snapshot() { # <id before>
  local row
  for _ in $(seq 45); do
    sleep 1
    row=$(sql "select id, kind, ifnull(privilegeMode, '') from snapshot where id > $1 order by id desc limit 1")
    [ -n "$row" ] && { echo "$row" | tr '|' ' '; return 0; }
  done
  fail "no snapshot within 45 s of a screen toggle"
}
snap() { # <expected mode: root|shell|none|->
  local before row
  before=$(last_id)
  toggle
  row=$(wait_snapshot "$before")
  note "snap ${row%% *} $1"
  log "snapshot $row (expected $1)"
}
mode_code() { case $1 in root) echo 2 ;; shell) echo 1 ;; *) echo "" ;; esac; }
snap_until() { # <root|shell>: snapshots until Coulomb serves one in that mode
  local before row
  for _ in $(seq 6); do
    before=$(last_id)
    toggle
    row=$(wait_snapshot "$before")
    set -- "$1" $row
    if [ "${4:-}" = "$(mode_code "$1")" ]; then
      note "snap $2 $1"
      log "snapshot $row: Coulomb is in $1 mode"
      return 0
    fi
    note "snap $2 -"
    log "snapshot $row: not yet in $1 mode"
    sleep 5
  done
  fail "Coulomb did not reach $1 mode"
}

run=$(date +%s)
markers=0
marker() { # counted|uncounted
  markers=$((markers + 1))
  as_root "echo Abort: Pending Wakeup Sources: coulomb-test-$run-$markers > /dev/kmsg"
  note "marker $markers $1"
}
wakelock() { as_root "echo coulomb-test-wl > /sys/power/wake_lock; sleep 1; echo coulomb-test-wl > /sys/power/wake_unlock"; }

pid=$(sh_ pidof "$pkg" || true)
[ -n "$pid" ] || fail "Coulomb is not running"
since=$(sh_ "date '+%m-%d %H:%M:%S.000'")
note "run $run"
note "first $(($(last_id) + 1))"
log "run $run, Coulomb pid $pid"
awake || toggle

# 1. Root mode, with the markers between two root snapshots: each counted once.
stop_server
start_server root
snap_until root
wakelock
marker counted
marker counted
snap root
marker counted
snap root

# 2. Killed while collecting: the screen key and the kill run in one root shell, so the server dies
# just before or during the snapshot the key starts. Which one is a race; the log says which it was,
# and the unit tests cover each cut (KernelReadsTest, KernelCollectionTest).
before=$(last_id)
if awake; then key=KEYCODE_SLEEP; else key=KEYCODE_WAKEUP; fi
as_root "input keyevent $key; kill $(server_pid)" > /dev/null
row=$(wait_snapshot "$before")
note "snap ${row%% *} -"
log "snapshot $row as the server was killed: $(sql "select sourceStatus from snapshot where id = ${row%% *}") (SourceStatusCodes, ADR-0040)"
wait_for 15 no_server || fail "the Shizuku server is still running"
sleep 15
no_server || fail "the Shizuku server came back on its own"
marker uncounted
snap none
marker uncounted

# 3. Shell mode: no kernel reads, and the markers are stored only once root returns.
start_server shell
snap_until shell
marker uncounted
snap shell

# 4. Back to root: the first root window starts at a shell snapshot, so it is not collected.
stop_server
start_server root
snap_until root
wakelock
marker counted
snap root
marker counted
snap root

# No crash: the same process throughout, and nothing in the crash buffer since the start.
now=$(sh_ pidof "$pkg" || true)
crashes=$(sh_ "logcat -b crash -d -T '$since'" | grep -c "$pkg" || true)
if [ "$now" = "$pid" ] && [ "$crashes" = 0 ]; then note "crash ok"; else note "crash fail pid $pid -> ${now:-none}, $crashes crash lines"; fi
visible=$(as_root "cat /sys/class/wakeup/*/name" | grep -cx coulomb-test-wl || true)
note "wakelock_visible $visible"

# A consistent copy through SQLite's backup API, fetched with exec-out: sh_ strips every \r byte.
copy=cache/mode-switch.db
sh_ "run-as $pkg sqlite3 no_backup/coulomb.db '.backup $copy'"
timeout 60 adb -s "$serial" exec-out run-as "$pkg" cat "$copy" > "$out/coulomb.db"
sh_ "run-as $pkg rm -f $copy"
log "database copied; verifying"
python3 -I "$here/verify_mode_switch.py" "$out/coulomb.db" "$out/manifest.txt" | tee -a "$out/run.log"
