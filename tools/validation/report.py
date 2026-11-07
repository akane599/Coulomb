#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Report on a Coulomb validation run from one tools/validation/capture.sh checkpoint.

    python3 -I tools/validation/report.py <capture-dir> [--night-start <capture-dir>]
                                         [--after-screen-on <capture-dir>]
                                         [--video-from T --video-to T]

Prints Markdown: the run's collection rates, the heavy snapshot cost and protocol A (calibration)
of SPEC 16.6. With --night-start, the checkpoint taken when the night began, <capture-dir> is the
one taken when it ended, before the screen came on, and protocols E (self-cost) and B (screen-off
drain) are reported for that night (ADR-0019). Coulomb closes the night's session only at the
screen-on snapshot, so B's Coulomb rate comes from --after-screen-on, a third checkpoint taken after
the screen came on. T is epoch milliseconds or ISO 8601 with an offset (2026-11-07T21:10+03:00).
Without --video-from/--video-to, protocol A uses the longest stretch of unplugged, screen-on
samples of at least 30 minutes.

Only aggregate numbers about Coulomb and the device are printed, never another app's name, so the
output can be committed under docs/validation/ (ADR-0004).
"""

import argparse
import atexit
import datetime as dt
import json
import re
import shutil
import sqlite3
import statistics
import sys
import tempfile
from collections import Counter
from pathlib import Path

PACKAGE = "io.github.akane599.coulomb"
DAY_MS = 86_400_000
NS_PER_MS = 1_000_000
UA_NS_PER_MAH = 3.6e15  # 1 mAh = 1000 µA × 3600 s
SAMPLE_GAP_MS = 120_000  # SPEC 7.3: the slowest sampler rate while the screen is on is 30 s
PROTOCOL_A_MIN_MS = 30 * 60_000

# SPEC 16.6 pass marks (protocols B and E as amended by ADR-0019).
E_NIGHT_MIN_MS = 8 * 3_600_000
E_SHARE_PCT_PER_NIGHT = 0.1
E_WAKELOCK_MS_PER_NIGHT = 30_000
# A valid night is unplugged and screen-off between the checkpoints; this much slack covers the
# capture itself.
NIGHT_SLACK_MS = 2 * 60_000
B_TOLERANCE_PCT_PER_H = 0.1
A_AGREEMENT_PCT = 5.0
# SPEC 16.5, reported here, gated in P4.
HEAVY_WALL_MS = 1_500
HEAVY_CPU_MS = 300


def parse_time(text):
    if re.fullmatch(r"\d{12,14}", text):
        return int(text)
    parsed = dt.datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        sys.exit(f"{text}: give an offset, for example +03:00")
    return int(parsed.timestamp() * 1000)


def open_db(capture):
    """A private copy, so SQLite can apply the write-ahead log without touching the capture."""
    if not (capture / "coulomb.db").is_file():
        sys.exit(f"{capture}/coulomb.db is missing")
    tmp = Path(tempfile.mkdtemp(prefix="coulomb-report-"))
    atexit.register(shutil.rmtree, tmp, ignore_errors=True)
    for name in ("coulomb.db", "coulomb.db-wal", "coulomb.db-shm"):
        if (capture / name).is_file():
            shutil.copy(capture / name, tmp / name)
    db = sqlite3.connect(tmp / "coulomb.db")
    db.row_factory = sqlite3.Row
    return db


def read(capture, name):
    path = capture / name
    return path.read_text(errors="replace").strip() if path.is_file() else None


def own_uid(capture, db):
    text = read(capture, "package.txt") or ""
    match = re.search(r"(?:userId|appId)=(\d+)", text)
    if match:
        return int(match.group(1))
    row = db.execute("SELECT uid FROM uid_package WHERE packageName = ? ORDER BY lastSeenWall DESC", (PACKAGE,)).fetchone()
    return row["uid"] if row else None


def checkin_lines(capture, uid):
    """The device's and Coulomb's since-charged lines from `dumpsys batterystats -c`.

    Formats: BatteryStats.dumpCheckinLocked, android-17.0.0_r1 (android-16.0.0_r4). The category
    `l` is STAT_NAMES[0], L265 (L264).
    - `9,0,l,bt,<start count>,<battery realtime ms>,<battery uptime ms>,<realtime ms>,<uptime ms>,
      <start clock ms>,<screen-off battery realtime ms>,<screen-off battery uptime ms>,...` L4309
      (L4288). The start count rises at every boot and drops to 0 at a stats reset (BatteryStatsImpl
      L10420, L14717 and L11085; android-16.0.0_r4 L10858, L15125 and L11493). A reset sets the
      start clock to the time of the reset (initTimes L10983-10992, called at L11086; L11391 and
      L11494); a wall-clock change moves it with the clock but keeps it at that reset
      (adjustStartClockTime L4203-4206, L4664-4667).
    - `9,0,l,m,...`, the tenth value the time in deep Doze, L4381 (L4360).
    - `9,0,l,dc,<low %>,<high %>,<screen-on %>,<screen-off %>,<mAh>,<screen-off mAh>,...` L4468 (L4447).
    - `9,0,l,pws,<capacity mAh>,...` L4524 (L4503).
    - `9,<uid>,l,pwi,uid,<mAh>,...` L4547 (L4526).
    - `9,<uid>,l,cpu,<user ms>,<system ms>,0` L4869 (L4848).
    - `9,<uid>,l,awl,<total ms>,<background ms>` L4713 (L4692): partial wakelock time on battery
      with the screen off since the last reset. The timer runs on mOnBatteryScreenOffTimeBase
      (BatteryStatsImpl L8278-8280, L8714-8716 in android-16.0.0_r4), and a DurationTimer counts
      only while its time base runs (L3227-3248, L3676-3697).
    - `9,<uid>,l,wua,<tag>,<count>` L4953 (L4932).
    - `9,<uid>,l,nt,...`, the 13th and 14th values the mobile and Wi-Fi radio wakeups, L4614
      (L4593); printed only when some value is above 0, so a missing line means 0.
    """
    text = read(capture, "batterystats.checkin.txt")
    if text is None:
        return {}
    found: dict = {"wua": 0}
    for line in text.splitlines():
        cols = line.split(",")
        if len(cols) < 5 or cols[0] != "9" or cols[2] != "l":
            continue
        if cols[1] == "0":
            if cols[3] == "pws":
                found["capacity_mah"] = float(cols[4])
            elif cols[3] == "bt" and len(cols) >= 12:
                found["bt"] = {
                    "start_count": int(cols[4]),
                    "battery_realtime_ms": int(cols[5]),
                    "start_clock_ms": int(cols[9]),
                    "screen_off_realtime_ms": int(cols[10]),
                    "screen_off_uptime_ms": int(cols[11]),
                }
            elif cols[3] == "m" and len(cols) >= 14:
                found["deep_doze_ms"] = int(cols[13])
            elif cols[3] == "dc" and len(cols) >= 10:
                found["screen_off_pct"] = int(cols[7])
                found["screen_off_mah"] = int(cols[9])
        if uid is None or cols[1] != str(uid):
            continue
        if cols[3] == "cpu" and len(cols) >= 6:
            found["cpu_ms"] = int(cols[4]) + int(cols[5])
        elif cols[3] == "awl":
            found["awl_ms"] = int(cols[4])
        elif cols[3] == "wua":
            found["wua"] += int(cols[-1])
        elif cols[3] == "pwi" and len(cols) >= 6 and cols[4] == "uid":
            found["own_mah"] = float(cols[5])
        elif cols[3] == "nt" and len(cols) >= 18:
            found["radio_wakeups"] = int(cols[16]) + int(cols[17])
    return found


def dump_status(capture, name):
    """Whether capture.sh saw the dump exit cleanly with some output; None for an older capture."""
    text = read(capture, f"{name}.status")
    if text is None:
        return None
    parts = text.split()
    return parts[0] == "ok" and len(parts) > 1 and parts[-1].isdigit() and int(parts[-1]) > 0


def percentile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, round(q * (len(ordered) - 1)))]


def fmt(value, digits=1, unit=""):
    if value is None:
        return "n/a"
    return f"{value:,.{digits}f}{unit}"


def verdict(ok):
    return "n/a" if ok is None else ("pass" if ok else "**fail**")


def span(db):
    first = db.execute("SELECT MIN(wallMs) FROM snapshot").fetchone()[0]
    last = max(
        db.execute("SELECT MAX(wallMs) FROM snapshot").fetchone()[0] or 0,
        db.execute("SELECT MAX(wallMs) FROM battery_sample").fetchone()[0] or 0,
    )
    return first, last


def rates(db, capture, days, out):
    out.append("## Collection rates\n")
    out.append("| What | Count | Per day |\n| --- | ---: | ---: |")

    def row(label, count):
        out.append(f"| {label} | {count:,} | {fmt(count / days if days else None)} |")

    for kind, count in db.execute("SELECT kind, COUNT(*) FROM snapshot GROUP BY kind ORDER BY kind"):
        row(f"{kind.lower()} snapshots", count)
    row("battery samples", db.execute("SELECT COUNT(*) FROM battery_sample").fetchone()[0])
    row("history events", db.execute("SELECT COUNT(*) FROM history_event").fetchone()[0])
    for reason, count in db.execute("SELECT reason, COUNT(*) FROM history_gap GROUP BY reason ORDER BY reason"):
        row(f"history gaps: {reason}", count)
    for kind, count in db.execute("SELECT kind, COUNT(*) FROM window_result GROUP BY kind ORDER BY kind"):
        row(f"{kind.lower()} windows", count)
    blobs = [line.split() for line in (read(capture, "blobs.txt") or "").splitlines() if line.startswith("-")]
    row("blob files kept", len(blobs))
    out.append("")
    blob_bytes = sum(int(cols[4]) for cols in blobs if len(cols) > 4 and cols[4].isdigit())
    db_bytes = sum((capture / n).stat().st_size for n in ("coulomb.db", "coulomb.db-wal") if (capture / n).is_file())
    out.append(f"Blob folder: {blob_bytes / 1e6:.2f} MB. Database with its log: {db_bytes / 1e6:.2f} MB.\n")

    out.append("Heavy snapshots by trigger:\n")
    out.append("| Trigger | Count |\n| --- | ---: |")
    for trigger, count in db.execute(
        "SELECT trigger, COUNT(*) FROM snapshot WHERE kind = 'HEAVY' GROUP BY trigger ORDER BY COUNT(*) DESC",
    ):
        out.append(f"| `{trigger}` | {count:,} |")
    out.append("")
    out.append("Sessions:\n")
    out.append("| Kind | Close reason | Count |\n| --- | --- | ---: |")
    for kind, reason, count in db.execute(
        "SELECT kind, COALESCE(closeReason, 'open'), COUNT(*) FROM session GROUP BY 1, 2 ORDER BY 1, 2",
    ):
        out.append(f"| {kind} | {reason} | {count:,} |")
    out.append("")


def heavy_cost(db, capture, checkin, out):
    out.append("## Heavy snapshot cost (SPEC 16.5, gated in P4)\n")
    durations = [r[0] for r in db.execute("SELECT durationMs FROM snapshot WHERE kind = 'HEAVY'")]
    p90 = percentile(durations, 0.9)
    out.append(
        f"Wall time over {len(durations):,} heavy snapshots: p50 {fmt(percentile(durations, 0.5), 0, ' ms')}, "
        f"p90 {fmt(p90, 0, ' ms')}, max {fmt(max(durations) if durations else None, 0, ' ms')} "
        f"(target < {HEAVY_WALL_MS} ms: {verdict(None if p90 is None else p90 < HEAVY_WALL_MS)} at p90).\n",
    )
    # A read that slept or failed is stored as a light snapshot, so outcomes count every attempt:
    # any snapshot whose sources were read (ADR-0018, heavy only when whole).
    statuses = Counter()
    attempts = slept = screen_off = screen_off_slept = 0
    for text, trigger in db.execute("SELECT sourceStatusJson, trigger FROM snapshot"):
        outcome = json.loads(text or "{}")
        if not outcome:
            continue
        attempts += 1
        did_sleep = "slept" in outcome.values()
        slept += did_sleep
        if trigger == "SCREEN_OFF":
            screen_off += 1
            screen_off_slept += did_sleep
        for source, status in outcome.items():
            statuses[(source, status)] += 1
    out.append("| Source | Outcome | Reads |\n| --- | --- | ---: |")
    for (source, status), count in sorted(statuses.items()):
        out.append(f"| {source} | {status} | {count:,} |")
    out.append("")
    out.append(
        f"The CPU slept during {slept:,} of {attempts:,} reads, stored light. "
        f"At screen off: {screen_off_slept:,} of {screen_off:,}.\n",
    )

    stat, uptime, tck = read(capture, "proc_stat.txt"), read(capture, "uptime.txt"), read(capture, "clk_tck.txt")
    if stat and uptime and tck:
        fields = stat.rsplit(")", 1)[1].split()  # after the command name; fields[0] is field 3
        ticks = int(tck)
        cpu_ms = (int(fields[11]) + int(fields[12])) * 1000 / ticks
        started_s = int(fields[19]) / ticks
        age_s = float(uptime.split()[0]) - started_s
        boot = db.execute("SELECT MAX(bootSeq) FROM snapshot").fetchone()[0]
        since = db.execute(
            "SELECT COUNT(*) FROM snapshot WHERE kind = 'HEAVY' AND bootSeq = ? AND elapsedNs >= ?",
            (boot, int(started_s * 1e9)),
        ).fetchone()[0]
        per = cpu_ms / since if since else None
        out.append(
            f"Process CPU since it started {age_s / 3600:.1f} h ago: {cpu_ms / 1000:.1f} s over {since:,} heavy "
            f"snapshots, so at most {fmt(per, 0, ' ms')} per snapshot, sampling and idle included "
            f"(target < {HEAVY_CPU_MS} ms: {verdict(None if per is None else per < HEAVY_CPU_MS)}).\n",
        )
    else:
        out.append("Process CPU: not captured (no running process).\n")
    if "cpu_ms" in checkin:
        out.append(f"The platform counts {checkin['cpu_ms'] / 1000:.1f} s of Coulomb CPU since the last full charge.\n")


def since_charged(checkin, out):
    """One checkpoint alone: the platform's own count of Coulomb since the last full charge."""
    if not checkin:
        return
    out.append("## Coulomb since the last full charge\n")
    out.append(
        "The platform's own count (`dumpsys batterystats -c`): "
        f"{fmt(checkin.get('own_mah'), 2, ' mAh')} attributed, "
        f"partial wakelocks on battery with the screen off {fmt(checkin.get('awl_ms', 0) / 1000, 1, ' s')}, "
        f"wakeup alarms {checkin.get('wua', 0)}. Protocols B and E compare the night's checkpoints: pass the "
        "first with `--night-start` and, for B, one taken after the screen came on with `--after-screen-on` "
        "(SPEC 16.6, ADR-0019, ADR-0020).\n",
    )


