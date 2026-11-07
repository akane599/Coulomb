# ADR-0021: Schema version 2, 30-day window retention, and the stress projection corrected

- Status: accepted (amends ADR-0015 and ADR-0020; edits SPEC 8 and 16.3); freezes the P2 schema
- Date: 2026-11-07
- Phase: P2

## Context

The 30-day stress test (SPEC 16.3, ADR-0019) runs at the rates ADR-0019's phone checkpoint measured:
about 65,800 stored history events and 150 to 160 heavy snapshots a day. Those rates are an upper
bound (see Margin). On schema version 1 the test failed: the second run projected 236 MiB after a
year, against 150 MB. There were three causes.

**Detail rows were wide.**
- Every `window_stat` row repeated its counter key as text and its provenance by name.
- `window_stat` and `window_wake` each had a `(uid, windowId)` index that no query used.
- At day 30, `window_stat` held 48.0 MiB: 126 rows for each consecutive window.

**Rows grew for good that need not.**
- Every consecutive window row (about 150 a day) stayed for good after its detail was pruned.
- So did every snapshot row after its counters were pruned.
- Extended to day 365, the window rows added 12.2 MiB and the snapshot rows 14.7 MiB.

**ADR-0020's projection counted some tables wrongly.**
- The counter tables were extended as if they grew: `uid_stat`, `wake_stat`, `uid_power`, `job_stat`
  and `device_stat` added 15.5 MiB. But their rows are deleted after 2 h, except for a few pinned
  snapshots, so they hover rather than grow. `uid_stat` measured 2.02 MiB at day 15 and 2.46 MiB at
  day 30 in that run, and 1.92 MiB at day 31 in the run below.
- One-minute aggregates were extended for 335 days (24.6 MiB). In fact they turn hourly after 180
  days.
- Day 30 can come before the first 30-day cut. Retention runs once a day while charging, so the last
  measurement could miss the cut. Nothing checked that the cut had run.
- It divided by 2^20 bytes, while SPEC 7.7 says MB.

## Decision

### 1. Schema version 2 (lossless)

- **Interned keys and tags.**
  - `stat_key(id, name)` holds the window counter keys, and `window_stat` stores `keyId`.
  - `wake_tag(id, tag)` holds the wakelock tags, and `window_wake` stores `tagId`.
  - The DAO reads and writes them as text, so callers are unchanged.
  - `stat_key` rows are never deleted: there are a few dozen names.
  - A `wake_tag` row goes, in the same transaction, once no window detail uses it.
  - Neither has a foreign key: a key is never deleted, and a tag only when unused.
- **Provenance codes.**
  - `window_uid.provenance` and `window_stat.provenance` are integer codes (`DetailCodes`).
  - A code is a position in a fixed list, as with `HistoryCodes`: append, never reorder.
  - `DetailCodesTest` fails when a value has no code or a stored code moves.
  - These are the second exception to ADR-0015's "enums by name".
- **Indexes.** The `(uid, windowId)` indexes of `window_stat` and `window_wake` go. `window_uid`
  keeps its index, because app detail reads it by UID (`observeForUid`).
- **History time in milliseconds.**
  - `history_event.elapsedNs` becomes `elapsedMs`.
  - The history clock counts milliseconds, and every v1 time was a whole number of them, so nothing
    is lost.
  - Queries compare `elapsedMs * 1000000` with the nanosecond keys of snapshots and cuts.
- **Migration 1 → 2.**
  - A version 1 build is installed on the phone, so the migration is real.
  - It rebuilds the four tables and keeps every id. Snapshots' `historyEventId` marks stay valid.
  - It carries the `AUTOINCREMENT` counters of `history_event` and `window_wake` across the
    rebuild. A dropped table takes its counter with it. A ring that emptied before the upgrade (no
    history read for 30 days) would then hand out ids at or below the marks, and the next cut
    would delete the new events.
  - Its provenance codes are written out in the migration, frozen at version 2.
  - `MigrationTest` builds a v1 database, migrates it, and checks it against both exported schemas
    and every stored value.

### 2. Retention (SPEC 7.7)

SPEC 7.7 keeps "only the derived per-window records" after 30 days. The day and session windows are
those records: each sums the consecutive windows over its span.

- **Consecutive windows.** After 30 days, consecutive window rows are deleted, and their detail
  goes with them by cascade.
  - Their totals remain in the day and session windows.
  - An insight that pointed at one keeps its row and loses the link (`SET NULL`).
- **Snapshot rows.** After the history ring's 30-day cut, a snapshot row is deleted unless one of
  these still needs it:
  - a session's start or end, a reference, or a window's start or end (all `RESTRICT` keys);
  - its counters (not yet pruned) or its blob;
  - it is the last snapshot of its boot: retention counts elapsed time from it;
  - it is the latest heavy snapshot, or the latest with a history cursor;
  - it is the latest whose history mark is before the cut: it marks the ring's oldest kept event.

  This delete runs last in the daily run.
