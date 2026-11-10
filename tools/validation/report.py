#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Report on a Coulomb validation run from one tools/validation/capture.sh checkpoint.

    python3 -I tools/validation/report.py <capture-dir> [--coulomb-rates <rates.json>]
                                         [--video-from T --video-to T]

Prints Markdown: the run's collection rates, the heavy snapshot cost, and protocols E (self-cost),
B (screen-off drain) and A (calibration) of SPEC 16.6. E and B read the screen-off sessions Coulomb
stored, as ADR-0049 amends them: a session counts when it is on battery with the screen off from
its screen-off snapshot to its screen-on snapshot, with no reboot. B's Coulomb rates come from
`CaptureRatesTest`, which runs the app's own read path on the capture. T is epoch milliseconds or ISO
8601 with an offset (2026-11-07T21:10+03:00). Without --video-from/--video-to, protocol A uses the
longest stretch of unplugged, screen-on samples of at least 30 minutes.

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

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))  # tools/, for the shared schema module
from coulomb_schema import name_views  # noqa: E402

PACKAGE = "io.github.akane599.coulomb"
DAY_MS = 86_400_000
NS_PER_MS = 1_000_000
UA_NS_PER_MAH = 3.6e15  # 1 mAh = 1000 µA × 3600 s
SAMPLE_GAP_MS = 120_000  # SPEC 7.3: the slowest sampler rate while the screen is on is 30 s
PROTOCOL_A_MIN_MS = 30 * 60_000

# SPEC 16.6 pass marks, protocols B and E as amended by ADR-0049 (owner decision, 2026-11-10).
B_MIN_MS = 2 * 3_600_000
B_MIN_DROP_PCT = 4
B_TOLERANCE_PCT_PER_H = 0.1
E_MIN_SESSION_MS = 30 * 60_000
E_TOTAL_MIN_MS = 8 * 3_600_000
E_SHARE_PCT_PER_DAY = 0.3
E_WAKELOCK_MS_PER_8H = 30_000
# A counted session is on battery with the screen off between its two snapshots; this much slack
# covers the snapshots themselves.
SESSION_SLACK_MS = 2 * 60_000
# HistoryCodes.KINDS in :core:database: BATTERY_LEVEL is code 5 in every schema (codes only grow).
BATTERY_LEVEL = 5
# Process.SHELL_UID: Shizuku's server and its worker run as shell in shell mode.
SHELL_UID = 2000
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
    name_views(db)  # schema 5 codes read as names (ADR-0040)
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
    """The platform's own count of Coulomb since the last full charge, for context."""
    if not checkin:
        return
    out.append("## Coulomb since the last full charge\n")
    out.append(
        "The platform's own count (`dumpsys batterystats -c`): "
        f"{fmt(checkin.get('own_mah'), 2, ' mAh')} attributed, "
        f"partial wakelocks on battery with the screen off {fmt(checkin.get('awl_ms', 0) / 1000, 1, ' s')}, "
        f"wakeup alarms {checkin.get('wua', 0)}.\n",
    )


def alarm_lines(capture):
    return [line for line in (read(capture, "alarm_coulomb.txt") or "").splitlines() if line.strip()]


def columns(db, table):
    return {row[1] for row in db.execute(f"PRAGMA main.table_info({table})")}


def window_counters(db, window_id, uid):
    """One window's counters for `uid` (-1: the device), by key. Schema 1 stores the key's text,
    later schemas a `stat_key` id (ADR-0021)."""
    if "keyId" in columns(db, "window_stat"):
        rows = db.execute(
            "SELECT k.name, w.value FROM window_stat w JOIN stat_key k ON k.id = w.keyId WHERE w.windowId = ? AND w.uid = ?",
            (window_id, uid),
        )
    else:
        rows = db.execute("SELECT key, value FROM window_stat WHERE windowId = ? AND uid = ?", (window_id, uid))
    return {key: value for key, value in rows}


def covering_windows(db, first, last):
    """The consecutive windows that tile the snapshots `first` to `last`, or None when they leave a
    hole or one has lost its detail (ADR-0021 prunes detail after 30 days)."""
    rows = db.execute(
        """SELECT id, fromSnapshotId, toSnapshotId, detailPruned FROM window_result
           WHERE kind = 'CONSECUTIVE' AND fromSnapshotId >= ? AND toSnapshotId <= ? ORDER BY fromSnapshotId""",
        (first, last),
    ).fetchall()
    at = first
    for row in rows:
        if row["fromSnapshotId"] != at or row["detailPruned"]:
            return None
        at = row["toSnapshotId"]
    return [row["id"] for row in rows] if rows and at == last else None