def uptime_s(capture):
    text = read(capture, "uptime.txt")
    return float(text.split()[0]) if text else None


def pid(capture):
    text = read(capture, "pid.txt")
    return text if text and text != "none" else None


def foreground(capture):
    """Whether `dumpsys activity services` shows Coulomb's collector in the foreground."""
    text = read(capture, "services.txt") or ""
    return "CollectorService" in text and "isForeground=true" in text


COUNTERS = ("own_mah", "awl_ms", "wua", "radio_wakeups", "deep_doze_ms", "screen_off_pct", "screen_off_mah")


def night_deltas(uid, start_capture, end_capture, start, end):
    """The night's change in the platform's since-charged counters, or why there is no valid night.

    Each rule closes a way for a broken night to pass (SPEC 16.6, ADR-0019): the counters must run
    on through the night for Coulomb's UID, Coulomb must run in Full mode throughout, and the phone
    must stay unplugged with the screen off.
    """
    for name, capture in (("start", start_capture), ("end", end_capture)):
        status = dump_status(capture, "batterystats")
        if status is None:
            return None, f"the {name} checkpoint does not record whether `dumpsys batterystats -c` finished; capture again"
        if not status:
            return None, f"`dumpsys batterystats -c` failed or printed nothing at the {name} checkpoint"
    if uid is None:
        return None, "Coulomb's UID is unknown (no `package.txt` and no `uid_package` row)"
    a, b = start.get("bt"), end.get("bt")
    if a is None or b is None:
        return None, "a checkpoint has no `bt` line"
    for name, found in (("start", start), ("end", end)):
        # The foreground service always costs some CPU, so a missing line is a bad capture, not 0.
        if "own_mah" not in found:
            return None, f"the {name} checkpoint has no `pwi,uid` line for UID {uid}"
    up_a, up_b = uptime_s(start_capture), uptime_s(end_capture)
    if a["start_count"] != b["start_count"] or (up_a is not None and up_b is not None and up_b < up_a):
        return None, "the phone rebooted between the two checkpoints"
    first, last = device_wall_ms(start_capture), device_wall_ms(end_capture)
    if first is None or last is None or last <= first:
        return None, "the checkpoints have no usable device clock"
    if b["battery_realtime_ms"] < a["battery_realtime_ms"] or b["start_clock_ms"] > first - 60_000:
        return None, "the platform reset its statistics during the night"
    keys = ("battery_realtime_ms", "screen_off_realtime_ms", "screen_off_uptime_ms")
    delta = {key: b[key] - a[key] for key in keys}
    for key in COUNTERS:
        delta[key] = end.get(key, 0) - start.get(key, 0)
    dropped = [key for key in COUNTERS if delta[key] < 0]
    if dropped:
        return None, f"{', '.join(dropped)} went down, which only a stats reset does"
    wall_ms = last - first
    if delta["battery_realtime_ms"] < wall_ms - NIGHT_SLACK_MS:
        plugged = (wall_ms - delta["battery_realtime_ms"]) / 60_000
        return None, f"the phone was plugged in for {plugged:.0f} min of the night"
    if delta["screen_off_realtime_ms"] < delta["battery_realtime_ms"] - NIGHT_SLACK_MS:
        lit = (delta["battery_realtime_ms"] - delta["screen_off_realtime_ms"]) / 60_000
        return None, f"the screen was on for {lit:.0f} min of the night"
    pid_a, pid_b = pid(start_capture), pid(end_capture)
    if pid_a is None or pid_a != pid_b:
        return None, f"Coulomb's process did not run through the night (PID {pid_a} at the start, {pid_b} at the end)"
    if not (foreground(start_capture) and foreground(end_capture)):
        return None, "Coulomb's collector was not a foreground service at both checkpoints (Full mode)"
    delta["wall_ms"] = wall_ms
    return delta, None


