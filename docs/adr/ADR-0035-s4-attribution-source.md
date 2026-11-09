# ADR-0035: S4 as the breakdown source: per-app energy by component and process state

- Status: accepted (fourth revision, after three second opinions under ADR-0027 §2; the last
  re-review accepted it with the changes applied here)
- Date: 2026-11-08
- Phase: P4

## Context

SPEC 6.5 takes attribution from `BatteryUsageStats` deltas: mAh per UID, per power component and per
process state. SPEC 7.2 reads S4 at every heavy snapshot. P2 stored only what it consumed (ADR-0018):
S5's per-UID total, as `uid_power` rows with `component = "all"` and `processState = "any"`, plus the
device's power items. ADR-0018 moved S4 to P4.

P4's screens need what S5 cannot give:

- Drain's app rows expand to component chips, and its Components lens ranks components with their top
  apps (SPEC 10.3);
- App detail shows a component breakdown and a process-state breakdown "in the window" (SPEC 10.4).
  Its 14-day trend is the app's total, which S5 already gives.

S5's proto has one power figure per UID. Its device items have names only for the old sipper kinds,
so CPU, GNSS, sensors, audio, video and wakelocks all fall into one unnamed row
(`CounterMapper.devicePower`).

The second draft was rejected by its re-review. The third followed the reviewer's simpler design:
S4 breaks down short intervals that were wholly on battery, and S5 stays the source of every total.
Its re-review accepted the design with five major changes (cost, the sum check, an agreement rule, the
size gate, a safety statement) and four minor ones. This revision applies them.

### What the platform offers (verified at android-16.0.0_r4 and android-17.0.0_r1)

Line numbers are 16 / 17 where they differ; paths are under `frameworks/base`.

- **The dump.** `dumpsys batterystats --usage --proto` reaches `dumpUsageStats`
  (`services/core/java/com/android/server/am/BatteryStatsService.java` L3229-3243 / L3277-3291,
  L3004-3037 / L3056-3089). It queries `BatteryUsageStats` with process-state, screen-state,
  power-state and power-model data and a maximum age of 0, and writes
  `android.os.BatteryUsageStatsAtomsProto` (`core/java/android/os/BatteryUsageStats.java` L485-506).
  - Only the arguments after `--usage` are read; `--proto --usage` gives text.
- **What each dump forces.** Both S5's dump and S4's start with `syncStats` (L693-696 / L722-726):
  a forced power-stats sample, then an external-stats sync with `UPDATE_ALL`. S4 then forces one
  more sample before its query (L3025 / L3077). The forced sample schedules the CPU, wakelock, screen,
  mobile radio, Wi-Fi, Bluetooth, camera, GNSS and custom collectors (`BatteryStatsImpl`
  L14887-14901 / L14479-14493). So S4 repeats the controller queries S5 already makes, which costs
  energy in the radios as well as `system_server` CPU.
- **Each query rebuilds the session from the history.** Neither dump gives the query an end time
  (`BatteryUsageStatsProvider` L274-288), so the end is `UNDEFINED`, -1 (`MonotonicClock` L54). The
  exporter then skips every stored power-stats span (`PowerStatsExporter` L100) and aggregates the
  whole session from the battery history (L124-127; `BatteryStatsHistoryIterator` L61). Each S4 read
  therefore adds a second pass over the whole history, on top of S5's, and its cost grows with the
  session's length until the history reaches its cap. All of it runs in `system_server`, under UID
  1000, so protocol E, which reads Coulomb's UID, cannot see it.
- **Permission.** `checkDumpAndUsageStatsPermission` (L3088 / L3140) wants DUMP and usage-stats
  access (`DumpUtils` L98-164). Coulomb's T1 grants (ADR-0010) pass, and so does the worker. A
  failed check denies the whole dump; no UID is filtered out.
- **Not destructive, not quite read-only.** The dump does no reset, history clear, file deletion or
  stats write. The forced samples append power-stats records to the battery history, which the
  platform caps and rolls over (`BatteryStatsImpl.recordPowerStats`, L10893 / L10455).