def history_overlaps_gap(db, boot, start_ns, end_ns):
    """A history gap (ADR-0014) that reaches into the session leaves its level changes unknown."""
    return (
        db.execute(
            """SELECT 1 FROM history_gap
               WHERE (startBootSeq < ? OR (startBootSeq = ? AND startElapsedNs < ?))
                 AND (endBootSeq > ? OR (endBootSeq = ? AND endElapsedNs > ?)) LIMIT 1""",
            (boot, boot, end_ns, boot, boot, start_ns),
        ).fetchone()
        is not None
    )


def level_changes(db, boot, start_ns, end_ns):
    """The platform's level changes inside the session, from the stored history (S7), found by boot
    and elapsed time: (elapsed ns, level). Restated states after a read start are not changes
    (ADR-0014). The app reads them by snapshot id range instead (`HistoryDao.levelChanges`), and
    `CaptureRatesTest` gives its rates."""
    t = event_ns(db)
    rows = db.execute(
        f"""SELECT {t}, value FROM history_event
            WHERE bootSeq = ? AND type = ? AND baseline = 0 AND {t} >= ? AND {t} < ? ORDER BY id""",
        (boot, BATTERY_LEVEL, start_ns, end_ns),
    )
    return [(at, v) for at, v in rows if v is not None]


def event_ns(db):
    """History time in nanoseconds: schema 1 stores nanoseconds, later schemas milliseconds (ADR-0021)."""
    return "elapsedMs * 1000000" if "elapsedMs" in columns(db, "history_event") else "elapsedNs"


def first_and_last_drop(start_level, changes):
    """(time, level) of the first and last drop below the level before it, or None with fewer than two."""
    level, drops = start_level, []
    rise = False
    for at, value in changes:
        if value < level:
            drops.append((at, value))
        elif value > level:
            rise = True
        level = value
    return (drops[0], drops[-1], rise) if len(drops) >= 2 and drops[-1][0] > drops[0][0] else None


def uid_mah(db, windows, uid):
    """`uid`'s attributed charge over `windows` (ADR-0018 `window_uid`; component rows are apart)."""
    return sum(
        row[0] or 0
        for window_id in windows
        for row in db.execute("SELECT mAh FROM window_uid WHERE windowId = ? AND uid = ?", (window_id, uid))
    )


