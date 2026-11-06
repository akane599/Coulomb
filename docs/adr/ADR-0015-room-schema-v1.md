# ADR-0015: Room schema version 1

- Status: accepted (amends SPEC 8); final freeze at the P2 gate
- Date: 2026-11-06
- Phase: P2

## Context

SPEC 8 lists the minimum schema. Columns may be added; entities may not be merged and provenance
fields may not be dropped. After P2 the schema is costly to change (SPEC 0.6), so the first draft
went to a fresh read-only reviewer with a neutral brief before it was accepted.

**The draft** followed the SPEC 8 listing closely. It keyed rows by `(bootCount, elapsedNs)`, with
`bootCount` read from `Settings.Global.BOOT_COUNT`. It kept battery values only in samples and
recorded a snapshot's outcome as one `partial` flag. Windows had no type. History events were stored
with names, tags and both clocks. Retention cut by wall time, and per-snapshot rows,
windows, references and capacity estimates were deleted with their parents (`CASCADE`).

**The second opinion** found the draft faithful to SPEC 8 but not ready to freeze. These problems
would be expensive later:

1. **The raw boot count as a key.** An import (SPEC 14.5) from a phone at boot 812 into one at boot
   5 would sort new data before old. `latest` would return the old phone's rows and the collector
   would take its history cursor.
2. **Size.** At the S25 Ultra fixture's rates (about 2,800 history changes an hour; 1,000–2,500
   counter rows per heavy snapshot), history and snapshot counters alone come to hundreds of MB a
   month. The SPEC 7.7 target is 150 MB after a year.
3. **No battery values on `Snapshot`.** A coulomb delta between two snapshots (SPEC 6.2, 7.6)
   becomes impossible once the samples are compacted.
4. **Windows without type, session link or charge provenance.** The rules' input window type
   (SPEC 9.1) cannot be selected. The SPEC 6.1 Estimated label of an old window cannot be
   recovered.
5. **One `partial` flag.** A failed source reads as zeros: a window diff computes B − 0.
6. **History.** The parser's baseline flag was dropped, so every read's restated state counts as
   transitions. Gaps could not span a reboot.
7. **Insights.** There is no dismissal time for SPEC 9.1's 24 h rule, and no subject that is not an
   app.

It also found:

- `CASCADE` onto data kept forever;
- wall-clock retention cuts;
- a compaction that could abort on a bucket straddling the cut;
- wall-time ordering where order decides behaviour;
- a non-atomic history write;
- missing per-UID sensor, scan and device counters;
- several columns that should be nullable.

Its full findings are summarised in the decision below, item by item.

## Decision

Version 1 is the SPEC 8 listing in `:core:database` with the changes below. Each item notes
whether it comes from the draft or the second opinion. Tables are `snake_case`, and the schema is
exported to `core/database/schemas/` and committed. A unit test pins the identity hash, so a
table change without a version bump fails the build. Until the P2 gate the pinned hash may move
with the exported JSON.

### Keys, boots and ordering (second opinion 1)

- **`bootSeq`, Coulomb's own boot number, replaces `bootCount` everywhere.** A new `boot` table holds:
  - the sequence number;
  - the raw `BOOT_COUNT`;
  - the build fingerprint;
  - the first wall and elapsed times seen;
  - the history clock offset (`MonotonicClock` minus `elapsedRealtime`, ADR-0014), so the
    mapping of history times can be redone.
- **How a boot is numbered** (`DeviceDao.enterBoot`):
  - it starts at the raw count;
  - it continues one past the highest stored boot when the raw count is not higher, as after an
    import;
  - the same boot is recognised when the count matches and elapsed time has not gone back;
  - an unreadable count always starts a new boot, since splitting one boot keeps rows in order
    while merging two would not.
- **Keys.** `BatterySample` is keyed by `(bootSeq, elapsedNs)`. `SampleAggregate` is keyed by
  `(resolutionS, bootSeq, startElapsedNs)`.
