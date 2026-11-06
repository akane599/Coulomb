# ADR-0013: Parser architecture

- Status: accepted
- Date: 2026-11-06
- Phase: P2
- Second opinion: an independent read-only review (neutral brief) recommended the same choices
  and raised the risks listed below.

## Context

SPEC 5.1 says parsers are pure Kotlin, streaming and side-effect free, never throw, record bad
sections as `ParseIssue`s with byte offsets, and count unknown fields. Other constraints:
- SPEC 16.5: a 20 MB dump within 64 MB of heap;
- SPEC 7.5: bounded memory;
- SPEC 15.2: `:core:analytics` sees only `:core:model`.

The privileged layer hands parsers an `InputStream` that may be cut short (ADR-0009). The real
inputs are in `fixtures/`.

## Decisions

1. **Proto (S5) is walked, not decoded whole.** Wire's `ProtoReader` reads the two outer messages
   (`BatteryStatsServiceDumpProto` and `BatteryStatsProto`) field by field. Each `uids` entry and
   the `system` message is decoded on its own with the generated adapter, inside a guard. A broken
   entry becomes a `ParseIssue` at its offset and the walk goes on.
   - When the stream is cut, every complete UID survives. The result says whether the UID list
     and the system message are complete.
   - `system` comes last in the dump, so a cut loses device totals first. Analytics must not
     compute an unattributed share from a partial sum.
   - Memory is bounded by the largest single UID (about 23 KB on the phone), not the whole object
     graph.
2. **Text dumps share one line layer and use one small extractor per dump.** There is no shared
   indentation tree: indentation is inconsistent across the real dumps (alarm, jobscheduler,
   battery and power each break it in a different way), and a tree would break the memory bound.
   - **The line layer** reads Okio `BufferedSource` lines with exact byte offsets and caps line
     length. Helpers cover quote-aware CSV, `+1h2m3s4ms` durations, `k=v` / `k: v` pairs and `-1`
     meaning "absent".
   - **Each extractor** is a state machine keyed on exact section headers. It skips what it does
     not need, and counts unknown sections, keys, checkin section ids and extra columns by name.
   - **Versions:** a parser is chosen by SDK level and OEM, then by what the dump says about itself
     (checkin `vers`: CHECKIN_VERSION 36 on both devices; parcel version 1376472 on Samsung vs. 216
     on AOSP). OEM variants extend the AOSP parser.
3. **Output types live in `:core:model`.** They are source-neutral: S5 and S6 produce the same
   records. Counters stay cumulative, exactly as the system reports them, with units in field
   names. There are no windows or snapshot ids at this layer.
   - `ParseReport` (source, bytes read, complete, capped issues, unknown counts) is in
     `:core:model` too, so the capability report can show the unknown counts (SPEC 5.1).
   - Wire classes stay `internal` to `:core:parsing`.
   - History events go to a sink in batches, never into one big list.
4. **Okio is the reader.** It already ships with Wire. A counting source minus the buffer gives
   byte offsets for free; `indexOf` gives capped lines and `rangeEquals` matches headers without
   decoding. `BufferedReader` counts characters, not bytes, and has no line cap.
5. **Privacy.** For dumps that carry identifiers, unknown and generic keys are kept as names and
   counts, never values. The main case is `dumpsys battery`, whose `[BattInfoLogBuffer]` holds the
   battery serial or QR code.

## Consequences

- **Truncation:** a text dump cut exactly on a line boundary looks complete. Completeness
  therefore comes from the transport's status (ADR-0009) plus the missing final newline.
- **Tests:**
  - every fixture must parse with no exception and asserted unknown counts;
  - synthetic tests cover multi-byte UTF-8, very long lines, cuts at every section and a
    full-size history (about 34 MB of text at the phone's 8 MB history buffer, more than 16.5's
    20 MB).
- **Fixture path:** fixtures came from `adb shell dumpsys`, while production uses
  `Debug.dumpService`. One fixture set captured through the production path is still owed.
- **Collector cost:** formatting the text dumps costs system_server CPU, which is drain Coulomb
  causes. Per-UID alarm and job counts already arrive in S5, so the collector (P2, own ADR) reads
  the alarm and jobscheduler text dumps less often than every heavy snapshot.

## Amendment 2026-11-06: zeros, and the checkin parser (S6)

Found while writing the S6 parser and checking it against S5 on the same capture.

