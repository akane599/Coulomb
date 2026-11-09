# ADR-0040: Integer codes for the records kept for good (schema 5)

- Status: accepted (after a second opinion under ADR-0027)
- Date: 2026-11-09
- Phase: P4

## Context

ADR-0035's size gate reran the 30-day stress test (SPEC 16.3) on the API 37 emulator in schema 4,
with S4 dumps on every heavy snapshot and root's 5.5 MB bound (ADR-0029) added. It failed: day 31's
file was 96.1 MB, and the year projected to 149.2 MB, or 154.7 MB with root.

That figure stacks three upper bounds:

- the history rate of a phone day with seven reboots (ADR-0021);
- root's kernel storage (ADR-0029);
- S4 on, with every consecutive window on battery broken down (ADR-0035).

Root and S4 can both be on (`CollectorEngine` gates them separately), so the bounds do add.

ADR-0035's fallback was to keep component rows for 14 days. At day 31, `window_component` is 2.95 MB
in steady state, so that saves about 1.6 MB and the projection stays near 153 MB. It also loses
breakdowns.

## Second opinion

A read-only reviewer with a neutral brief recommended against these:

- the 14-day cut alone, with the gate left "pending the phone night";
- ADR-0021 steps 2 and 3, which lose data SPEC 7.7 keeps for good and are hard to revert;
- splitting the gate so the bounds no longer add, which would redefine the gate to pass.

It recommended a lossless pass first: store the text enums of the three tables kept for good, and
the snapshot's JSON status map, as integer codes, then measure before committing to anything.

## Measurement

A SQLite model of the three tables, with 50,000 rows each, compared text names against codes:

| Table | Text, bytes a row | Codes, bytes a row | Saved |
| --- | --- | --- | --- |
| `session` | 201.8 | 162.4 | 19% |
| `snapshot` | 182.4 | 120.6 | 34% |
| `window_result` | 146.6 | 124.1 | 15% |

Those three tables grew 10.85, 6.44 and 6.50 MB to day 365 in the failed run.

## Decision

1. **Schema 5** stores these columns as integer codes:
   - `session.kind`, `session.chargeSource` and `session.closeReason`;
   - `snapshot.trigger` and `snapshot.kind`;
   - `window_result.kind` and `window_result.chargeSource`.

   Each code is the entry's position in a frozen list in `RecordCodes`, which is the enum's order
   at version 5. New entries are appended, never reordered. The constants used in SQL
   (`SNAPSHOT_HEAVY`, `WINDOW_CONSECUTIVE`, and so on) are checked against the lists by
   `RecordCodesTest`.
2. **`snapshot.sourceStatusJson` becomes `snapshot.sourceStatus`**, a `SourceStatusCodes` integer:
   - 3 bits per source, in the order S5, S6, S7, S4, S9, S25, S26, S27;
   - each slot holds 0 for no outcome, otherwise the outcome's position plus one in
     ok, partial, failed, skipped, slept, off;
   - a shell snapshot's four sources fit in 12 bits.

   Only source ids and outcomes are stored, as before (ADR-0015).
3. **The migration (4 to 5) loses nothing:**
   - it rebuilds the three tables with their ids and their `AUTOINCREMENT` counters;
   - it maps names with `CASE`, so an unknown name fails the NOT NULL column instead of storing a
     wrong code;
   - it parses each JSON map with the same code the tests use.

   The collector only ever wrote the eight ids and six outcomes above; this was checked across the
   repository's history. Should another appear anyway:
   - an unknown outcome becomes `failed`: not read, so no value is trusted;
   - an unknown id is dropped, since nothing reads it.

   `MigrationTest` covers:
   - every kind, a root snapshot's eight statuses, and an unknown outcome and id;
   - the children of the rebuilt tables (`charge_detail`, `window_stat`);
   - `foreign_key_check`;
   - counters that skip deleted ids.
4. **Lossless before lossy.** The 14-day cut for component rows is not taken, because the gate
   passes without it (SPEC 0.2: measurement honesty comes before the self-drain budget's margin).
   ADR-0035's rule still applies if a later run reaches 150 MB.

## Result

On the API 37 emulator, schema 5 passes. The setup is the same as the failed run: S4 on, 3,770 of
4,274 consecutive windows broken down, at most 32 component rows a window, and root's 5.5 MB added.

- Day 31's file is 95.2 MB, and the year projects to **148.2 MB** against 150. It was 154.7 MB.
- Growth to day 365:
  - `session` 8.12 MB (was 10.85);
  - `window_result` 5.12 MB (was 6.50);
  - `snapshot` 4.96 MB (was 6.44).
- The file has no free pages at days 16 and 31, so they do not inflate the projection.
- The run took 3.0 minutes. Heap after GC went from 5.4 to 5.0 MB (−6.4%).

The margin is 1.2%, against three stacked upper bounds. The phone night's clean history rate
(ADR-0021) is still the measurement that replaces the first of them.

## Consequences

- SPEC 8's `Snapshot` lists `sourceStatus /*ADR-0040*/` in place of `sourceStatusJson`.
- Raw SQL compares kinds with `RecordCodes` constants. Code that reads entities is unchanged,
  because Room converts the codes.
- The SPEC 14 export is not built yet. It should write kinds and outcomes by name, decoded from the
  entities, so the codes stay an internal storage form.