- **Indexes.**
  - The SPEC 8.1 index `(kind, startElapsedNs)` on `Session` becomes
    `(kind, bootSeq, startElapsedNs)`.
  - `Snapshot` is indexed on `(bootSeq, elapsedNs)`.
  - Wall-time columns used for chart ranges are indexed too.
- **Ordering.** Queries order by boot, then elapsed time. Wall time only selects chart ranges.
  - Order that decides behaviour follows ids: the latest insight for the one-open rule, the
    action log's revert order, and the latest capacity estimates.
  - Window and history spans that cross boots use the `bootSeq < b OR (bootSeq = b AND
    elapsedNs < e)` form.

### Snapshots (second opinion 3, 5)

- **Battery values.** `Snapshot` carries the light data it read: level, charge, raw current,
  voltage, temperature, plug and status.
- **Source status.** `sourceStatusJson` records each source's outcome, with short values such as
  `{"S5":"ok","S10":"failed"}`. A table without rows for a failed source means "not read".
- **Counters.** New tables hold the counters SPEC 9's rules need that SPEC 8 has no table for:
  - `uid_stat(snapshotId, uid, key, value)`: GNSS, sensor and scan timers, wakelock time without
    overlaps, background time;
  - `device_stat(snapshotId, key, value)`: signal bins, Doze time, learned capacity.
- **Device-level rows.** These use `UidPower.DEVICE_UID = -1`. A component has either one `"any"`
  process-state row or rows per process state, never both.
- **Rail readings.** `rail_energy.readElapsedNs` keeps the S16 reading's own time.
- **History marks.**
  - `historyCursorMs` is the `NEXT:` value (ADR-0014).
  - `historyEventId` is the highest history event id stored once this snapshot's read was
    written (see History).
- **Not reported is null, never zero** (the parser rule). That makes these columns nullable:
  `AlarmStat.total`, `JobStat.runtimeMs` and `abandoned`, and the `NetStat` byte columns.

### Windows (second opinion 4)

- **Window rows.** `WindowResult` gains:
  - `kind` (`CONSECUTIVE`, `SESSION`, `DAY`, `REFERENCE`);
  - `sessionId` (`SET NULL`);
  - `chargeSource` (charge counter, integrated current, or level delta, which sets the provenance);
  - `detailPruned`.
- **`modeledMah` is nullable.** In T0 and Lite mode no model is read, and a stored 0 would make
  every window "unattributed".
- **`Session` links its snapshots.** It gains `startSnapshotId` and `endSnapshotId`, and keeps
  values that outlive the samples:
  - charge source, peak current, minimum and mean temperature;
  - whether deep Doze was reached, as its own column, since a null time cannot tell "never" from
    "unknown";
  - minutes at or above 80% and at 100%;
  - the stress index and cycle equivalents.
- **Per-window records.** SPEC 7.7 keeps only "derived per-window records" past 30 days, so wakelocks
  and other counters get them as well as energy:
  - `window_wake`: wakelock, kernel wakelock and wakeup reason changes;
  - `window_stat`: alarms, jobs, bytes, rail energy and the `uid_stat`/`device_stat` keys.
- **Shared UIDs.** `WindowUid.process` splits a shared UID by process (SPEC 6.5); `""` means the
  whole UID. The column is in the primary key now, since adding it later would change the key.
- **Below the threshold.** UIDs under the retention threshold are summed per component into
  `OTHER_UID = -2`.

### History (second opinion 2, 6; the size test)

- **`HistoryEvent` columns.** It keeps the parser's `baseline` flag and `change`. The tag is
  interned into `history_tag`.
- **Codes, not names.** `type` and `change` are stored as integer codes (`HistoryCodes`):
  - a code is a position in a fixed list: append new entries at the end, never reorder;
  - a test fails when a kind has no code, or when a stored code moves.
- **One clock.**
  - `monotonicMs` is dropped. The boot's stored offset maps `elapsedNs` back to it.
  - History is never imported. Another phone's history clock is unrelated, and the ring holds only
    30 days.