def device_wall_ms(capture):
    text = read(capture, "device_wall_s.txt")
    return int(text) * 1000 if text and text.isdigit() else None


def battery_level(capture):
    match = re.search(r"^\s*level: (\d+)", read(capture, "battery.txt") or "", re.MULTILINE)
    return int(match.group(1)) if match else None


def alarm_lines(capture):
    return [line for line in (read(capture, "alarm_coulomb.txt") or "").splitlines() if line.strip()]


def protocol_e(uid, start_capture, end_capture, start, end, out):
    out.append("## Protocol E: self-cost over one night\n")
    delta, why = night_deltas(uid, start_capture, end_capture, start, end)
    if delta is None:
        out.append(f"No result: {why}.\n")
        return None
    capacity = end.get("capacity_mah")
    screen_off_ms = delta["screen_off_realtime_ms"]
    slept_ms = screen_off_ms - delta["screen_off_uptime_ms"]
    out.append(
        f"Between the checkpoints, {delta['wall_ms'] / 3_600_000:.2f} h apart, the phone spent "
        f"{delta['battery_realtime_ms'] / 3_600_000:.2f} h on battery, {screen_off_ms / 3_600_000:.2f} h of it "
        f"with the screen off. The CPU slept for {slept_ms / 3_600_000:.2f} h of that, and the device was in "
        f"deep Doze for {delta['deep_doze_ms'] / 3_600_000:.2f} h. Battery capacity {fmt(capacity, 0, ' mAh')} "
        f"(the platform's estimate, `pws`). Coulomb's UID is {uid}; its process ran throughout as a "
        "foreground service.\n",
    )

    # Wakeups: the platform's wakeup alarms for the UID (`wua`) and the radio wakeups it attributes
    # to the UID (`nt`). Coulomb's own windows cannot add to this: no snapshot runs during the night.
    alarms_ok = dump_status(start_capture, "alarm") and dump_status(end_capture, "alarm")
    alarms_start, alarms_end = alarm_lines(start_capture), alarm_lines(end_capture)
    share = delta["own_mah"] / capacity * 100 if capacity else None
    wakeups = delta["wua"] + delta["radio_wakeups"]
    long_enough = screen_off_ms >= E_NIGHT_MIN_MS

    out.append("| Check | Measured | Pass mark | Result |\n| --- | ---: | ---: | --- |")
    out.append(
        f"| Screen off on battery | {screen_off_ms / 3_600_000:.2f} h | ≥ {E_NIGHT_MIN_MS // 3_600_000} h "
        f"| {verdict(long_enough)} |",
    )
    out.append(
        f"| Wakeups from Coulomb (wakeup alarms {delta['wua']}, radio wakeups {delta['radio_wakeups']}) "
        f"| {wakeups} | 0 | {verdict(wakeups == 0)} |",
    )
    alarm_measured = f"{len(alarms_end)} (start {len(alarms_start)})" if alarms_ok else "n/a (`dumpsys alarm` failed)"
    out.append(
        f"| Coulomb entries in `dumpsys alarm` | {alarm_measured} | 0 | {verdict(not alarms_end if alarms_ok else None)} |",
    )
    out.append(
        f"| Coulomb's attributed charge | {delta['own_mah']:.2f} mAh, {fmt(share, 3, '%')} of capacity "
        f"| ≤ {E_SHARE_PCT_PER_NIGHT}% | {verdict(None if share is None else share <= E_SHARE_PCT_PER_NIGHT)} |",
    )
    out.append(
        f"| Coulomb's partial wakelocks, screen off on battery | {delta['awl_ms'] / 1000:.1f} s "
        f"| < {E_WAKELOCK_MS_PER_NIGHT // 1000} s | {verdict(delta['awl_ms'] < E_WAKELOCK_MS_PER_NIGHT)} |",
    )
    out.append("")
    if delta["awl_ms"] > 0:
        out.append(
            "Coulomb takes its sampling wakelock only while plugged in (SPEC 7.4), so any partial "
            "wakelock time here is a finding even under the pass mark (ADR-0019).\n",
        )
    if screen_off_ms and slept_ms < screen_off_ms / 2:
        out.append(
            "The CPU stayed awake for most of the night, so Coulomb was not tested in deep sleep. "
            "The result stands, but repeat the night with fewer apps holding the CPU awake.\n",
        )
    return delta