- **An absent message is often a zero.** `ProtoOutputStream.endObjectImpl` drops a nested
  message that ends up empty, unless it is a repeated entry (android-16.0.0_r4 and
  android-17.0.0_r1 L2088-2135). So an all-zero `cpu`, `network`, `wifi`, `battery`, `misc` or
  `battery_discharge`, or an all-zero timer, simply disappears. The first S5 mapper read those as
  null ("not reported"); on the emulator that gave `cpu = null` and `discharge = null`.
- **The zero rule, in both parsers.** A counter the writer always covers is 0 when absent, so a
  missing value and a written zero read the same. `bluetooth` and the per-UID `powerMah`, which
  the writer may skip, stay null when absent. Entries with no time are dropped, as the checkin
  writer drops them: wakelocks without partial time, and jobs, syncs, sensors and processes with
  zero totals. Zero stop-reason counts are dropped too.
- **Model changes.**
  - `BinTime` holds `totalMs` and a nullable `count`: checkin prints brightness times without
    counts.
  - `signalScanningMs` replaces a timer the checkin form cannot fill.
  - `SipperKind.UID` is gone: an app's own power is `UidStats.powerMah` in both sources.
- **S6 design.**
  - Each mapped section is an enum of its columns in the writer's order. The writer
    (`BatteryStats.dumpCheckinLocked`) is identical in android-16.0.0_r4 and android-17.0.0_r1.
  - Only the `jbc` columns depend on the SDK level: `JobParameters.JOB_STOP_REASON_CODES` has 15
    codes in 16 and 16 in 17. The parser therefore takes `sdkInt`.
  - Checkin prints counts without their codes, so an OEM's extra stop reason (One UI 9: code 100)
    is counted as unknown column `jbc.18` instead of being named.
  - Known sections the model does not use are skipped without counting them as unknown; unknown
    section ids and extra columns are counted.
- **Cross-check.** On both fixture sets the two parsers agree exactly on every UID they share, and
  on the device totals apart from the realtime clocks (the two dumps are about 0.3 s apart). The
  remaining differences are what checkin cannot carry:
  - CPU times truncated to the millisecond where the proto rounds;
  - no timer maxima for jobs, syncs, sensors and scans;
  - no brightness counts;
  - commas in wakelock and alarm names replaced by `_`;
  - no line for an all-zero UID (345 of 557 on the phone), but extra UIDs that have only a power
    entry (19 on the emulator), which the proto leaves out;
  - an OEM's unnamed stop reasons.
- **Power estimates differ between the two dumps.** The power figures in the phone's checkin dump
  are above those in the proto dump taken 0.3 s earlier (CPU 1045 vs 927 mAh, Bluetooth 1.6 vs
  0.15 mAh), while the per-UID CPU times match. Corrected below: the two paths ask for different
  usage stats.

## Amendment 2026-11-06: independent review of S5 and S6

An independent read-only review of both parsers (neutral brief) found the following; all are
fixed or recorded.

- **The power figures are different quantities.** The proto writer asks the dump helper for
  usage stats with `detailed = false` (BatteryStats.java android-17.0.0_r1 L7930-7932,
  android-16.0.0_r4 L7898-7900), the checkin writer with `detailed = true` (L4523, L4502).
  Detailed adds power models, process-state, power-state and screen-state data and virtual UIDs
  (`BatteryStatsDumpHelperImpl`, android-16.0.0_r4 and android-17.0.0_r1 L31-43), which likely
  explains the UIDs that have only a power entry in checkin. So `powerMah`, `powerUse` and `powerSummary` are not
  comparable across S5 and S6; whether the query alone explains the whole gap is not proven.
  Attribution takes mAh from `BatteryUsageStats` (S4, SPEC 6.5) and never mixes sources in a window.
- **Names with line breaks.** Tag pool strings (`hsp`) and quoted job, sync, process and kernel
  names are written without escaping line breaks; only `wl` names are cleaned. Read line by
  line, the rest of such a name passed for a record, so an app could add rows to any UID.
  Records are now joined while a quote is open (`CheckinLines`), with caps on length and line
  count. `wua` and `wl` names have commas replaced, so they cannot carry a record.
- **Completeness.** A UID entry the proto adapter cannot decode, or an overlong checkin record
  outside the history, now makes the dump and the UID list incomplete: per-UID sums are partial.
- **No invented zeros.** A checkin line that is there but short, or has a column that is not a
  number, gives null for that column; 0 stays for a line the writer left out.
- **No values in issues.** A line whose first field is not a short number is "not a checkin
  line", without its text, and no longer marks the dump as another format version.
- **Smaller points.** A broken proto header is "malformed", not "ended early". A proto timer's
  absent maximum reads as null: the writer leaves out both a zero maximum and an untracked one.
  A name holding `",` still splits early; the format cannot tell it apart.
