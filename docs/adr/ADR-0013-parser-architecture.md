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
  Records are now joined while a quote is open (`CheckinLines`), with a 64 KB cap on length.
  `wua` and `wl` names have commas replaced, so they cannot carry a record. Where a record's end is
  lost (a record past the cap, or a history wakelock tag printed without doubling its quotes),
  nothing after it is read as a record: the S6 parser stops there as at a cut, and S7 keeps only a
  `NEXT:` on the last line (ADR-0014, second amendment).
- **Completeness.** A UID entry the proto adapter cannot decode, or a checkin record whose end is
  lost, makes the dump and the UID list incomplete: per-UID sums are partial.
- **No invented zeros.** A checkin line that is there but short, or has a column that is not a
  number, gives null for that column; 0 stays for a line the writer left out.
- **No values in issues.** A line whose first field is not a short number is "not a checkin
  line", without its text, and no longer marks the dump as another format version.
- **Smaller points.** A broken proto header is "malformed", not "ended early". A proto timer's
  absent maximum reads as null: the writer leaves out both a zero maximum and an untracked one.
  A name holding `",` still splits early; the format cannot tell it apart.

## Amendment 2026-11-06: dumpsys text parsers (S10 to S13, S19, S21)

The `dumpsys` text parsers share `TextDump` (the line layer above, with an unterminated last line
never handed on) and keep their own small walkers. Their dumps print app-chosen text without
escaping: wakelock tags (S12), alarm tags (S10), job namespaces and extras (S13). A line break
in such text can print a line that looks like the platform's own. What each parser trusts:

- **Only `\n` ends a line.** `LineReader` splits on `\n` and drops one trailing `\r`, so a `\r`
  inside app text cannot start a line.
- **`IndentingPrintWriter` indents every line.** `AlarmManagerService` and `JobSchedulerService`
  print through `android.util.IndentingPrintWriter`, which writes the current indent after every
  `\n` inside a printed string (core/java/android/util/IndentingPrintWriter.java L199-253, the same
  file at both tags). App text printed n spaces in therefore never yields a line shallower than n.
  S10 reads only lines two or four spaces in (app tags are six in), so a tag cannot forge another
  app's `Alarm Stats` entry; a stats tag is every deeper line up to the next platform line, line
  breaks included. S13's statistics windows hold no app text. (App text does reach column 0 in
  `Job history`, after the windows; see the review amendment below.)
- **Counts where the indent does not help.** `PowerManagerService` prints with a plain
  `PrintWriter`: the held wakelock list is kept only when the lines between `Wake Locks: size=N`
  and `Suspend Blockers:` are N readable entries and one blank line. S13's job list is printed two
  spaces in, the same depth as a namespace's continuation lines: it is kept only when its `JOB`
  lines match `Registered N jobs`, and a job whose needed field line is missing or appears twice is
  dropped and reported. The pending alarm list must match `N pending alarms`.
- **Headings appear once.** A heading a parser reads, where the platform prints it once, must
  appear once; otherwise that part is null and the dump incomplete. One UI prints released wakelock
  tags before battery saver, which is why S12's saver section follows this rule. `Power Manager
  State:` comes straight after the header, before any app text, so S12 keeps its first copy and
  reports a later one. A forged heading can hide a part but not change it.
- **Read from both ends where text is free.** A wakelock entry is split at the last ` (uid=` (the
  platform's trailer) and the last quote before it, so a tag holding quotes or ` (uid=` stays whole.
  S10's `Alarm Stats` lines are matched whole, and S13 reads only fixed field prefixes.
- **Left out on purpose.** S10 does not keep `Top Alarms`: it is the ten tag entries with the most
  running time, all present in `Alarm Stats`. S13 does not read `Job history`: a namespace can
  forge its lines at the platform's indent, and S7 carries the same starts and stops, with
  attribution and escaped names. S13's per-app stop reasons (`timeout_abandoned` included) come
  from the statistics windows.
- **Unknown names, never values.** OEM keys and names are counted by name only after a name pattern
  check; text that fails the check counts as `<line without key>`. Samsung's extra job stop reason
  `olaf` and Power Manager state names are counted this way.

## Amendment 2026-11-06: independent review of the dumpsys text parsers

A fresh read-only review traced S10 to S13, S19 and S21 against the AOSP printers in both tags and
built crafted inputs against the compiled parsers. It reproduced three forgeries the parsers called
complete and one exception. These corrections and changes follow from it:

- **Correction: app text can reach column 0 in S13.** `JobPackageTracker.dumpHistory` drops the
  indent to 0 and prints each event's tag, which holds the namespace (JobPackageTracker.java
  L582-630, both tags). A namespace may hold line breaks: it is trimmed and rejected over 1000
  characters, not capped (`JobSchedulerService.validateNamespace`, 16 L4995-5008, 17 L5317-5330).
  With fewer than four real historical windows, a namespace could add a window for another app.
  `Current stats` is always the last window (`dump` L536-553) and `Job history` comes after it, so
  S13 now reads no heading once `Current stats` has ended; such headings are reported and ignored.
- **Lines too long to keep.** `TextDump` hands each dropped line to the walker (`onLost`). A
  wakelock tag has no length limit, so two 70 KB lines around a forged entry kept S12's count
  matching and swapped an app's entry for another's. A dropped line between `Wake Locks` and
  `Suspend Blockers` now makes the list untrusted. A bare `  (uid=5)` line made the entry reader
  throw; the trailer must now follow the indent. S10 needs no such rule: its app text is six or
  more spaces in, so a dropped line loses a tag, never forges one. S13's lines cannot reach 64 KB.
  S21 now also requires that no line was dropped.
- **S11 app text.** The reasons in `Temp whitelist schedule` hold a notification `PendingIntent`'s
  intent action (PendingIntentRecord.java, 16 L590-603, 17 L593-606), printed with a plain
  `PrintWriter`. They could set the Doze state, charging and screen values and put an e-mail
  address into `unknown`. The dump runs under the controller's lock in a fixed order, so S11 now
  reads the sections before the schedule from their first copy and ignores later ones. It reads
  `Temp whitelist app ids` and the state lines from their last copy, and does not believe a state
  name printed twice in that copy. Unknown names seen after the schedule count only from the last
  state line on, and every unknown name must pass a name pattern.
- **Private compute core UIDs.** Android 17 prints `u<user>p<n>` for app ids 30000 to 39999
  (`UserHandle.formatUid` 17 L505-531, `Process.FIRST_PCC_UID` 17 L328). They are now read back.
  An unread one would have blanked a whole alarm or job section.
- **Correction to decision 5.** For `dumpsys battery`, OEM `key: value` lines keep their values in
  `extras`: SPEC 5.1 asks for a raw map. The log buffer lines (`[Name]` and the timestamped lines
  after it), which hold serials, are skipped and only the buffer's name is counted. A bracketed
  line with spaces, such as Samsung's `[Not Battery Adaptive Protect Mode]`, is a state that
  changes with a setting; it is counted as `<bracketed line>`.
- **`ParseContext`** cuts a name to 80 characters before looking it up, so a long name already
  counted keeps counting once the map is full.

Left as it is: a dump cut exactly at a line boundary inside the last section still looks whole to
the parser; the transport reports it (ADR-0009).
