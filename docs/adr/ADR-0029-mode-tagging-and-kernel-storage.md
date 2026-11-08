# ADR-0029: Mode tagging, kernel storage and connection handling for two Shizuku modes

- Status: accepted (schema v3; edits SPEC 8; SPEC 4.5's boot-grace row cites it)
- Date: 2026-11-08
- Phase: P4
- Second opinion: a fresh read-only subagent reviewed the draft (ADR-0027 §2). Its verdict was
  "accept with changes"; every change it asked for is in this record, marked (R).

## Context

ADR-0028 makes ADB-started Shizuku (uid 2000) and root Shizuku (uid 0) both full modes and adds four
kernel sources: S9, S25, S26 and S27. This record decides four things: how a piece of data records
its mode, how kernel data is stored and turned into windows, how interrupted reads stay exact, and
how the connection survives restarts and mode switches. It touches the database schema, a stored
format, the worker's AIDL and the connection logic, all costly to reverse.

## Decisions

### 1. The mode is the uid of the connection that did the read

- Each binder Coulomb receives gets a connection number, one higher than the last. The worker
  handshake's uid is no longer for diagnostics only: every worker result carries the uid and
  connection number it ran under (R).
- Kernel results count only when their uid is 0 and every kernel read of the snapshot ran on the
  same connection. A root worker that outlives a switch to shell cannot pass its data off as shell
  data, and a switch in the middle of a snapshot discards that snapshot's kernel reads (R).
- `snapshot.privilegeMode` (nullable small int: 1 shell, 2 root, 3 another uid) records the mode of
  the connection that served the snapshot's worker reads, or the live connection when none ran. Null
  means no Shizuku connection.
- This replaces the drafted per-source routes JSON. The batterystats dumps go in-app first, so a
  routes JSON reads `app` whether Shizuku is stopped, in shell mode or root. It would also cost space
  in rows that are kept, and the size margin is 3% (ADR-0021) (R).
- Kernel statuses appear exactly where the kernel reads were due: on a heavy snapshot whose live
  Shizuku server is root. There `sourceStatusJson` always holds all four (S25, S26, S9, S27): `failed`
  when the root worker could not serve them or the reads were discarded under the rules above,
  `skipped` when the 4 s cap had run out or, for the kernel files only, when the snapshot had no
  usable S5 counters to join. Any kernel value with status `ok` was read as root by construction (R).
- Elsewhere a snapshot holds no kernel key, and `privilegeMode` says why: null for no connection,
  1 for shell, 3 for another uid. A light snapshot reads no kernel source in any mode.
  - Amended during the collector work. The draft gave every snapshot an explicit `unavailable` per
    kernel source. `privilegeMode` already records the same fact, and the four keys would cost about
    80 bytes on each shell heavy snapshot: 3.2 MB a year at the budget's snapshot rate, against a 3%
    margin.

### 2. Worker surface

Two typed methods. Each streams through a pipe, like the dumps:

- `readKernelFiles(sources, pipe)` reads S25, S26 and S9 in one call. Its output is length-framed
  records, `@<path> <bytes>` followed by the bytes and a newline, or `!<path> <errno>`, then a
  final `#end`; the first record is `boot_id`. One call replaces the roughly 5,000 binder calls
  that per-file `readSysfs` reads of S9 would take on a phone (R).
- `readKernelLog(afterSeq, pipe)` opens `/dev/kmsg` with O_NONBLOCK. `/dev/kmsg` cannot seek to a
  sequence number, so the worker reads from the oldest record and skips to `afterSeq`. It keeps only
  records whose text matches the S27 allowlist; nothing else leaves the worker, because the kernel
  log can hold addresses and serials. The stream is:
  - a header `#begin boot=<boot_id> first=<seq>`;
  - the kept records, unchanged;
  - an inline `#gap from=<seq> to=<seq>` at every sequence jump, including one at the start when
    the ring no longer reaches `afterSeq + 1` (R);
  - a trailer `#end last=<seq> lost=<n>`.

  Kept records are capped per read, so a storm of failed suspends cannot flood the database (R).
- `boot_id` comes from `/proc/sys/kernel/random/boot_id`. Its first 64 bits are the kernel boot key
  stored with every kernel row (R).

### 3. Schema v3

The migration from v2 adds:

- `snapshot.uptimeMs` (CLOCK_MONOTONIC at the light read). It aligns the kernel log's clock, which
  also stops in suspend, and gives each window's deep-sleep time in both modes as elapsed minus uptime.
- `snapshot.privilegeMode` (§1).
- `kernel_read`: one row per snapshot that read any kernel source, with the snapshot id as primary
  key and foreign key (cascade). The draft's three tables are merged into this one row (R). Columns:
  - the kernel boot key;
  - S25: every counter, plus the last failure's device, errno and step;
  - S26: the last resume reason, the suspend and resume time, and the sleep time;
  - S27: the read's `afterSeq`, first and last sequence numbers, and its gaps;
  - nullable throughout, so a source not read `ok` is null, never zero.

  S25 and S26 are stored only on heavy snapshots; the S27 columns may sit on any snapshot.
  S25 and S26 are cleared with the snapshot's counters; the S27 columns stay for the 7-day S27 ring.
  The row goes once nothing is left in it (R).
- `wakeup_source_stat`: one row per source instance (`snapshotId`, the N of `wakeupN`, the name as
  text, the nine counters). Primary key `(snapshotId, instance)`; foreign key to the snapshot only.
  - The name is text, with no foreign key to `wake_tag`, whose prune
    (`DELETE FROM wake_tag WHERE id NOT IN (SELECT tagId FROM window_wake)`) must stay valid (R).
  - Rows are per instance, not summed by name, so a destroyed and recreated source shows as a reset
    instead of hiding inside a sum (R).
  - Stored only on heavy snapshots, and pruned with the other per-snapshot counters (R).
- `kernel_log_event`: `id`, kernel boot key, `seq`, `monotonicUs`, kind code, `irq?`, `tagId?`
  (a `history_tag` id, whose prune is extended to these rows), `errno?`, and `readSnapshotId`
  (foreign key, cascade).
  - It is unique on `(kernelBoot, seq)` and inserted with INSERT OR IGNORE, so a re-read is idempotent.
  - Rows leave at the 7-day cut of the S27 ring together with the span of the read that stored
    them, so a window built later never counts a range as covered without its events. They also
    leave with that snapshot. A snapshot that ends a kept window stays for good, so the cascade
    alone would not give a ring (R).
  - The ring is 7 days, not the history ring's 30, to fit the size budget (see Size). Windows are
    built within hours, so their S27 counts are made long before the cut. The ring only limits
    how far back a missed window can be rebuilt, and such a window then shows S27 as partial.
    The S7 history ring keeps 30 days of wakeup reasons in both modes.
  - The suspend entry and exit lines are not stored. Suspends are counted from S25, so they would
    add two rows per suspend that nothing reads (R).
- `window_wake.wakeups` (nullable): the wakeup-count change of an S9 source. `WakeKind` gains
  `WAKEUP_SOURCE` (S9), `RESUME_CAUSE` and `SUSPEND_ABORT` (S27).
- `window_result.kernelCoverage` (nullable int): two bits per source, for S25, S9 and S27 in that
  order. 0 is not collected, 1 is partial ("at least"), 2 is whole. Null means no kernel source was
  collected for the window. Snapshot counters are pruned within hours but windows stay, so
  kernel data has to live on windows, and a missing `window_stat` row cannot tell zero from "not
  collected" (R).

S25 deltas become `window_stat` device rows (keys `s25.success`, `s25.fail`, `s25.failed_<step>`;
Measured). S9 deltas become `window_wake` rows of kind `WAKEUP_SOURCE`. S27 counts become
`window_wake` rows of kinds `RESUME_CAUSE` and `SUSPEND_ABORT`. A missing row means zero only where
`kernelCoverage` says that source is whole.

### 4. Windows: "not collected", never zero

- **S25:** a window has deltas only when both endpoints read S25 `ok` under the same kernel boot
  key and no counter fell. Otherwise the S25 coverage is 0 and no row is written.
- **S9:**
  - An instance present at both endpoints with no field fallen gives its delta.
  - A fall in any cumulative field, `maxTimeMs` included, is a reset: that instance has no delta,
    and the window is partial. `activeTimeMs` (the current active stretch) and `lastChangeMs` (a
    time stamp) are not cumulative and are left out of the rule (R).
  - Only a source whose wakeup count rose gets a row, with its time alongside. Wakelock time on its
    own is in S5's kernel wakelock rows (R).
  - An instance present only at the end was created inside the window, so its value is its delta.
  - An instance that vanished makes the window partial.
  - Sums by name happen only here, when the window is built (R).
- **S27:**
  - A window (A, B) is whole only when every read in (A, B] ran under one kernel boot key, and
    together, minus their gaps, they cover the sequence range (A's last, B's last].
  - Its events are exactly the records with `seq` in that range, so each record lands in one
    consecutive window.
  - Records recovered later, after an ADB-mode span, are kept with their own times. A window
    already built keeps the coverage it had when it was built (R).
  - `SUSPEND_ABORT` counts the `Abort:` lines. `print_wakeup_sources` prints one per aborted
    attempt (kernel/power/wakeup_reason.c, android16-6.12 L274-325). Device and freeze failure
    lines print alongside it, so they are detail and never add to the count (R).
  - `RESUME_CAUSE` counts one per leaf interrupt line. A resume with two interrupts counts for
    both, so the counts can add up to more than the number of resumes.
- **No double counting across sources:** S27 overlaps S7's wakeup reasons, and S9 overlaps S5's
  kernel wakelocks. A screen shows one or the other, never their sum. Suspends are counted from
  S25, never from S27 (R).
- **Charts:** a span with coverage 0 renders as a gap labelled "not collected", with the reason
  (Shizuku in ADB mode, Shizuku stopped, kernel path closed) taken from `privilegeMode` and the
  source statuses.

### 5. Interrupted reads

- One transaction per snapshot (unchanged).
- A kernel file source cut short stores nothing for that source: its status is `partial`.
- A kernel log read cut short stores its exact prefix, and its last sequence is the last record
  kept.
- The next read's `afterSeq` comes from the database: the latest stored read with the same kernel
  boot key, never from memory, and never across boots (R).
- Counters are cumulative, so a retried read is just another reading. Events are unique per
  `(kernelBoot, seq)`.
- Kernel file reads get the same sleep check as S5. A read during which the CPU slept is not stored,
  and its sources read `slept` (ADR-0018). S27 needs no such check: each record carries its own
  sequence number and time stamp.
- The kernel files are read only when the snapshot's S5 counters are usable, since their counters
  end windows beside S5's. The S27 read does not depend on them.
- Without a kernel boot from the files, the S27 read has no stored cursor to use and starts at the
  ring's oldest record. Records already stored are ignored on insert, so nothing is counted twice.
- Order inside a heavy snapshot: S5, then S25, S26 and S9, then S7, then S27, all inside the 4 s cap
  (R).

### 6. Connection handling

- **Boot grace:** if the process started less than 10 minutes after boot, `NoBinderCheck` runs at
  180 s after process start instead of 2 s. Until then the manager stays `UNKNOWN`, which shows no
  issue. No new enum value is needed (R).
  - The sticky listener already delivers a late binder, so there is no polling.
  - A binder that arrives before its server answers `pingBinder` is retried at 1, 2, 4 and 8 s,
    instead of being dropped (R).
- **Quick restarts:** after `BinderDead` the tier drops at once, but "Shizuku is stopped" shows only
  after 8 s without a new binder, so a restart or mode switch does not flash an error (R).
- **Bind timeouts:** a bind timeout stays sticky for the session only when no bind has ever worked
  with this manager package and version. The ledger keeps that as a new key. Otherwise the worker
  retries with backoff (5, 10, 20 s, then every 60 s), so a timeout right after a switch does not
  shut root data off for the session or show the Android 17 hang message (R).
- On every new connection the kernel capabilities are probed again. At uid 2000 they become
  UNAVAILABLE (fix NEEDS_ROOT) without touching the files.

## Review

An independent review of the kernel parsers and the worker's stream writers found two defects;
both are fixed with regression tests:

- **A read with nothing new since its cursor was marked incomplete.** The trailer check took any
  cursor as "something was read". Every quiet interval would have lost its S27 coverage.
- **A cut kernel-file stream could report S9 complete.** A cut just before a `wakeupN` directory
  left nothing for that directory's parser to miss. `KernelSources.split` now marks the source
  being read at the cut as incomplete.

It also suggested three changes, all made:

- A listing past the 4096-entry cap now reads as not listable instead of a complete read missing
  sources.
- The file stream has a 16 MiB total cap.
- The S9 reset rule leaves out `activeTimeMs` (§4).

The allowlist prefixes it could not check (`Resume caused by …`, `Abort: …`) match
`print_wakeup_sources` on android16-6.12 and android15-6.6. That file defines no `pr_fmt`.

A second independent review covered schema v3 for data safety: the migration, the delete paths
and the DAOs.
- **One latent defect, fixed:** a retried read covers records whose rows an earlier read owns.
  If the S27 cut fell between the two reads, the events went with the earlier read but the retry's
  span stayed, so a window built later would count the range as whole with zero events. The ring
  now clears the old spans first and deletes only events no kept span covers. No collector code
  wrote kernel rows yet, so nothing was affected.
- **Test gaps, now filled:** the order of the two rings in retention, a row with only S25 or only
  S26, a read exactly at the cut, which read owns a record read twice, and the cascade when an
  unneeded snapshot is deleted.
- **Nits, fixed:**
  - The S27 ring now frees its tags even before the database is old enough for a history cut.
  - `kernelLog` takes a null cursor instead of 0.
- **Found safe:**
  - The migration matches Room's exported schema exactly and rewrites no row.
  - The stored codes are pinned by tests.
  - Both `NOT IN` tag lists exclude nulls.
  - Every child row gets the snapshot id inside the one transaction.
- **Its open question:** a kernel boot more than 7 days without an S27 read loses all its spans,
  so the next read starts at the ring's oldest record and may store pruned records again. They
  are never counted twice. A window counts only the range after its start's own kept span, and no
  snapshot in those 7 days has one.

## Size

`SizeBudgetTest` measures the root rows at upper-bound rates: 3,000 S27 events a day, 300 S9
sources per heavy snapshot, and 20 kernel rows per kept window.
- Bytes per row: an S27 event takes 69.2, an S9 row 58.7 and a kernel window row 47.1.
- Root adds 5.5 MB after a year. A 30-day S27 ring would add 10.3 MB, most of it events.
- The test fails above 6 MB, or when an event row grows past 72 bytes.
- The kernel status keys need no extra line: the snapshot row is measured with 18 status keys,
  and the engine writes at most 7 (S5, S6, S7 and the four kernel keys). The snapshot row is now
  measured with `uptimeMs` and `privilegeMode` set, as every snapshot stores them.

With shell data, the year projects to 115.7 MB at ADR-0015's rates plus 5.5 MB from root. At the
stress test's history rate (145.5 MB, ADR-0021) plus root's upper bound, the total is about 151 MB,
just over 150. That history rate is itself an upper bound, so this case adds two upper bounds. The
phone night's clean history rate replaces it. If the total is still over 150, ADR-0021's margin
step 1 comes first: lookup ids for `window_uid`, which is lossless.

## Consequences

- Room schema v3, with a migration and a migration test. The size projection gains the S27 ring and
  the S9 rows, capped by pruning (see Size).
- **SPEC 7.7** gains a row: root kernel log events are kept for 7 days in a ring.
- Root costs a worker bind on each heavy snapshot (474 ms wall on the phone, ADR-0018). The
  capability report records the bind and kernel read cost. That cost is measured on the phone when
  the owner next allows a session; the emulator gives a first number.
- The S27 read starts from the ring's oldest record every time; its cost on the S25 Ultra's ring is
  measured with the rest.
- Root sources have no phone fixtures, because the owner's phone has no root. Golden tests use the
  API 37 emulator's real files and kernel-format cases taken from the kernel sources (ADR-0028),
  marked hardware-limited per protocol G.