- **No time index.**
  - Events are written in the order they happened, so `id` order is time order.
  - Each snapshot's `historyEventId` marks the ids its read had stored.
  - A span maps to an id range: above the mark of the last snapshot whose read had ended before
    the span (its time plus `durationMs`), and up to the mark of the first snapshot taken at or
    after the span's end.
  - The ring is cut the same way, by id.
  - This halved the stored size per event, from 65 to 31 bytes; the index was most of the cost.
- **Ids.** `AUTOINCREMENT` stays (the reviewer suggested dropping it): it keeps ids rising after
  the newest rows are deleted, which the marks rely on.
- **Gaps.** `HistoryGap` keeps the history clock's times as read, and start and end boots that may
  differ, so a gap can span a reboot.
- **One write.** The collector writes a read's events and gaps (`insertRead`, which returns the
  mark), then the snapshot with that mark, in one `withTransaction`.

### Insights and the action log (second opinion 7)

- **`Insight`** gains `subjectKey` (such as `uid:10123`, `wakelock:<tag>`, `charger:3` or
  `session:42`) and `stateChangedWall`. It is indexed on `(ruleId, subjectKey)`.
- **`ActionLog`** gains `insightId` (`SET NULL`) and the value seen when outside drift was
  detected.

### Other tables

- **`HealthSummary`** is keyed by `epochDay`, so a time zone change cannot split one day into two.
  Its estimate, interval and health percentage are nullable, so system SoH and the cycle count can
  be stored before the first estimate exists.
- **`ChargeDetail.peakW` and `avgW`** are nullable: they are unknown before calibration.
- **`CapacityEstimate`** gains the window's ΔSoC and ΔQ. Its `weight` is only the static part;
  recency is applied when estimates are combined.
- **`uid_package(uid, packageName, firstSeenWall, lastSeenWall)`** keeps which packages a UID had.
  A window kept for good then keeps its app's name after Android gives the freed UID to a later
  install.

### Kept from the draft

- **Added columns:**
  - `BatterySample.currentRaw`, so a later calibration can be applied again;
  - `Session.closeReason`, null while the session is open;
  - `Capability.detailJson`.
- **Added tables:** `SampleAggregate` (the SPEC 7.7 compaction) and `HistoryGap`.
- **Names as text.** `component` and `processState` keep the platform's names, so a new platform
  name needs no migration.
- **Enums by name.** Every enum except the history ones is stored by name. Their comments now say
  "add, never rename".
- **The file** is in `noBackupFilesDir` (SPEC 14).
- **No destructive migration fallback.**

### Foreign keys (second opinion)

- **`RESTRICT` onto data kept for good.** SQLite cannot change a foreign key's action without
  rebuilding the table. A bug, an import or later snapshot thinning must not silently delete
  windows, user references or capacity estimates. So these are `RESTRICT`:
  - `window_result` → `snapshot`;
  - `reference` → `snapshot`;
  - `session` → `snapshot`;
  - `capacity_estimate` → `session`.
- **`CASCADE`** remains for the per-snapshot and per-window rows and for `charge_detail`.
- **`SET NULL`** applies to `insight.windowId`, `window_result.sessionId`, `action_log.insightId`
  and `charge_detail.chargerProfileId`.
- **Rows that refer to a boot** have no foreign key to `boot`. `enterBoot` writes the boot row
  before anything else, and the check would cost every insert.

### References (second opinion)

- **Automatic references** have an `autoKind`, unique among references. `moveAutoReference` moves
  the one row to a new snapshot instead of adding another, so old snapshots do not stay pinned.
- **User references** are deleted only through `deleteUserReference`.

### Retention and compaction (second opinion; SPEC 7.7)

**Cuts are a key.** Every cut is a `(bootSeq, elapsedNs)` key, never a wall time. A clock that is
unset after boot, or that jumps, cannot prune the wrong rows. The collector turns "N days ago" into
a key from the boot table and elapsed time.

**Sample compaction.** The DAO selects, aggregates and deletes the same key range, one boot and at
most 5,000 rows per transaction.
- The cut moves back to a bucket boundary, so each bucket is built once from all its samples. A
  second insert of a bucket is a bug and aborts.
