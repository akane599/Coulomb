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
