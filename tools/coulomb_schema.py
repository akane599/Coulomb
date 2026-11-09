# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read Coulomb databases of any schema with the names the tools' queries use.

Schema 5 stores record kinds and snapshot source outcomes as integer codes (ADR-0040). On such a
file, `name_views` puts TEMP views named `snapshot`, `session` and `window_result` in front of the
tables: SQLite resolves an unqualified name in the temp schema first, so queries written for
schema 4 (`kind = 'HEAVY'`, `sourceStatusJson`) read the same rows unchanged. The file itself is
never written. On a schema 4 file it does nothing.

The lists mirror `RecordCodes` and `SourceStatusCodes` in :core:database, which `RecordCodesTest`
freezes: a code is a position, so these only ever grow at the end.
"""

import json

SESSION_KINDS = ["DISCHARGE", "CHARGE", "SCREEN_ON", "SCREEN_OFF"]
CLOSE_REASONS = ["STATE_CHANGED", "REBOOT", "DATA_GAP", "MONITORING_STOPPED"]
SNAPSHOT_KINDS = ["LIGHT", "HEAVY"]
TRIGGERS = [
    "SCREEN_OFF", "SCREEN_ON", "USER_PRESENT", "POWER_CONNECTED", "POWER_DISCONNECTED", "LEVEL_CHANGED",
    "SCREEN_ON_PERIODIC", "APP_OPENED", "PULL_TO_REFRESH", "BOOT_COMPLETED", "PACKAGE_REPLACED",
    "PACKAGE_CHANGED", "LITE_PERIODIC", "SERVICE_STARTED",
]
WINDOW_KINDS = ["CONSECUTIVE", "SESSION", "DAY", "REFERENCE"]
CHARGE_SOURCES = ["CHARGE_COUNTER", "INTEGRATED_CURRENT", "LEVEL_DELTA"]
SOURCES = ["S5", "S6", "S7", "S4", "S9", "S25", "S26", "S27"]
STATUSES = ["ok", "partial", "failed", "skipped", "slept", "off"]
BITS = 3

CODED = {
    "snapshot": {"trigger": TRIGGERS, "kind": SNAPSHOT_KINDS},
    "session": {"kind": SESSION_KINDS, "chargeSource": CHARGE_SOURCES, "closeReason": CLOSE_REASONS},
    "window_result": {"kind": WINDOW_KINDS, "chargeSource": CHARGE_SOURCES},
}


def statuses(code):
    """A `sourceStatus` code as {source: outcome}."""
    out = {}
    for slot, source in enumerate(SOURCES):
        value = (code >> (slot * BITS)) & ((1 << BITS) - 1)
        if value:
            out[source] = STATUSES[value - 1] if value <= len(STATUSES) else f"code{value}"
    return out


def status_json(code):
    """Schema 4's `sourceStatusJson` form of a code: sorted ids, no spaces."""
    if code is None:
        return None
    return json.dumps(dict(sorted(statuses(int(code)).items())), separators=(",", ":"))


def _case(column, names):
    whens = " ".join(f"WHEN {code} THEN '{name}'" for code, name in enumerate(names))
    return f"CASE \"{column}\" {whens} ELSE 'code' || \"{column}\" END"


def name_views(db):
    """Puts the name views in front of schema 5's coded tables; returns True when it did."""
    columns = [row[1] for row in db.execute("PRAGMA main.table_info(snapshot)")]
    if "sourceStatus" not in columns:
        return False
    db.create_function("coulomb_status_json", 1, status_json, deterministic=True)
    for table, coded in CODED.items():
        exprs = []
        for column in [row[1] for row in db.execute(f"PRAGMA main.table_info({table})")]:
            if column in coded:
                exprs.append(f"{_case(column, coded[column])} AS \"{column}\"")
            else:
                exprs.append(f"\"{column}\"")
            if table == "snapshot" and column == "sourceStatus":
                exprs.append('coulomb_status_json("sourceStatus") AS "sourceStatusJson"')
        db.execute(f"CREATE TEMP VIEW \"{table}\" AS SELECT {', '.join(exprs)} FROM main.\"{table}\"")
    return True