- Hourly rows are built from minute rows the same way. Means are weighted by sample count.

**Snapshot counters** (`pruneCounters`) are deleted once no window can start at the snapshot.
- Kept: the latest heavy snapshot, every referenced snapshot, and the start of every open session.
- The collector passes a cut two hours back, a margin for computing windows. The blob (30 days)
  keeps the raw dumps for recomputation.
- The snapshot row stays, marked `countersPruned`.

**Window detail** is deleted by kind (`detailBefore`, `sessionDetailBefore`, `pruneDetail`); the
window row stays, marked `detailPruned`. The collector's policy:

| Window | Detail kept |
| --- | --- |
| Day, discharge session | For good |
| Consecutive, charge session, reference | 30 days |
| Screen-on, screen-off session | Not stored: consecutive windows chain between the same heavy snapshots, so their sum is the session's detail |

**Blobs.** `pruneBlobs` clears the paths before the cut and returns them; the caller deletes the
files after the transaction. Blobs live in `files/snapshots/`, which the backup rules exclude.
Files that a crash left behind are found by comparing that folder with `blobPaths`.

**Samples.** A repeated sample is ignored (`IGNORE`) rather than aborting the snapshot written with
it (SPEC 7.5).

### Size budget

**Measured.** `SizeBudgetTest` measures bytes per row on Robolectric's SQLite:

| Row | Bytes |
| --- | --- |
| History event | 31 |
| Sample | 77 |
| One-minute aggregate | 111 |
| Snapshot with full source status | 439 |
| Snapshot counter | 88 |
| Window | 117 |
| Window detail | 86 |

**Projected.** The year below comes to about 131 MB against SPEC 7.7's 150 MB. The test fails above
150 MB, or when a history event grows past 36 bytes or a detail row past 90.

| Table | Rows assumed after a year |
| --- | --- |
| History ring | 1.4 M (30 days at about 1,950 stored events an hour, 70% of the fixture's rate) |
| Raw samples | 70 k (14 days at 5,000 a day) |
| Aggregates | 171 k (180 days at 900 one-minute rows a day, plus hourly rows) |
| Snapshots | 40 k (110 a day: heavy ones, and light ones that mark something) |
| Snapshot counters | 40 k (about 20 snapshots kept) |
| Windows | 80 k (220 a day) |
| Window detail | 384 k (rows per window under the policy above) |
| Other tables | 5 MB |

**What the collector must do:**
- It stores history kinds within the ring's budget, leaving out about 30% of the fixture's
  changes, such as battery values that samples already hold while sampling.
- It stores a level-change light snapshot (SPEC 7.2) as a sample only.
- It stores per-window rows for UIDs above the threshold, and the rest as `OTHER_UID`.

The P2 gate's 72 h run replaces these rates with measured ones before the freeze.

## Consequences

- **SPEC 8** is amended:
  - `bootCount` becomes `bootSeq`, and `HealthSummary.dateWall` becomes `epochDay`;
  - the added columns, the nullability changes and the added tables `boot`, `sample_aggregate`,
    `uid_stat`, `device_stat`, `uid_package`, `window_wake`, `window_stat`, `history_tag` and
    `history_gap` are listed;
  - `HistoryEvent` is listed without `tag` and with `tagId` and `baseline`.
- **The collector owns:**
  - boot numbering on start;
  - the history offset;
  - the transaction that writes a read's events with its snapshot;
  - the retention policy and cuts above;
  - the stored-kind filter;
  - the blob sweep.
- **Tests.** Robolectric runs the database tests on API 36, the newest level it supports. Its
  runtime needs `--add-opens=java.base/jdk.internal.access`, which only `:core:database` sets.
- **detekt.** `TooManyFunctions` no longer counts functions annotated with Room's `@Query`,
  `@Insert`, `@Update`, `@Upsert` and `@Delete`: each is one SQL statement, and a DAO needs one per
  table.