def screen_off_sessions(db, uid):
    """Every closed screen-off session with what protocols B and E need from it, or why it does not
    count (ADR-0049): split by a reboot (closed by one, or opened at boot), closed by a data gap or a
    stop, not covered by consecutive windows, or plugged in or lit for more than the slack. A session
    may open or close at a plug change: the screen stays off, and the windows' clocks show whether
    it ran on battery throughout."""
    sessions = []
    for s in db.execute(
        """SELECT s.id, s.bootSeq, s.startElapsedNs, s.endElapsedNs, s.startWall, s.endWall, s.startLevel,
                  s.endLevel, s.startSnapshotId, s.endSnapshotId, s.closeReason,
                  a.trigger AS opened, b.trigger AS closed, a.bootSeq AS openBoot, b.bootSeq AS closeBoot,
                  EXISTS(SELECT 1 FROM snapshot p WHERE p.bootSeq = a.bootSeq AND p.elapsedNs < a.elapsedNs) AS afterBoot
           FROM session s LEFT JOIN snapshot a ON a.id = s.startSnapshotId LEFT JOIN snapshot b ON b.id = s.endSnapshotId
           WHERE s.kind = 'SCREEN_OFF' AND s.endElapsedNs IS NOT NULL ORDER BY s.startWall""",
    ):
        item = {"session": s, "ms": (s["endElapsedNs"] - s["startElapsedNs"]) / NS_PER_MS, "why": None}
        sessions.append(item)
        if s["opened"] is None or s["closed"] is None:
            item["why"] = "a snapshot at its ends is gone"
            continue
        # Opened by the boot's first snapshot, whatever its trigger: the screen went off before it.
        rebooted = s["closeReason"] == "REBOOT" or s["opened"] == "BOOT_COMPLETED" or not s["afterBoot"]
        if rebooted or s["openBoot"] != s["closeBoot"] or s["openBoot"] != s["bootSeq"]:
            item["why"] = "split by a reboot"
            continue
        if s["closeReason"] != "STATE_CHANGED":
            item["why"] = f"closed by {s['closeReason']}"
            continue
        windows = covering_windows(db, s["startSnapshotId"], s["endSnapshotId"])
        if windows is None:
            item["why"] = "not covered by consecutive windows"
            continue
        device, own = Counter(), Counter()
        for window_id in windows:
            device.update(window_counters(db, window_id, -1))
            own.update(window_counters(db, window_id, uid))
        own_mah = uid_mah(db, windows, uid)
        # A window stores only the counters that moved, so a missing one is 0.
        total = device.get("total_realtime_ms")
        if not total:
            item["why"] = "the windows have no device clock"
            continue
        # The windows' clock must run as long as the session: a stats reset inside, such as the
        # one Android makes on unplugging at full charge, restarts every counter.
        if abs(total - item["ms"]) > SESSION_SLACK_MS:
            item["why"] = "the platform's statistics restarted inside"
            continue
        if total - device.get("battery_realtime_ms", 0) > SESSION_SLACK_MS:
            item["why"] = "plugged in during the session"
            continue
        if total - device.get("screen_off_realtime_ms", 0) > SESSION_SLACK_MS:
            item["why"] = "the screen was on during the session"
            continue
        # SPEC 16.6 E is Full mode: the collector ran as a foreground service throughout, so the
        # counts are of a running Coulomb.
        if own.get("fg_service_ms", 0) < total - SESSION_SLACK_MS:
            item["why"] = "the collector was not in the foreground throughout"
            continue
        item.update(
            device=device,
            wakeups=own.get("alarm_wakeups", 0) + own.get("mobile_wakeups", 0) + own.get("wifi_wakeups", 0),
            alarm_wakeups=own.get("alarm_wakeups", 0),
            radio_wakeups=own.get("mobile_wakeups", 0) + own.get("wifi_wakeups", 0),
            wakelock_ms=own.get("partial_wakelock_ms", 0),
            cpu_ms=own.get("cpu_user_ms", 0) + own.get("cpu_system_ms", 0),
            fg_service_ms=own.get("fg_service_ms", 0),
            own_mah=own_mah,
            shell_mah=uid_mah(db, windows, SHELL_UID),
        )
    return sessions


