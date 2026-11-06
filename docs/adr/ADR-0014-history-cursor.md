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