- **Unchanged from ADR-0015:**
  - reference and charge-session windows lose their detail after 30 days, and keep their rows;
  - day and discharge-session windows keep everything for good.

### 3. The projection (corrects ADR-0020, decision 4)

- **31 days.** Sizes are taken at day 16 and day 31, so every 30-day cut has run before the last
  measurement. Growth is extended by (365 − 31) / 15.
- **Capped whole** (counted at their day-31 size):
  - raw samples;
  - history events, gaps and tags;
  - the counter tables: `uid_power`, `wake_stat`, `alarm_stat`, `job_stat`, `net_stat`,
    `rail_energy`, `uid_stat` and `device_stat`.
- **Capped in part** (by their share of the table's rows):
  - the detail of consecutive, reference and charge-session windows;
  - consecutive window rows;
  - snapshots that only consecutive windows reference.
- **Aggregates.**
  - A year holds 180 days of one-minute rows and the other 171 days as hourly rows, a sixtieth the
    size.
  - The test adds: the measured daily growth × (180 + 171 / 60), less the day-31 size.
- **MB** means 10^6 bytes, as SPEC 7.7 writes it. That is 4.6% stricter than MiB.
- **The cut must have run.** At day 31, no light snapshot from the first 6 h may be left. Retention
  deletes them, unless a session, reference or window still needs one, or it is the last of its
  boot.
- **Heap** after GC is compared between day 1 and the last day (day 31).

## Results

**Stress test, API 37 emulator, 2026-11-07:**
- The run took 2.8 min, with a worst main-thread delay of 1 ms.
- Heap after GC went from 5.0 to 4.7 MB (−6.9%).
- The day-31 file was 93.5 MB. **Projected after a year: 145.5 MB**, under 150.
- The largest tables at day 31:
  - `history_event` 50.5 MB and `window_stat` 21.1 MB (50.3 MB at day 30 on version 1);
  - `window_uid` 7.3, `history_tag` 3.3, `battery_sample` 2.5 and `uid_stat` 2.0 MB.
- Growth to day 365:
  - `sample_aggregate` 13.1, `window_stat` 11.3, `session` 10.7 and `window_result` 6.3 MB;
  - `snapshot` 6.0, `window_uid` 2.5 and `window_wake` 2.3 MB.
- History rows: 2,019,363 at day 30 and 2,042,026 at day 31. The ring holds 30 days.
- Detail rows per consecutive window: 21.4 UID rows, 2.2 wakelock rows and 126.3 counter rows.

**`SizeBudgetTest` (Robolectric):**
- A history event takes 29.1 bytes (was 31), and a window detail row 50.7 bytes (was 86).
- The projection at ADR-0015's rates is 115.5 MB, now also in units of 10^6 bytes.
- Its thresholds tighten from 36 and 90 bytes to 32 and 60.

## Review

An independent review covered the migration, the new delete paths and the DAOs, for data safety.
- **One defect:** the lost `AUTOINCREMENT` counter above. It is fixed, and `MigrationTest` checks
  it on an emptied ring. That test fails without the fix.
- **Test gaps, now filled:**
  - `SnapshotDaoTest` covers each rule that keeps a snapshot row;
  - `RetentionTest` follows the history ring across two daily runs;
  - `MigrationTest` checks the counters.
- **Two nits, fixed:**
  - the migration no longer reads the live code list;
  - its comment no longer claims that Room checks foreign keys after a migration. It does not:
    the generated `onPostMigrate` is empty.
- **Found safe:**
  - The snapshot delete excludes all five `RESTRICT` references.
  - Its `NOT IN` lists cannot hold a null.
  - It deletes only light snapshots or ones whose counters are already pruned.

## Margin

The margin is 3%, at an upper-bound history rate. That rate came from a day with seven reboots, each
forcing a catch-up read, and one boot in which the platform recorded full history. The phone night
(ADR-0019) measures a clean rate, which replaces it.

If the clean rate is not lower, these are the next steps, in order:
1. Store `window_uid`'s `component` and `processState` as lookup ids (lossless).
2. Screen-on and screen-off session rows: `session` grows 10.7 MB a year. SPEC 7.7 keeps sessions
   for good, so this needs its own ADR.
3. Sum discharge-session detail per UID after 30 days. This loses detail, so it also needs its own
   ADR.

The history ring stays at 30 days: SPEC 7.7 sets it.

## Consequences

- **SPEC 8** lists `WindowWake.tagId`, `WindowStat.keyId`, `HistoryEvent.elapsedMs`, `StatKey` and
  `WakeTag`, and says that this ADR records version 2.
- **SPEC 16.3**'s stress-test sentence names this projection.
- **ADR-0015** is amended:
  - its retention table: consecutive windows are deleted after 30 days, not only their detail;
  - "the snapshot row stays, marked `countersPruned`" now holds only until retention finds the row
    unneeded;
  - "enums by name" gains the `DetailCodes` exception.
- **The schema is frozen** for P2. A later change is a costly-to-reverse decision (SPEC 0.6): a
  second opinion, an ADR and a tested migration.
