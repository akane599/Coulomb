#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Checks the database of a mode-switch run (ADR-0028 §5) against the run's manifest.

    python3 -I tools/emulator/verify_mode_switch.py <coulomb.db> <manifest.txt>

mode-switch.sh writes the manifest:
- `run <id>` and `first <snapshot id>`;
- `snap <id> <root|shell|none|->` for each snapshot it triggered, with the mode it expects;
- `marker <n> <counted|uncounted>` for each kernel log marker;
- `crash ok|fail ...` and `wakelock_visible <n>`.

Checks:
1. Mode tags: each snapshot carries the mode it was taken in.
2. No fake zeros: a window with an end outside root mode says "not collected" (no coverage, no
   kernel rows), and every kernel row holds a positive value.
3. No double counting: every marker is stored once, and no window kind counts one twice. A
   counted marker is counted in exactly one consecutive window; an uncounted one in none.
4. No crash, and S9 saw the test wakelock when the kernel listed it.

Reads the copy only. Exits 1 when a check fails.
"""

import sqlite3
import sys

ROOT, SHELL = 2, 1
KERNEL_KINDS = ("WAKEUP_SOURCE", "RESUME_CAUSE", "SUSPEND_ABORT")
NONE, PARTIAL, WHOLE = 0, 1, 2


def coverage(code):
    """(S25, S9, S27) from window_result.kernelCoverage; None when not collected."""
    if code is None:
        return None
    return (code & 3, (code >> 2) & 3, (code >> 4) & 3)


def read_manifest(path):
    manifest = {"snaps": [], "markers": [], "crash": None, "wakelock": 0}
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.split()
            if not parts:
                continue
            key = parts[0]
            if key == "run":
                manifest["run"] = parts[1]
            elif key == "first":
                manifest["first"] = int(parts[1])
            elif key == "snap":
                manifest["snaps"].append((int(parts[1]), parts[2]))
            elif key == "marker":
                manifest["markers"].append((int(parts[1]), parts[2]))
            elif key == "crash":
                manifest["crash"] = " ".join(parts[1:])
            elif key == "wakelock_visible":
                manifest["wakelock"] = int(parts[1])
    return manifest


class Checks:
    def __init__(self):
        self.failures = []
        self.passed = 0

    def check(self, ok, message):
        if ok:
            self.passed += 1
        else:
            self.failures.append(message)


def check_modes(db, manifest, checks):
    expected = {"root": ROOT, "shell": SHELL, "none": None}
    for snapshot_id, mode in manifest["snaps"]:
        if mode not in expected:
            continue
        row = db.execute("SELECT privilegeMode FROM snapshot WHERE id = ?", (snapshot_id,)).fetchone()
        checks.check(row is not None, f"snapshot {snapshot_id} is missing")
        if row is not None:
            checks.check(row[0] == expected[mode], f"snapshot {snapshot_id}: mode {row[0]}, expected {mode}")


def kernel_rows(db, window_id):
    wakes = db.execute(
        f"SELECT kind, count, wakeups FROM window_wake WHERE windowId = ? AND kind IN {KERNEL_KINDS}", (window_id,)
    ).fetchall()
    stats = db.execute(
        "SELECT ws.value FROM window_stat ws JOIN stat_key k ON k.id = ws.keyId WHERE ws.windowId = ? AND k.name LIKE 's25.%'",
        (window_id,),
    ).fetchall()
    return wakes, stats


def check_windows(db, manifest, checks):
    windows = db.execute(
        "SELECT w.id, w.kind, w.kernelCoverage, a.privilegeMode, b.privilegeMode FROM window_result w "
        "JOIN snapshot a ON a.id = w.fromSnapshotId JOIN snapshot b ON b.id = w.toSnapshotId "
        "WHERE w.toSnapshotId >= ? ORDER BY w.toSnapshotId",
        (manifest["first"],),
    ).fetchall()
    summary = []
    for window_id, kind, code, from_mode, to_mode in windows:
        cov = coverage(code)
        wakes, stats = kernel_rows(db, window_id)
        name = f"{kind} window {window_id} ({from_mode} -> {to_mode})"
        summary.append(f"  {name}: coverage {cov}, {len(wakes)} wake rows, {len(stats)} s25 rows")
        if cov is None:
            checks.check(not wakes and not stats, f"{name}: kernel rows without coverage")
            continue
        s25, s9, s27 = cov
        checks.check(from_mode == ROOT, f"{name}: coverage {cov} with a start outside root mode")
        if to_mode != ROOT:
            checks.check(s25 == NONE and s9 == NONE and s27 != WHOLE, f"{name}: coverage {cov} with an end outside root mode")
        by_kind = {k: [w for w in wakes if w[0] == k] for k in KERNEL_KINDS}
        checks.check(s25 != NONE or not stats, f"{name}: S25 rows with S25 not collected")
        checks.check(s9 != NONE or not by_kind["WAKEUP_SOURCE"], f"{name}: S9 rows with S9 not collected")
        checks.check(
            s27 != NONE or not (by_kind["RESUME_CAUSE"] or by_kind["SUSPEND_ABORT"]), f"{name}: S27 rows with S27 not collected"
        )
        positive = all((w[2] if w[0] == "WAKEUP_SOURCE" else w[1]) > 0 for w in wakes) and all(s[0] > 0 for s in stats)
        checks.check(positive, f"{name}: a kernel row holds zero")
    return summary


def check_markers(db, manifest, checks):
    duplicates = db.execute("SELECT COUNT(*) FROM (SELECT 1 FROM kernel_log_event GROUP BY kernelBoot, seq HAVING COUNT(*) > 1)")
    checks.check(duplicates.fetchone()[0] == 0, "a kernel record is stored twice")
    lines = []
    for n, kind in manifest["markers"]:
        tag = f"Pending Wakeup Sources: coulomb-test-{manifest['run']}-{n}"
        stored = db.execute(
            "SELECT COUNT(*) FROM kernel_log_event e JOIN history_tag t ON t.id = e.tagId WHERE t.tag = ?", (tag,)
        ).fetchone()[0]
        counts = dict(
            db.execute(
                "SELECT r.kind, SUM(w.count) FROM window_wake w JOIN wake_tag t ON t.id = w.tagId "
                "JOIN window_result r ON r.id = w.windowId WHERE w.kind = 'SUSPEND_ABORT' AND t.tag = ? GROUP BY r.kind",
                (tag,),
            ).fetchall()
        )
        consecutive = counts.get("CONSECUTIVE", 0)
        lines.append(f"  marker {n} ({kind}): stored {stored}, counted {counts or 0}")
        checks.check(stored == 1, f"marker {n}: stored {stored} times")
        checks.check(all(c <= 1 for c in counts.values()), f"marker {n}: counted twice in one window kind {counts}")
        want = 1 if kind == "counted" else 0
        checks.check(consecutive == want, f"marker {n} ({kind}): {consecutive} in consecutive windows, expected {want}")
    return lines


def main(db_path, manifest_path):
    manifest = read_manifest(manifest_path)
    db = sqlite3.connect(db_path)
    checks = Checks()
    check_modes(db, manifest, checks)
    windows = check_windows(db, manifest, checks)
    markers = check_markers(db, manifest, checks)
    checks.check(manifest["crash"] == "ok", f"crash check: {manifest['crash']}")
    if manifest["wakelock"]:
        seen = db.execute(
            "SELECT COUNT(*) FROM wakeup_source_stat w JOIN snapshot s ON s.id = w.snapshotId WHERE w.name = 'coulomb-test-wl' AND s.id >= ?",
            (manifest["first"],),
        ).fetchone()[0]
        checks.check(seen > 0, "S9 never read the test wakelock the kernel listed")
    print("windows:")
    print("\n".join(windows))
    print("markers:")
    print("\n".join(markers))
    for failure in checks.failures:
        print(f"FAIL: {failure}")
    print(f"{checks.passed} checks passed, {len(checks.failures)} failed")
    return 1 if checks.failures else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2]))
