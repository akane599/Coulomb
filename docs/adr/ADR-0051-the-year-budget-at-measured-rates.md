# ADR-0051: The year budget at the phone's measured rates (schemas 6 and 7)

- Status: accepted (after a second opinion under ADR-0027)
- Date: 2026-11-10
- Phase: P2 (the gate's stress test, SPEC 16.3)
- Amends: ADR-0015, ADR-0021 and ADR-0040 (storage form, the stress test's rates and projection);
  SPEC 8

## Context

The stress test's rates came from a 17 h phone checkpoint (ADR-0019): about 75 screen cycles, 160
heavy snapshots and 65,755 stored history events a day. ADR-0021 called the history rate an upper
bound and listed next steps "if the clean rate is not lower". Schema 5 passed at 148.2 MB
(ADR-0040).

Four days on the phone (capture of 2026-11-10) are busier:

| A day | Checkpoint | Four days |
| --- | --- | --- |
| Screen cycles | 75 | 97 |
| Heavy snapshots | 158 | 238 (some from 54 reboots) |
| History events | 65,755 | 68,702 |
| One-minute aggregate rows | about 820 in the test | 1,144 |

Rerun on the API 37 emulator with 68,702 events and about 96 screen cycles a day (197 heavy
snapshots a day), schema 5 fails: day 31's file is 106.1 MB and the year projects to **166.3 MB**
against 150, with root's 5.5 MB and S4 on.

Where the year's bytes go in that run:

| Table | MB | Why |
| --- | --- | --- |
| `history_event` | 53.0 | The 30-day ring. A row takes 24.9 bytes for about 7 bytes of content. |
| `window_stat` | 36.8 | 25.5 in the 30-day part and 11.3 kept for good. Its primary key is stored twice: in the table and in SQLite's automatic index. |
| `sample_aggregate` | 16.2 | |
| `session`, `window_result`, `snapshot` | 27.4 | They grow with screen cycles. |
| `window_uid` | 9.7 | The same doubled key. |

ADR-0021's lossless step 1 is already in schema 4. Its steps 2 and 3 lose data SPEC 7.7 keeps for
good, and ADR-0040's second opinion advised against them.

## Second opinion

A read-only reviewer with a neutral brief modelled the candidates on a copy of the phone's database.
Its recommendation, which this ADR takes:

- Two lossless changes: the history ring as packed chunks (−36.4 MB), and `window_stat` and
  `window_uid` without a rowid (−18.4 and −2.6 MB).
- Correct the projection first: it under-counts by about 5 MB (decision 4).
- Not these:
  - the old checkpoint's rates, or splitting the stacked bounds (with root and S4 both off the run
    is still about 157 MB);
  - anything that loses data while lossless changes pass: dropping unread event kinds, merging
    short screen sessions, expiring screen-session rows, a shorter ring;
  - a surrogate id for `window_stat` (−5.9 MB, not worth a migration), or removing the rowid from
    the sample tables (their second index grows).
- In reserve, if the Room workaround of decision 1 does not hold: `window_stat` as one packed row
  per window and UID (−29.7 MB). It puts a codec on rows kept for good, so it comes second.

## Decision

1. **Schema 6: `window_stat` is a `WITHOUT ROWID` table.**
   - The table is then its primary key's index, so each key is stored once: 36.1 → 18.1 bytes a
     row, measured on the phone's rows. About −18 MB a year.
   - Room 2.8.5 cannot declare the clause. The migration creates the table with it, and a database
     callback rebuilds the empty table on a fresh install (`WithoutRowid`). Every database,
     tests' included, is built through `CoulombDatabase.configured`.
   - Room's validation reads the column and key pragmas, which are the same for both forms.
   - The exported schema does not show the clause, so `SchemaTest` and `MigrationTest` assert it in
     `sqlite_master` after a fresh create and after the migration.
   - Nothing is lost and no query changes. `stats(windowId)` now returns rows in key order; it
     promised no order before.
   - **`window_uid` keeps its rowid.** The second opinion proposed it too (−2.6 MB), assuming
     Room's validation would pass; it had not run Room. It fails: on a table without a rowid
     SQLite reports the key's columns as part of every secondary index, and Room compares the
     index's columns. `window_uid` needs its `(uid, windowId)` index, and 2.6 MB is not worth
     declaring a five-column index to match.
2. **Schema 7: the history ring is stored in chunks.**
   - `history_chunk(firstId, count, data)`: one read's events in order, at most 4,096 a chunk, as a
     byte string. Event `i` of a chunk has id `firstId + i`, so every event keeps its id and
     snapshots' `historyEventId` marks keep their meaning.
   - The encoding is a format byte, then per event: one number that holds the type, the change,
     `baseline` and which optional fields follow; the time as a difference from the event before;
     then the UID, tag id and value where present. Numbers are variable-length; signed ones are
     zigzag-coded. A boot change restates the boot and the time.
   - `history_counter` holds the next event id. It replaces `AUTOINCREMENT`, so an emptied ring
     cannot hand out ids at or below the marks (ADR-0021's defect).
   - `history_tag.lastEventId` is the last event that used the tag. A tag is unused once that id is
     older than the ring's oldest event, which replaces the scan of every event.
   - Reads decode in Kotlin: `events` and `levelChanges` keep their signatures and their id range
     through the snapshot marks. SQL over single events is gone; only tests and
     `tools/validation/report.py` used it, and the tool gets a decoder.
   - The ring is cut in whole chunks. A read is one or more whole chunks and a mark is a read's
     end, so the cut falls where it did.
   - The migration walks the stored events in id order and starts a new chunk at each snapshot
     mark, at each break in the ids and at 4,096 events. It carries the `AUTOINCREMENT` counter into
     `history_counter`.
3. **Two versions, not one.** Each has its own migration and tests and can be reverted alone. The
   phone migrates through both in one start.
4. **The stress test measures what the phone measured, and projects the widths it will reach.**
   - Rates: 68,702 history events and about 96 screen cycles a day, and the phone's 1,144
     one-minute rows a day. The weekly reboot stays: 54 reboots in 4 days were development, not use.
   - No phone figure is called an upper bound again. The longer measurement came in higher.
   - Integer widths at day 365 are added: history ids (schema 6 only; chunks carry one id each),
     `windowId` past 32,767, and elapsed time past 24.8 days of uptime.
   - Hourly aggregates are counted as 24 rows a day, not a sixtieth of the minute rate.
5. **Lossless before lossy, again** (ADR-0040, decision 4). Nothing SPEC 7.7 keeps is cut.

## Verification

- `HistoryChunkCodecTest`: every field at its extremes, boot changes, times that step back, and
  random reads, each decoded back to the same events.
- `MigrationTest`: a version 5 database with id breaks, marks, several boots and an emptied ring
  goes to 7; every event reads back with its id, tag and values, and the next id is above every
  mark.
- The phone's capture (schema 5, 273,935 events) is migrated on the JVM and compared event by
  event with the original rows.
- One independent review of the codec, the migration and the DAO for data safety.
- The stress test's result is recorded below when it has run.

## Results

**Stress test, API 37 emulator, 2026-11-10, schema 7, at the measured rates** (root's 5.5 MB added,
S4 on, 4,836 of 5,455 consecutive windows broken down):

| | Schema 5 | Schema 7 |
| --- | --- | --- |
| Day 31's file | 106.1 MB | 56.6 MB |
| History, 2.14 million events | 53.0 MB | 16.3 MB (7.6 bytes an event) |
| `window_stat` at day 31 | 25.5 MB | 12.5 MB |
| `window_stat` growth to day 365 | 11.3 MB | 5.5 MB |
| **Projected after a year** | 166.3 MB | **119.0 MB** |

- The schema 7 figure is by the corrected projection, which the schema 5 one is not: it counts
  21.1 MB of aggregates at the phone's 1,144 one-minute rows a day (14.7 MB before) and 1.6 MB for
  wider window ids. The margin is 21%.
- The run took 3.6 minutes, with a worst main-thread delay of 4 ms. Heap after GC went from 5.4 to
  5.0 MB (−7.0%).
- `SizeBudgetTest`: a history event takes 13.4 bytes with its tag and value (29.1 before) and a
  window detail row 41.6 (50.7). Its thresholds tighten from 32 and 60 bytes to 16 and 46.

**The phone's database** (copy of 2026-11-10, schema 5, 273,935 events), migrated on the JVM:
- 21.3 MB → 14.8 MB; history 6.8 MB → 1.9 MB in 965 chunks (7.1 bytes an event); `window_stat`
  3.3 MB → 1.7 MB.
- `tools/validation/report.py`'s decoder, written apart from the app's, reads back every event
  identical to the original rows.
- Each tag's `lastEventId` is the last event that used it, every snapshot mark falls at a chunk's
  end, and the next id is one above the last event.
- `integrity_check` passes, the other tables keep their row counts, and protocols B and E read
  the same as on schema 5.

## Review

One independent read-only review of the codec, the migration and the DAO found no way to lose or
change an event. It found four robustness defects, all fixed before the commit:

1. **The reads took the writer.** Room runs a DAO `@Transaction` method on the writer connection,
   so Now's read of a session's level changes could wait for the collector, or hold it up.
   `levelChanges` now reads the marks and the chunks in one query and needs no transaction.
   `events` keeps its transaction for its tags; nothing in the app calls it yet.
2. **A corrupt chunk broke Now.** The exception left the screen's read. `BatteryRepository` now
   catches `CorruptChunkException`, logs it and times that session by its ends. The cache keeps
   that result, so the log gets one line per session, not one per sample.
3. **The migration read the whole table through one cursor.** It now reads 8,192 rows a page, by
   key.
4. **The migration read boots, codes and UIDs with `getInt`,** which turns an out-of-range number
   into a different one. It now reads `Long`s and refuses a number that does not fit.

The review also listed six missing tests. They now exist:
- a mark inside a chunk, for the reads and the cut;
- the Python decoder against the frozen chunk (`report.py --self-test`, in `check.sh`'s static
  step);
- the migration's counter with an empty ring and a mark above every id;
- a refused row, which leaves the database at version 6;
- every stored code fitting the format's bits;
- a read with more tags than one batch of ids.

The paged migration was rerun on the phone's copy. It wrote the same chunks byte for byte, the same
tags and counter, and the same rates.

## Consequences

- SPEC 8 lists `HistoryChunk`, `HistoryCounter` and `HistoryTag.lastEventId` in place of
  `HistoryEvent`, and notes that `WindowStat` has no rowid.
- P5's Timeline and insights read history through the DAO's decoded events, never through SQL.
- A support export (SPEC 14) decodes chunks to rows, so the packed form stays internal.
- Reverting schema 7 means expanding the chunks into rows again; nothing is lost either way.
