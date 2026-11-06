# ADR-0014: Read the battery history in checkin form with a time cursor

- Status: accepted (amends SPEC 5 S7, 8 `Snapshot`, 19.1)
- Date: 2026-11-06
- Phase: P2

## Context

SPEC S7 reads `dumpsys batterystats --history-start <index>` (or `--history`) and stores
`Snapshot.historyEndIndex`. The AOSP sources say otherwise:

- `--history-start` takes a monotonic clock time and prints the records with `time >= start`. The
  output ends with `NEXT: <last time + 1>`, which is the cursor for the next read.
  - `BatteryStatsService.java`: android-16.0.0_r4 L3119-3128; android-17.0.0_r1 L3169-3178.
  - `BatteryStats.dumpHistory`: android-16.0.0_r4 L7439-7554; android-17.0.0_r1 L7471-7586.
- `--history-start` also sets `writeData`, so the service saves its stats asynchronously after the
  dump (`writeAsyncLocked`). This is the same save the system does on its own: not destructive,
  but a small disk write per read.
- `--history` and `--history-start` set `DUMP_HISTORY_ONLY`, and `-c` selects the checkin format.
  Together they print only the history, in checkin form (`9,h,…` lines), and return
  (android-16.0.0_r4 L7814, android-17.0.0_r1 L7846).
- The text form stamps each record with `MM-dd HH:mm:ss.SSS` wall time in the device time zone,
  without a year. SPEC 7.8 forbids ordering by wall time. The checkin form carries time deltas from
  a `RESET:TIME` anchor, and on the phone it is about 4 times smaller for the same records
  (0.56 MB vs. 2.36 MB).

## Decision

- S7 reads `dumpsys batterystats -c --history-start <cursor>`, starting from cursor 0. A new worker
  mode `CHECKIN_HISTORY` sends exactly these flags, all already on the 19.1 read list.
- `Snapshot.historyEndIndex` becomes `historyCursorMs`, the `NEXT:` value.
- Gaps are explicit. If the first record is later than the cursor, or the history was reset, the
  missing span is recorded as a gap. An end event with no matching start never invents a duration.
- The text history stays available for export (SPEC 14.4) and for diagnostics. Samsung's extra
  per-record fields (`current=`, `skin_temp=`) exist only in the text form. Coulomb does not use
  them in v1: screen-off sessions use the coulomb counter at both ends (SPEC 6.2).

## Consequences

The SPEC edits are the S7 row (5), the `Snapshot` field (8), the 19.1 read row and the S7
reference in 7.2. In-app (T1), the `-c` branch lists apps for all users, so it needs
INTERACT_ACROSS_USERS like proto and checkin (ADR-0010). Adding Samsung's text-only samples later
means parsing the text form as a second, optional source with its wall-clock caveat.

## Amendment 2026-11-06: what the S7 parser relies on

Checked against the printer (`BatteryStats.dumpHistory` and `HistoryPrinter.printNextItem`,
identical in android-16.0.0_r4 and android-17.0.0_r1) and against three real reads on the API 37
emulator (`fixtures/avd-pixel-api37/16571990/history-checkin/`: from 0, from a cursor halfway
through, and from the first read's `NEXT:`).

- **Placing records.** The only absolute time is `NEXT:`, at the end. Records carry offsets from
  the read's first record, and `firstRecordMs = NEXT − 1 − (sum of the later steps)`. The reads
  from 0 and from the middle agree on every event in their overlap, at the same placed times.
- **Gaps.** The first step counts from the oldest record the system iterated, so it is 0 only
  when the read starts at that record. A gap is flagged when the cursor is above 0, the first step
  is 0 and the first record is later than the cursor. With a first step above 0 the buffer still
  held older records, so nothing between the cursor and the first record was lost. `-c` can limit
  the iterated window (`config_batteryHistoryDumpCheckinWindowSize`, 0 in AOSP:
  core/res/res/values/config_battery_stats.xml L69-71); an OEM value would only move the oldest
  record, and the same rule holds.
- **Baselines.** Each dump gets a new printer, and `START` and `RESET` reset it, so the first state
  record after either, and the first of every read, restates the whole state. A `+x` there means
  "on", not "turned on". Records carry a `baseline` flag; a consumer must not count those as
  transitions.
- **Nothing new.** A read with no record at or after the cursor prints only the pool and `NEXT:`
  (last time + 1, so the same cursor when nothing was added). A cursor above `NEXT:` is kept.
- **Failures.** If a record fails to print, the platform prints the stack trace into the dump and
  stops; `NEXT:` then counts the failed record. Times are not placed for that read, the trace text
  is not kept, and the collector records a gap up to `NEXT:`.
- **Tags.** The whole tag pool is printed on every read (149 strings on the emulator, 885 and
  51 KB on the phone). A tag with a line break spans lines and is joined while its quote is open
  (ADR-0013). When the pool is full, tags are printed inline as `<formatted uid>:"text"`; app and
  system UIDs are read back, isolated ones are not.
- **Rail charges** (`Mrc`, `Wrc`) are printed with `DecimalFormat` in the system locale, so a
  Turkish phone writes `1,5`. They are 0 on both test devices and unused; the parser skips the
  fraction.
- **Cost.** `--history-start` also sets `writeData`, which triggers an asynchronous stats write,
  and every read walks the whole history buffer in system_server, whatever the cursor. The
  collector keeps to one read per heavy snapshot (SPEC 7.2).
- **Time base.** History times are `MonotonicClock` milliseconds, which keep counting across
  reboots. Mapping them to `HistoryEvent(elapsedNs, bootCount, …)` is part of the Room schema
  decision (P2), not of the parser.