def day_of(wall_ms, first_wall):
    """The collection day a time falls on, from 1: the report keeps no clock time (ADR-0004)."""
    return int((wall_ms - first_wall) // DAY_MS) + 1


def protocol_e(sessions, capture, checkin, first_wall, out):
    """SPEC 16.6 E as amended by ADR-0049: screen-off sessions of 30 minutes or more on battery,
    together at least 8 h."""
    out.append("## Protocol E: self-cost over the stored screen-off sessions\n")
    counted = [s for s in sessions if s["why"] is None and s["ms"] >= E_MIN_SESSION_MS]
    long_ones = [s for s in sessions if s["ms"] >= E_MIN_SESSION_MS]
    reasons = Counter(s["why"] for s in long_ones if s["why"])
    ms = sum(s["ms"] for s in counted)
    hours = ms / 3_600_000
    capacity = checkin.get("capacity_mah")
    own_mah = sum(s["own_mah"] for s in counted)
    share = own_mah / capacity * 100 if capacity else None
    share_mark = E_SHARE_PCT_PER_DAY * hours / 24
    wakelock_ms = sum(s["wakelock_ms"] for s in counted)
    wakelock_mark = E_WAKELOCK_MS_PER_8H * hours / 8
    wakeups = sum(s["wakeups"] for s in counted)
    asleep = sum(s["device"].get("screen_off_realtime_ms", 0) - s["device"].get("screen_off_uptime_ms", 0) for s in counted)
    deep = sum(s["device"].get("deep_doze_ms", 0) for s in counted)
    fg = sum(min(s["fg_service_ms"], s["ms"]) for s in counted)
    out.append(
        f"{len(long_ones)} screen-off sessions lasted 30 minutes or more; {len(counted)} count, "
        f"{hours:.2f} h together. Left out: "
        + (", ".join(f"{n} {why}" for why, n in reasons.most_common()) or "none")
        + f". Over the counted time the CPU slept {fmt(asleep / ms * 100 if ms else None, 0, '%')} of it and the "
        f"device was in deep Doze {fmt(deep / ms * 100 if ms else None, 0, '%')}; Coulomb's collector ran as a "
        f"foreground service for {fmt(fg / ms * 100 if ms else None, 0, '%')}. Battery capacity "
        f"{fmt(capacity, 0, ' mAh')} (the platform's, `pws`).\n",
    )
    alarms_ok = dump_status(capture, "alarm")
    alarms = alarm_lines(capture)
    out.append("| Check | Measured | Pass mark | Result |\n| --- | ---: | ---: | --- |")
    out.append(
        f"| Screen off on battery, sessions of 30 min or more | {hours:.2f} h | ≥ {E_TOTAL_MIN_MS // 3_600_000} h "
        f"| {verdict(ms >= E_TOTAL_MIN_MS)} |",
    )
    out.append(
        f"| Wakeups from Coulomb (wakeup alarms {sum(s['alarm_wakeups'] for s in counted)}, radio wakeups "
        f"{sum(s['radio_wakeups'] for s in counted)}) | {wakeups} | 0 | {verdict(wakeups == 0 if counted else None)} |",
    )
    out.append(
        f"| Coulomb lines in `dumpsys alarm` at the capture | "
        f"{len(alarms) if alarms_ok else 'n/a (`dumpsys alarm` failed)'} | 0 | {verdict(not alarms if alarms_ok else None)} |",
    )
    out.append(
        f"| Coulomb's attributed charge | {own_mah:.3f} mAh, {fmt(share, 4, '%')} of capacity "
        f"| ≤ {share_mark:.4f}% ({E_SHARE_PCT_PER_DAY}% a day) "
        f"| {verdict(None if share is None or not counted else share <= share_mark)} |",
    )
    out.append(
        f"| Coulomb's partial wakelocks | {wakelock_ms / 1000:.3f} s | < {wakelock_mark / 1000:.1f} s "
        f"(30 s per 8 h) | {verdict(wakelock_ms < wakelock_mark if counted else None)} |",
    )
    out.append("")
    shell = sum(s["shell_mah"] for s in counted)
    out.append(
        f"Not counted against Coulomb: heavy snapshots read through Shizuku's shell UID ({SHELL_UID}) and "
        f"system_server, which the platform bills to them. The shell UID drew {shell:.3f} mAh over the same "
        "windows, with everything else that ran as shell.\n",
    )
    out.append("| Session | Day | Hours | Coulomb mAh | Wakeups | Wakelock ms | CPU ms |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
    for s in counted:
        out.append(
            f"| {s['session']['id']} | {day_of(s['session']['startWall'], first_wall)} | {s['ms'] / 3_600_000:.2f} "
            f"| {s['own_mah']:.3f} | {s['wakeups']} | {s['wakelock_ms']} | {s['cpu_ms']} |",
        )
    out.append("")


def protocol_b(db, sessions, rates, first_wall, out):
    """SPEC 16.6 B as amended by ADR-0049. The reference is the platform's own level changes: the
    whole points between the first drop and the last, over the time between them. Sessions of 2 h
    or more on battery with the screen off count, together at least 4 points. Coulomb's rate per
    session is the app's own, from `CaptureRatesTest` on the same capture (`rates`, by session id);
    the report does not compute it."""
    out.append("## Protocol B: screen-off drain over the stored sessions\n")
    rows = []
    reasons = Counter()
    for s in sessions:
        if s["ms"] < B_MIN_MS:
            continue
        if s["why"]:
            reasons[s["why"]] += 1
            continue
        x = s["session"]
        # Conservative: a gap can hide the first or the last drop (ADR-0049 decision 3).
        if history_overlaps_gap(db, x["bootSeq"], x["startElapsedNs"], x["endElapsedNs"]):
            reasons["a history gap inside"] += 1
            continue
        found = first_and_last_drop(x["startLevel"], level_changes(db, x["bootSeq"], x["startElapsedNs"], x["endElapsedNs"]))
        if found is None:
            reasons["fewer than two level drops"] += 1
            continue
        (t1, l1), (t2, l2), rise = found
        ends = (x["startLevel"] - x["endLevel"], (x["endWall"] - x["startWall"]) / 3_600_000)
        app = (rates or {}).get(str(x["id"]))
        coulomb = (app["points"], app["hours"]) if app and app.get("rate") is not None else None
        rows.append((s, (l1 - l2, (t2 - t1) / NS_PER_MS / 3_600_000), coulomb, ends, rise))
    out.append(
        f"{len(rows)} sessions of 2 h or more count. Left out: "
        + (", ".join(f"{n} {why}" for why, n in reasons.most_common()) or "none")
        + ".\n",
    )
    if not rows:
        out.append("No result: no session qualifies.\n")
        return
    out.append(
        "| Session | Day | Hours | Levels | First to last drop | Reference %/h | Coulomb %/h | Apart | From the ends %/h |\n"
        "| --- | ---: | ---: | --- | --- | ---: | ---: | ---: | ---: |",
    )
    for s, ref, coulomb, ends, rise in rows:
        x = s["session"]
        mine = coulomb[0] / coulomb[1] if coulomb else None
        out.append(
            f"| {x['id']} | {day_of(x['startWall'], first_wall)} | {s['ms'] / 3_600_000:.2f} "
            f"| {x['startLevel']}% → {x['endLevel']}% "
            f"| {ref[0]} points in {ref[1]:.2f} h{' (a rise inside)' if rise else ''} | {ref[0] / ref[1]:.3f} "
            f"| {fmt(mine, 3)} | {fmt(None if mine is None else abs(mine - ref[0] / ref[1]), 3)} | {ends[0] / ends[1]:.3f} |",
        )

    def combined(index):
        return sum(r[index][0] for r in rows) / sum(r[index][1] for r in rows)

    points = sum(r[1][0] for r in rows)
    reference, ends = combined(1), combined(3)
    coulomb = combined(2) if all(r[2] for r in rows) else None
    gap = None if coulomb is None else abs(coulomb - reference)
    enough = points >= B_MIN_DROP_PCT
    out.append(
        f"| **All counted** | | {sum(r[0]['ms'] for r in rows) / 3_600_000:.2f} | | {points} points | "
        f"{reference:.3f} | {fmt(coulomb, 3)} | {fmt(gap, 3)} | {ends:.3f} |",
    )
    out.append("")
    if coulomb is None:
        out.append(
            "Coulomb's rates: n/a. Run `CaptureRatesTest` on this capture and pass its output with "
            "`--coulomb-rates` (ADR-0049).\n",
        )
    out.append(
        f"Together {points} points (at least {B_MIN_DROP_PCT}: {verdict(enough)}). Coulomb against the reference: "
        f"{fmt(gap, 3, ' %/h')} apart (pass within ±{B_TOLERANCE_PCT_PER_H} %/h: "
        f"{verdict(None if gap is None or not enough else gap <= B_TOLERANCE_PCT_PER_H)}). From the session's ends, "
        f"as before ADR-0049, Coulomb would be {abs(ends - reference):.3f} %/h apart.\n",
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
    start = dt.datetime.fromtimestamp(chosen[0]["wallMs"] / 1000, dt.timezone.utc).strftime("%Y-%m-%d")  # no clock time (ADR-0004)
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
    parser.add_argument("--coulomb-rates", type=Path, help="CaptureRatesTest's output for this capture (protocol B)")
    parser.add_argument("--video-from", type=parse_time)
    parser.add_argument("--video-to", type=parse_time)
    args = parser.parse_args()
    if (args.video_from is None) != (args.video_to is None):
        parser.error("give both --video-from and --video-to, or neither")

    db = open_db(args.capture)
    first, last = span(db)
    if first is None:
        sys.exit("the database holds no snapshot")
    days = (last - first) / DAY_MS
    uid = own_uid(args.capture, db)
    if uid is None:
        sys.exit("Coulomb's UID is unknown (no `package.txt` and no `uid_package` row)")
    checkin = checkin_lines(args.capture, uid)
    profile = db.execute("SELECT model, sdkInt FROM device_profile").fetchone()

    out = ["# Validation run report\n"]
    out.append(
        f"Device: {profile['model'] if profile else 'unknown'}, SDK {profile['sdkInt'] if profile else '?'}. "
        f"Captured {(read(args.capture, 'captured_at.txt') or 'on an unknown day')[:10]}. Data from "
        f"{dt.datetime.fromtimestamp(first / 1000, dt.timezone.utc):%Y-%m-%d} (day 1), "
        f"{days * 24:.1f} h.\n",
    )
    rates(db, args.capture, days, out)
    heavy_cost(db, args.capture, checkin, out)
    since_charged(checkin, out)
    sessions = screen_off_sessions(db, uid)
    app_rates = json.loads(args.coulomb_rates.read_text()) if args.coulomb_rates else None
    protocol_e(sessions, args.capture, checkin, first, out)
    protocol_b(db, sessions, app_rates, first, out)
    protocol_a(db, args.video_from, args.video_to, out)
    print("\n".join(out))


if __name__ == "__main__":
    main()