- **Faking the plug state is destructive.** `dumpsys battery set ac 1` and `dumpsys battery
  unplug` reach `setOnBatteryLocked`, and an unplug at 90% or more after charging resets the
  statistics (`BatteryStatsImpl` L13530-13554 and L13569-13606 at 16). The emulator capture shows
  it: its session starts at the script's unplug. Plug faking is therefore for the emulator only.
- **Plugged-in energy is in every S4 total.** With power-state data asked for, the exporter adds the
  battery state and the plugged-in state into each `(component, all process states)` total
  (`services/core/java/com/android/server/power/stats/PowerStatsExporter.java` L166-181,
  L229-232; the per-UID path L290-299, L383-392). The per-state parts are written unlabelled and
  zero ones are skipped (`PowerComponents.java` L244-279), so the battery part cannot be taken back
  out. S5 asks with `detailed = false` (`BatteryStats.java` L7898-7900 / L7930-7932,
  `BatteryStatsDumpHelperImpl` L31-43), which counts the battery state only (exporter L174-178).
  Over a stretch wholly on battery, their changes are the same quantity.
- **On battery, provably.** S5 reports `total_realtime_ms` and `battery_realtime_ms` since the reset
  (`BatteryStats.java` L8466-8472 / L8499-8504, `BatteryStatsImpl` L13936-13948 / L13533-13545).
  Equal changes between two snapshots in one session mean the whole stretch was on battery, even if
  Coulomb missed a plug. Battery time stops when the platform sees the plug; S5's clocks are read
  later, after the broadcast, the light read and S5's own sync.
- **Session.** Cumulative since the last statistics reset. S4's `session_start_millis` is the
  session's start clock time (`BatteryUsageStatsProvider` L272-279), the same value as S5's
  `start_clock_time_ms` (`BatteryStats.java` L8465 / L8497; `BatteryStatsImpl` L372 / L310). S4's
  `session_end_millis` is the wall clock at the query.
- **Totals can go down.** Each query rebuilds the session from the battery history, and the history
  rolls over at its cap. Energy recorded in the records that rolled off is gone from later totals.
- **Schema** (`core/proto/android/os/batteryusagestats.proto` L30-138, identical at both tags):
  session start, end and duration; the device consumer; one consumer per UID, each with power
  components (id, deci-coulombs, duration) and process-state slices.
- **Units.** dC = round(mAh × 36) (`BatteryConsumer.java` L761-764). Each entry is rounded by
  itself, and so is a consumer's total (L733).
- **Ids** (`BatteryConsumer.java` L78-111, `POWER_COMPONENT_COUNT` = 19):
  - 0-17 are named components. Ids 7, 13, 16 and 17 are not produced on 16 or 17
    (`MultiStatePowerAttributor` L70-213).
  - Id 18, `POWER_COMPONENT_BASE`, carries time in state for attribution, not energy (L107-108,
    L937-941).
  - Ids 1000-9999 are custom components.
  - Custom components and the split components are divided by process state; the others are not
    (L224-242, L937-941). Energy with no process state is filed as background
    (`PowerStatsExporter` L347-357): the emulator capture shows UID 1000's sensor energy in
    background slices.
- **Entry order.** The dump asks for screen and power states, so after each total the writer emits
  one entry per non-zero screen × power slot, without labels (`PowerComponents.java` L244-279).
  - The total comes first by layout order: the keys with no screen and no power state are laid out
    first (`BatteryConsumer.java` L885-903).
  - The exporter writes no `(component, process state)` total; the writer computes one for every
    key left unset, by adding the keys that match it (`PowerComponents.java` L84-101, reached via
    L250-252; `Key.matches` L411-430).
  - The writer skips an entry whose energy and duration are both 0 (L256-259).
  - On the API 37 emulator, the wakelock component's background slices read 44, 44, 22, 22, 22 and
    22 ms: the total, then its parts.
