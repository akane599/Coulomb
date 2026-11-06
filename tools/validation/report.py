#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Report on a Coulomb validation run from one tools/validation/capture.sh checkpoint.

    python3 -I tools/validation/report.py <capture-dir> [--video-from T --video-to T]

Prints Markdown: the run's collection rates, the heavy snapshot cost, protocol E (self-cost)
and protocol A (calibration) of SPEC 16.6. T is epoch milliseconds or ISO 8601 with an offset
(2026-11-07T21:10+03:00). Without --video-from/--video-to, protocol A uses the longest stretch
of unplugged, screen-on samples of at least 30 minutes.

Only aggregate numbers about Coulomb and the device are printed, never another app's name, so the
output can be committed under docs/validation/ (ADR-0004).
"""

import argparse
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

# SPEC 16.6 pass marks.
E_SHARE_PCT_PER_DAY = 0.3
E_WAKELOCK_MS_PER_DAY = 120_000
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
    """Coulomb's own since-charged lines and the power summary from `dumpsys batterystats -c`.

    Formats: BatteryStats.dumpCheckinLocked, android-17.0.0_r1 (android-16.0.0_r4):
    `9,<uid>,l,cpu,<user ms>,<system ms>,0` L4869 (L4848); `9,<uid>,l,awl,<total ms>,<background ms>`
    L4713 (L4692), since the last reset; `9,<uid>,l,wua,<tag>,<count>` L4953 (L4932);
    `9,0,l,pws,<capacity mAh>,...` L4524 (L4503). The category `l` is STAT_NAMES[0], L265 (L264).
    """
    text = read(capture, "batterystats.checkin.txt")
    if text is None:
        return {}
    found: dict[str, float] = {"wua": 0}
    for line in text.splitlines():
        cols = line.split(",")
        if len(cols) < 5 or cols[0] != "9" or cols[2] != "l":
            continue
        if cols[1] == "0" and cols[3] == "pws":
            found["capacity_mah"] = float(cols[4])
        if uid is None or cols[1] != str(uid):
            continue
        if cols[3] == "cpu" and len(cols) >= 6:
            found["cpu_ms"] = int(cols[4]) + int(cols[5])
        elif cols[3] == "awl":
            found["awl_ms"] = int(cols[4])
        elif cols[3] == "wua":
            found["wua"] += int(cols[-1])
    return found


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
    statuses = Counter()
    for (text,) in db.execute("SELECT sourceStatusJson FROM snapshot WHERE kind = 'HEAVY'"):
        for source, status in json.loads(text or "{}").items():
            statuses[(source, status)] += 1
    out.append("| Source | Outcome | Snapshots |\n| --- | --- | ---: |")
    for (source, status), count in sorted(statuses.items()):
        out.append(f"| {source} | {status} | {count:,} |")
    out.append("")

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


def protocol_e(db, uid, days, checkin, first, last, out):
    out.append("## Protocol E: self-cost\n")
    capacity = checkin.get("capacity_mah")
    if uid is None:
        out.append("Coulomb's UID is unknown: no `package.txt` and no `uid_package` row.\n")
        return
    windows = db.execute(
        """SELECT w.id, f.wallMs AS fromWall, t.wallMs AS toWall
           FROM window_result w JOIN snapshot f ON f.id = w.fromSnapshotId JOIN snapshot t ON t.id = w.toSnapshotId
           WHERE w.kind = 'CONSECUTIVE' ORDER BY f.wallMs""",
    ).fetchall()
    covered = sum(w["toWall"] - w["fromWall"] for w in windows)
    ids = [w["id"] for w in windows]

    def total(sql):
        if not ids:
            return 0.0
        marks = ",".join("?" * len(ids))
        return db.execute(sql.format(marks=marks), (*ids, uid)).fetchone()[0] or 0.0

    with_rows = total("SELECT COUNT(DISTINCT windowId) FROM window_uid WHERE windowId IN ({marks}) AND uid = ?")
    if ids and with_rows < len(ids):
        out.append(
            f"Coulomb's UID has rows in {with_rows:.0f} of {len(ids)} consecutive windows; the checks below "
            "count only those, so they are not a result.\n",
        )
    recorded = bool(ids) and with_rows == len(ids)
    mah = total("SELECT SUM(mAh) FROM window_uid WHERE windowId IN ({marks}) AND uid = ?")
    wake_ms = total("SELECT SUM(totalMs) FROM window_wake WHERE windowId IN ({marks}) AND uid = ? AND kind = 'PARTIAL'")
    wakeups = total(
        "SELECT SUM(value) FROM window_stat WHERE windowId IN ({marks}) AND uid = ? "
        "AND key IN ('alarm_wakeups', 'mobile_wakeups', 'wifi_wakeups')",
    )
    jobs = total("SELECT SUM(value) FROM window_stat WHERE windowId IN ({marks}) AND uid = ? AND key = 'job_count'")
    covered_days = covered / DAY_MS
    share = mah / covered_days / capacity * 100 if recorded and capacity and covered_days else None
    wake_per_day = wake_ms / covered_days if recorded and covered_days else None

    out.append(
        f"Consecutive windows cover {covered_days:.2f} of the run's {days:.2f} days "
        f"({fmt(covered / (last - first) * 100 if last > first else None, 1, '%')}). Battery capacity "
        f"{fmt(capacity, 0, ' mAh')} (the platform's estimate, `pws`). Coulomb's UID is {uid}.\n",
    )
    out.append("| Check | Measured | Pass mark | Result |\n| --- | ---: | ---: | --- |")
    out.append(
        f"| Coulomb's share of the battery | {fmt(share, 3, ' %/day')} ({mah:.1f} mAh) "
        f"| ≤ {E_SHARE_PCT_PER_DAY} %/day | {verdict(None if share is None else share <= E_SHARE_PCT_PER_DAY)} |",
    )
    out.append(
        f"| Wakeups caused by Coulomb (alarm, mobile, Wi-Fi) | {wakeups:.0f} | 0 | {verdict(wakeups == 0 if recorded else None)} |",
    )
    out.append(
        f"| Coulomb's partial wakelocks | {fmt(None if wake_per_day is None else wake_per_day / 1000, 1, ' s/day')} "
        f"| < {E_WAKELOCK_MS_PER_DAY // 1000} s/day "
        f"| {verdict(None if wake_per_day is None else wake_per_day < E_WAKELOCK_MS_PER_DAY)} |",
    )
    out.append(f"| Jobs run by Coulomb | {jobs:.0f} | 0 expected | {verdict(jobs == 0 if recorded else None)} |")
    out.append("")

    out.append("Per local day (day windows):\n")
    out.append("| Day starts | Hours | Coulomb mAh | Share | Partial wakelocks |\n| --- | ---: | ---: | ---: | ---: |")
    for w in db.execute(
        """SELECT w.id, f.wallMs AS fromWall, t.wallMs AS toWall FROM window_result w
           JOIN snapshot f ON f.id = w.fromSnapshotId JOIN snapshot t ON t.id = w.toSnapshotId
           WHERE w.kind = 'DAY' ORDER BY f.wallMs""",
    ):
        day_mah = db.execute("SELECT SUM(mAh) FROM window_uid WHERE windowId = ? AND uid = ?", (w["id"], uid)).fetchone()[0] or 0
        day_wake = (
            db.execute(
                "SELECT SUM(totalMs) FROM window_wake WHERE windowId = ? AND uid = ? AND kind = 'PARTIAL'",
                (w["id"], uid),
            ).fetchone()[0]
            or 0
        )
        hours = (w["toWall"] - w["fromWall"]) / 3_600_000
        day_share = day_mah / capacity * 100 * 24 / hours if capacity and hours else None
        start = dt.datetime.fromtimestamp(w["fromWall"] / 1000, dt.timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
        out.append(f"| {start} | {hours:.1f} | {day_mah:.2f} | {fmt(day_share, 3, ' %/day')} | {day_wake / 1000:.1f} s |")
    out.append("")
    if checkin:
        out.append(
            "The platform's own count since the last full charge (`dumpsys batterystats -c`): "
            f"partial wakelocks {fmt(checkin.get('awl_ms', 0) / 1000, 1, ' s')}, "
            f"wakeup alarms {checkin.get('wua', 0)}.\n",
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
    protocol_e(db, uid, days, checkin, first, last, out)
    protocol_a(db, args.video_from, args.video_to, out)
    print("\n".join(out))


if __name__ == "__main__":
    main()