def protocol_b(session_db, start_capture, end_capture, end, delta, out):
    """B over E's night. Coulomb neither samples nor snapshots while the screen is off on battery,
    so the night's session gets its end reading only from the screen-on snapshot: its rate needs
    `session_db` from a capture taken after the screen came on."""
    out.append("## Protocol B: screen-off drain over the same night\n")
    if delta is None:
        out.append("No result: protocol E found no valid night.\n")
        return
    first, last = device_wall_ms(start_capture), device_wall_ms(end_capture)
    level_a, level_b = battery_level(start_capture), battery_level(end_capture)
    capacity = end.get("capacity_mah")
    hours = delta["wall_ms"] / 3_600_000
    reference = (level_a - level_b) / hours if level_a is not None and level_b is not None else None
    screen_off_h = delta["screen_off_realtime_ms"] / 3_600_000
    platform = delta["screen_off_pct"] / screen_off_h if screen_off_h else None

    # Coulomb's view: the screen-off session that covers most of the night, from its start reading
    # to its end reading (SPEC 6.2: the counter rate is the one shown once calibration completes;
    # the level rate before).
    counter = level_rate = None
    why = None
    row = session_db.execute(
        """SELECT bootSeq, startWall, endWall, startElapsedNs, startLevel, startChargeUah, endElapsedNs, endLevel,
                  endChargeUah
           FROM session WHERE kind = 'SCREEN_OFF' AND startWall < ? AND COALESCE(endWall, ?) > ?
           ORDER BY MIN(COALESCE(endWall, ?), ?) - MAX(startWall, ?) DESC LIMIT 1""",
        (last, last, first, last, last, first),
    ).fetchone()
    if row is None:
        why = "no Coulomb screen-off session overlaps the night"
    elif row["endElapsedNs"] is None:
        why = "the night's session is still open; pass a capture taken after the screen came on with `--after-screen-on`"
    elif min(row["endWall"], last) - max(row["startWall"], first) < 0.9 * (last - first):
        why = "Coulomb's longest screen-off session covers less than 90% of the night"
    elif row["endElapsedNs"] > row["startElapsedNs"]:
        session_h = (row["endElapsedNs"] - row["startElapsedNs"]) / NS_PER_MS / 3_600_000
        level_rate = (row["startLevel"] - row["endLevel"]) / session_h
        if capacity and row["startChargeUah"] and row["endChargeUah"]:
            counter = (row["startChargeUah"] - row["endChargeUah"]) / 1000 / capacity * 100 / session_h
    if why:
        out.append(f"Coulomb's rate: n/a, {why}.\n")

    out.append("| Rate | %/h |\n| --- | ---: |")
    out.append(
        f"| Level delta ÷ hours between the checkpoints ({level_a}% → {level_b}%) | {fmt(reference, 3)} |",
    )
    out.append(f"| The platform's screen-off level drop ÷ screen-off hours | {fmt(platform, 3)} |")
    out.append(f"| Coulomb, charge counter ÷ capacity | {fmt(counter, 3)} |")
    out.append(f"| Coulomb, level | {fmt(level_rate, 3)} |")
    out.append("")
    gap = None if counter is None or reference is None else abs(counter - reference)
    # Whole-percent levels at both ends put the true drop anywhere within one percent of the
    # level delta, so the reference itself is only known to ±1 % over the night.
    rounding = 1 / hours
    if gap is None:
        result = verdict(None)
    elif gap <= B_TOLERANCE_PCT_PER_H:
        result = verdict(True)
    elif gap <= B_TOLERANCE_PCT_PER_H + rounding:
        result = f"inconclusive: within the level delta's rounding (±{rounding:.3f} %/h)"
    else:
        result = verdict(False)
    out.append(
        f"Coulomb's counter rate against the level delta: {fmt(gap, 3, ' %/h')} apart "
        f"(pass within ±{B_TOLERANCE_PCT_PER_H} %/h: {result}). "
        "The system battery usage graph is the third reference; read it by hand.\n",
    )