- **Consumer totals.**
  - A UID's total is the sum of every key with no process, screen or power state: all components,
    ids 17 and 18 and custom included (`PowerComponents.getTotalPower` L524-533).
  - The device consumer's total is the measured discharge, set apart from its components
    (`AggregateBatteryConsumer.getConsumedPower` L61, set at `PowerStatsExporter` L245-253). The
    consumer is left out when that rounds to 0 (`BatteryConsumer.java` L728-740). The emulator
    capture has none.
  - S5's device items are the device consumer's components 0-18, without custom components
    (`BatteryStats.java` L8702-8746 / L8734-8782). Their sum is a window's modeled total
    (`WindowBuilder.modeled`).

### Cost

On the API 37 emulator (2026-11-08, six alternating runs each, unplugged by `dumpsys battery
unplug`):

| Dump | Wall time | system_server CPU | Size |
| --- | --- | --- | --- |
| `--usage --proto` (S4) | 57–78 ms | 10–30 ms | 361 B |
| `--proto` (S5) | 35–48 ms | 10 ms | 12.7 KB |

The emulator's session was 157 s long, and it has no power model and no modem, Wi-Fi or Bluetooth
stats. These figures cannot show the history pass, which grows with the session. On the phone the
heavy snapshot already misses SPEC 16's process-CPU target (at most 604 ms against 300 ms; wall p90
1,229 ms; `docs/progress.md`, P2).

## Decisions

1. **S4 is read as `dumpsys batterystats --usage --proto`**, in the app's process first (ADR-0010
   grants), the worker when that fails.
   - R: the dump has a published proto and needs only the grants S5 holds. The binder query could
     leave out the power state, but it needs a system API in the worker and its parcel types, and
     a worker round trip at every heavy snapshot. SPEC 4 lists S4 as T2 with a T1 fallback; this
     reverses the order for that reason. Decision 4 makes the power-state difference harmless.
2. **S4 is off until the phone shows what it costs.**
   - A stored setting, **Component breakdown**, Off by default, turns it on. It sits with the
     advanced settings and says that it adds a system report at each snapshot. Drain and App detail
     name it where a breakdown is missing because it is off.
   - **The bar for turning it on by default**, by a new ADR: on the phone, S4's added
     `system_server` CPU per heavy snapshot (S4 on minus S4 off, from `/proc/<pid>/stat`) is at
     most 150 ms at p90, over at least 20 heavy snapshots taken while the statistics session is at
     least 48 h old. The same run records S5's own `system_server` CPU, which nothing has measured
     on the phone either.
     - R: 150 ms is half the heavy snapshot's 300 ms CPU target (SPEC 16). At the stress test's
       rate it is at most about 15 s of CPU a day.
   - The phone run waits for the owner (SPEC 0.6); until then the gate is "pending real device".
   - R: SPEC 0.2 puts the self-drain budget above the functional spec, and the cost cannot be
     measured off the phone. Off by default is the cheaper and easier-to-revert choice (SPEC 0.3).
3. **When: right after S5, under the same sleep check.**
   - S4 is read only when S5 (the proto, not S6's checkin stand-in) is `ok`, and only when the
     snapshot is unplugged, is the plug-in snapshot, or its plug state is unknown. A snapshot
     plugged in before and after has no interval on battery to end or start.
   - It is skipped when S5 finished more than 1,000 ms after the snapshot started (SPEC 16: under
     1.5 s). A skip costs the intervals on both sides.
   - Its own timeout is 1 s, below SPEC 7.5's 2 s per source, so a hang leaves S7 and the kernel
     reads their share of the 4 s cap.
   - The sleep check (`CollectorEngine.slept`) runs again after S4. A sleep during S4 marks S4
     `slept`; S5 keeps its own status.
   - Its status goes into `sourceStatusJson` as `S4`, with `off` while the setting is off. A
     skipped, failed, partial or slept S4 never makes the snapshot `partial`: S5 still gives every
     total.
   - R: reading S4 next to S5 keeps its moment within one dump of S5's clocks. Read after S7 and the
     kernel files, it trailed them by seconds, or by a sleep.
4. **Parsing and validation:** a Wire parser in `:core:parsing`, with the proto vendored and marked
   Modified (its `java_package`, and the unused `proto_logging` import dropped).
   - Per consumer and component, the first entry is the total; later entries for the same id are
     skipped. The same holds for a slice's (component, process state) pair, whose first entry is
     the total the writer computed (see Entry order). A test decodes the emulator's wakelock slices.
   - A UID consumer with no component entries counts as absent.
   - **Checked before anything is dropped.** Any failure makes S4 `partial` for that snapshot, and
     no interval uses it. The capability report counts failures per rule.
     - Per UID consumer, the totals of all its components, ids 17, 18 and custom included, add up
       to `total_consumed_power` within 0.5 dC × (n + 1), where n is the number of component ids
       in the layout, custom ones included. The writer skips entries that round to 0 dC, and each
       can hide up to 0.5 dC.
     - The device consumer is exempt: its total is the measured discharge.
     - Ids 17 and 18 carry no energy, in any consumer.
     - No value is negative.
     - Per component, its process-state slices add up to no more than its total, within the same
       rounding.
     - `session_start_millis` equals S5's `start_clock_time_ms` from the same snapshot.
     - The device consumer is present: without it the device's components are unknown.
   - **Mapped to fixed columns**, in deci-coulombs:
     - one column per produced component: screen, CPU, Bluetooth, camera, audio, video, flashlight,
       mobile radio, sensors, GNSS, Wi-Fi, wakelock, phone, ambient display;
     - `custom`: ids 1000-9999 summed;
     - `other`: ids 7, 13 and 16, any unknown id below 1000, and any id of 10000 or more, with one
       parse issue per dump for an unknown id;
     - four process-state columns (foreground, background, foreground service, cached), each the
       sum of that state's slices over all components.
5. **The S4 interval is the consecutive window** between two successive heavy snapshots
   (`SnapshotWriter.afterSnapshot`). It gets a breakdown only when every rule holds:
   - both ends have `S4 = ok`, and the window has a modeled total from S5;
   - **wholly on battery, no reset:** `battery_realtime_ms` and `total_realtime_ms` changed by the
     same amount within 1 s, or within 5 s when the later end is the plug-in snapshot, and S5's
     session start is the same at both ends;
   - **nothing went down:** no column of a UID present at the earlier end, and no device column, is
     lower at the later end by more than 1 dC, and no such UID is missing;
   - **S4 agrees with S5:** the device row's columns, without `custom`, add up to the window's S5
     modeled total within 36 dC (1 mAh) plus 2% of it; and each UID row's component columns add up
     to that UID's `window_uid` mAh within 18 dC plus 2% (`OTHER_UID` included). A failure logs a
     parse issue.
     - R: both "Not broken down" figures are subtractions (decision 7). A parser bug, a plugged-in
       tail or an unseen reset would land in them unseen, and chips that add up to more than the
       app's total would be hidden. The margins cover the per-entry rounding and the gap between
       the two reads (S4 trails S5 by one dump at each end). The phone run checks them; an ADR
       changes them if real reads fail.
   - Otherwise the window has no breakdown. Missing values never become zeros.
   - The plug-in tail is energy used on the charger. At 5 s and 5 W it is 25 J, about 1.8 mAh at
     3.85 V; at the 1–2 s the path likely takes, under 0.7 mAh. The phone measures the real delay
     from the plug to S5's clocks, and a new ADR changes the 5 s if it runs longer.
   - R: consecutive windows are short, and most fall wholly on battery or wholly on the charger.
     Day, session and reference windows almost always contain a charge, so none of them is checked:
     spans add up their consecutive windows (decision 7), as ADR-0036 §3 does for measured drain.
6. **Storage: two wide tables and a reason** (schema version 4, one migration).
   - `uid_component_power` (snapshotId, uid, the 20 columns of decision 4): S4's cumulative values
     per heavy snapshot, one row per consumer, the device under `DEVICE_UID`, written only when S4
     is `ok`. It is pruned with `uid_power` (`SnapshotDao.pruneCounters`: after 2 h, except the
     snapshots it keeps).
   - `window_component` (windowId, uid, the same 20 columns): the changes over an S4 interval.
     - Rows for the UIDs the window keeps in `window_uid` (`WindowBuilder.detail`: at least 0.1 mAh
       by S5, at most 30, and Coulomb's own), one `OTHER_UID` row summing every other UID, and one
       `DEVICE_UID` row: at most 33 rows. Every eligible window has its device row, so the device
       row marks coverage.
     - No floor: the rows add up exactly to S4's change.
     - It references `window_result` like `window_uid`, so a deleted window takes its rows along,
       and `WindowDao.pruneDetail` deletes them too.
   - `window_result.breakdown`, a nullable small integer on consecutive windows: broken down, or the
     first rule that failed (S4 off, an end not `ok`, no modeled total, not wholly on battery,
     a value went down, S4 and S5 disagree). The phone run measures coverage from it.
   - One row holds a UID's components and its states as separate columns. No query can add the two
     dimensions together, and component and state need no lookup codes.
   - `uid_power`, `window_uid`, `WindowBuilder.detail` and the modeled total stay as they are, from
     S5 alone. No S4 window writes or changes S5 rows.
   - Integers in dC are lossless: the windows' changes are exact sums of the proto's values.
   - R: consecutive windows are deleted after 30 days (ADR-0021 §2), as far back as any screen
     shows a breakdown: Drain's custom range reaches 30 days (ADR-0036), and App detail shows its
     breakdowns for Drain's window. Day, session and reference windows get no rows, so S4 adds
     nothing to a year's growth after day 30.
7. **A span adds up its consecutive windows, and says how much of it is broken down.**
   - Covered windows: the span's consecutive windows with a device row in `window_component`.
   - **Device (Components lens):** the device rows' columns summed over the covered windows, and
     "Not broken down" for the rest: the span's modeled total less the covered windows' modeled
     totals, from S5. Without `custom`, the device row covers ids 0-18 as S5's device items do, so
     its parts add up to S5's modeled total (decision 5 checks it). `custom` shows in app chips
     only, as S5's UID totals include it.
     - When the covered windows hold less than half of the span's modeled total, the lens shows S5's
       coarse device rows instead, as it does today, with their note.
   - **An app (chips, App detail):** its rows summed over the covered windows; "Not broken down" is
     its total over the span (S5) less the sum of its chips. It also holds the windows in which the
     app fell into `OTHER_UID`. Decision 5 keeps it from going below zero by more than the margin;
     a negative remainder shows as none.
   - **Process states:** the four state columns summed, plus "Not split", the app's chip sum less
     its state sum. It holds the components the platform does not divide by state: screen, phone,
     ambient display and `other`.
   - DrainReader reads components only from `window_component` when it uses S4, never mixed with
     S5's device rows in one breakdown.
   - R: each interval is exact by itself, and hiding the uncovered share would present a part as
     the whole.
8. **The component palette maps the platform's components** to DESIGN.md's data hues: screen, CPU,
   mobile radio, Wi-Fi, GNSS, Bluetooth and sensors each get theirs, and the rest share the neutral
   ones (ADR-0036 decision 14). Labels come from strings, not from stored names.

## Size

The upper bound puts every consecutive window on battery with S4 read:

- ≤ 150 consecutive windows a day (the stress-test rate, ADR-0021) × 30 days;
- ≤ 33 rows a window (30 kept UIDs, Coulomb's own, `OTHER_UID` and `DEVICE_UID`); about 14 at the
  stress test's 21.4 `window_uid` rows a window;
- ≈ 58 bytes a row: 20 small integers, the row header, and the primary key's index;
- ≈ 8.6 MB at day 30 at 33 rows (3.7 MB at 14), flat after it. `uid_component_power` adds about
  0.2 MB: two hours of snapshots with one row per UID.

The stress test's history rate gives 145.5 MB (ADR-0021) and root adds up to 5.5 MB (ADR-0029), so
the year was already near 151 MB. With S4's upper bound it is about 160 MB. Each term is an upper
bound, but the sum is over 150, so ADR-0021's margin step 1 is taken now, in the same migration:
`window_uid`'s `component` and `processState` become lookup ids, which is lossless. The gate is
measured:

- `SizeBudgetTest` measures a `window_component` row and caps it at 64 bytes, and measures the
  `window_uid` row after step 1.
- The 30-day stress test runs again with S4 dumps in its synthetic counters, shaped like S5's UIDs,
  on every heavy snapshot. It adds root's 5.5 MB bound, asserts at most 33 component rows a window,
  and asserts a projection under 150 MB.
- If the projection still reaches 150 MB, component rows are kept 14 days, by ADR, with Drain saying
  when a span reaches past them.

## Consequences

- **Schema 4.** The migration adds the two tables and `window_result.breakdown`, and converts
  `window_uid`'s two text columns to lookup ids. `MigrationTest` and `SizeBudgetTest` gain them.
- **Settings.** The Component breakdown setting, Off (decision 2).
- **Collector.**
  - `HeavyReader` reads S4 right after S5 when the setting is on; `CollectorEngine` sleep-checks it
    and applies the cut-off and the timeout.
  - `CounterMapper` maps S4 to `uid_component_power`.
  - `WindowBuilder` checks decision 5 for consecutive windows, writes `window_component` and sets
    `breakdown`.
  - `SnapshotWriter` passes both ends' S4 rows and statuses.
- **Capabilities.** SPEC 4.6's probe gains S4: a usage dump that parses and validates, with the
  failures per rule.
- **Blob.** The raw S4 bytes go into the snapshot's blob as their own entry, next to S5's, when the
  snapshot keeps one: plug, boot, package-replaced and first-of-day heavy snapshots
  (`CollectorEngine.writeBlob`, ADR-0018). They serve diagnosis and fixtures; windows cannot be
  rebuilt from them in general.
- **Fixtures.**
  - `tools/fixtures/capture.sh` adds `dumpsys batterystats --usage --proto`. It never fakes the
    plug state.
  - The emulator joins the fixture set with two captures, unplugged and plugged in. Plug faking
    lives in an emulator script that refuses to run unless `ro.kernel.qemu` or `ro.boot.qemu` is 1,
    and ends with `dumpsys battery reset`.
  - Built protos cover what the emulator does not: energy values, duplicate entries, skipped zero
    entries, custom components, energy in ids 17 or 18, a negative value, slices above a total, a
    missing device consumer, and a device total that differs from its components.
  - A phone capture waits for the next session the owner allows. Until then, the parser's gate for
    energy values is "pending real device".
- **The phone run** (with the owner's go-ahead): the cost bar of decision 2, coverage by
  `breakdown` reason, the agreement margins of decision 5, and the plug-in delay.
- **Review.** The parser, the validation and decision 5 get their independent review before the P4
  gate (AGENTS.md).
- **Old windows.** Windows from before the update have no breakdown; spans show them as "Not broken
  down" until they age out.
- SPEC 4's S4 row ("via `BatteryStatsManager` / `IBatteryStats`") is read here as "via the dump of
  the same object"; SPEC.md is not edited.
- **Worker allowlist.** SPEC 19.1's read row gains `dumpsys batterystats --usage --proto` through
  this ADR: `BatteryStatsMode.USAGE_PROTO` (id 7) sends exactly those two flags. `--usage` returns
  before the branches that list every user's apps, so the in-app route needs no
  `INTERACT_ACROSS_USERS` for it.
- **Rejected:**
  - per-window eligibility for every window kind (the second draft): long windows almost never
    qualify, and their rows would grow for good;
  - a reset inside an interval (the third draft): it served only near-empty windows around a
    missed unplug, and use before an unseen reset would be missing from both sources while the
    window looked fully broken down.