def stretches(rows):
    """Runs of unplugged, screen-on samples in one boot with no gap above SAMPLE_GAP_MS."""
    run = []
    for r in rows:
        steady = r["plugType"] == 0 and r["screenOn"]
        joined = run and r["bootSeq"] == run[-1]["bootSeq"] and r["wallMs"] - run[-1]["wallMs"] <= SAMPLE_GAP_MS
        if steady and (not run or joined):
            run.append(r)
            continue
        if run:
            yield run
        run = [r] if steady else []
    if run:
        yield run


def protocol_a(db, video_from, video_to, out):
    out.append("## Protocol A: calibration\n")
    profile = db.execute("SELECT * FROM device_profile ORDER BY calibratedAtWall DESC").fetchone()
    if profile is None or profile["currentScale"] is None or profile["currentSign"] is None:
        out.append("Not calibrated yet: no current scale and sign in `device_profile`.\n")
        return
    scale, sign = profile["currentScale"], profile["currentSign"]
    out.append(
        f"Calibration: scale {scale} µA per unit, sign {sign:+d}, charge counter reliable "
        f"{bool(profile['chargeCounterReliable']) if profile['chargeCounterReliable'] is not None else 'unknown'}, "
        f"quality {fmt(profile['calibrationQuality'], 2)}.\n",
    )
    rows = db.execute(
        "SELECT bootSeq, elapsedNs, wallMs, levelPct, chargeUah, currentRaw, plugType, screenOn "
        "FROM battery_sample ORDER BY bootSeq, elapsedNs",
    ).fetchall()
    if video_from is not None:
        chosen = [r for r in rows if video_from <= r["wallMs"] <= video_to]
        how = "the stated load window"
    else:
        runs = sorted(stretches(rows), key=lambda run: run[-1]["wallMs"] - run[0]["wallMs"], reverse=True)
        chosen = runs[0] if runs else []
        how = "the longest unplugged, screen-on stretch (the load was not stated)"
    if len(chosen) < 2:
        out.append(f"No samples in {how}.\n")
        return
    length_ms = chosen[-1]["wallMs"] - chosen[0]["wallMs"]
    if any(r["currentRaw"] is None for r in chosen) or chosen[0]["chargeUah"] is None or chosen[-1]["chargeUah"] is None:
        out.append(f"{how.capitalize()} lacks current or counter readings.\n")
        return
    integrated = 0.0
    for a, b in zip(chosen, chosen[1:]):
        if b["bootSeq"] != a["bootSeq"]:
            out.append("The window crosses a reboot; protocol A needs one boot.\n")
            return
        mean_ua = sign * scale * (a["currentRaw"] + b["currentRaw"]) / 2
        integrated += -mean_ua * (b["elapsedNs"] - a["elapsedNs"]) / UA_NS_PER_MAH
    counter = (chosen[0]["chargeUah"] - chosen[-1]["chargeUah"]) / 1000
    currents = [-sign * scale * r["currentRaw"] / 1000 for r in chosen]
    gaps = [(b["wallMs"] - a["wallMs"]) / 1000 for a, b in zip(chosen, chosen[1:])]
    agreement = abs(integrated - counter) / counter * 100 if counter > 0 else None
    long_enough = length_ms >= PROTOCOL_A_MIN_MS
    start = dt.datetime.fromtimestamp(chosen[0]["wallMs"] / 1000, dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    out.append(
        f"Window: {how}, from {start}, {length_ms / 60_000:.1f} min, {len(chosen)} samples "
        f"(median spacing {statistics.median(gaps):.0f} s), level {chosen[0]['levelPct']}% → {chosen[-1]['levelPct']}%. "
        f"Discharge current mean {statistics.mean(currents):.0f} mA, standard deviation "
        f"{statistics.pstdev(currents):.0f} mA.\n",
    )
    out.append("| Check | Value |\n| --- | ---: |")
    out.append(f"| Integrated `CURRENT_NOW` | {integrated:.1f} mAh |")
    out.append(f"| `CHARGE_COUNTER` change | {counter:.1f} mAh |")
    out.append(f"| Difference | {fmt(agreement, 2, '%')} |")
    out.append(f"| At least 30 minutes | {'yes' if long_enough else '**no**'} |")
    ok = None if agreement is None else (agreement <= A_AGREEMENT_PCT and long_enough)
    out.append(f"| Result (≤ {A_AGREEMENT_PCT:g}%) | {verdict(ok)} |")
    out.append("")


def main():
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("capture", type=Path)
    parser.add_argument("--night-start", type=Path)
    parser.add_argument("--after-screen-on", type=Path)
    parser.add_argument("--video-from", type=parse_time)
    parser.add_argument("--video-to", type=parse_time)
    args = parser.parse_args()
    if (args.video_from is None) != (args.video_to is None):
        parser.error("give both --video-from and --video-to, or neither")
    if args.after_screen_on is not None and args.night_start is None:
        parser.error("--after-screen-on needs --night-start")

    db = open_db(args.capture)
    first, last = span(db)
    if first is None:
        sys.exit("the database holds no snapshot")
    days = (last - first) / DAY_MS
    uid = own_uid(args.capture, db)
    checkin = checkin_lines(args.capture, uid)
    profile = db.execute("SELECT model, sdkInt FROM device_profile").fetchone()

    out = ["# Validation run report\n"]
    out.append(
        f"Device: {profile['model'] if profile else 'unknown'}, SDK {profile['sdkInt'] if profile else '?'}. "
        f"Captured {read(args.capture, 'captured_at.txt') or 'at an unknown time'}. Data from "
        f"{dt.datetime.fromtimestamp(first / 1000, dt.timezone.utc):%Y-%m-%d %H:%M} UTC, "
        f"{days * 24:.1f} h.\n",
    )
    rates(db, args.capture, days, out)
    heavy_cost(db, args.capture, checkin, out)
    if args.night_start is None:
        since_charged(checkin, out)
    else:
        start = checkin_lines(args.night_start, uid)
        delta = protocol_e(uid, args.night_start, args.capture, start, checkin, out)
        session_db = open_db(args.after_screen_on) if args.after_screen_on else db
        protocol_b(session_db, args.night_start, args.capture, checkin, delta, out)
    protocol_a(db, args.video_from, args.video_to, out)
    print("\n".join(out))


if __name__ == "__main__":
    main()
