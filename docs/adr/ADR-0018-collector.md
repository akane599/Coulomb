# ADR-0018: The collector

- Status: accepted (fills gaps in SPEC 7; adds one `Snapshot` column and one trigger; widens one
  SPEC 7.5 timeout)
- Date: 2026-11-06
- Phase: P2

## Context

SPEC 7 defines triggers, samplers, the actor pipeline and retention. ADR-0014 and ADR-0015 left
the collector these jobs:
- placing history events on Coulomb's clock;
- choosing the history kinds it stores;
- comparing each read's restated state with the stored one;
- deciding what a snapshot blob holds;
- the retention cuts.

The phone fixture shows that some sources are large:
- `jobscheduler` is 2.3 MB of text;
- `alarm` is 289 KB;
- the proto dump is 273 KB.

A heavy snapshot has a 300 ms CPU target, and self-drain outranks the functional spec (SPEC 0.2).

## Decision

### Placing history events

History times are `MonotonicClock` milliseconds. Within one run of system_server they are
`timeshift + elapsedRealtime`, with the timeshift fixed (`MonotonicClock`, identical in
android-16.0.0_r4 and android-17.0.0_r1, L60-88). No dump prints the current monotonic time.

**Where the pairings come from.** Every `TIME` (`WALL_CLOCK`) and `RESET:TIME` record pairs a
monotonic time with the wall time at the same instant:
- `START` is followed at once by `TIME` (`continueRecordingHistory`, BatteryStatsHistory L1076-1087
  in both tags);
- every history fragment starts with `TIME` (`maybeFlushBufferAndWriteHistoryItem` calls
  `startRecordingHistory`, L1755-1797);
- every clock change writes one (`recordCurrentTimeChange`, L1163-1174).

**The rule.** The collector reads `wall − elapsed` right after each history read. For the latest
pairing in the current boot, `offset = monotonic − (wall − (wall − elapsed)now)`. It is accepted
only if:
- it places the read's last record (`NEXT − 1`) no later than now, plus 1 s;
- it places the boot's first record no earlier than elapsed 0;
- no `ACTION_TIME_CHANGED` arrived during the read.

Once accepted, the offset is stored on the boot row and kept: it is constant for the boot.

**Segments.** A read is cut into segments at each `START`:
- the part before the first `START` belongs to the boot the previous read ended in, and uses
  that boot's stored offset;
- the part after the last `START` belongs to the current boot;
- segments in between belong to boots Coulomb did not run in. They are not stored, and a
  `CURSOR_SKIPPED` gap covers them;
- on the very first read (cursor 0), everything before the current boot's `START` is not stored.
  History is never imported (ADR-0015).

**No offset yet.** While the current boot has no accepted offset, the read is not stored and the
cursor does not move. The next fragment's `TIME` record supplies a pairing within hours.

**Soft reboots.** A system_server restart without a kernel reboot keeps `bootSeq`, but writes
`START` and a new timeshift. The new segment's own `TIME` record gives the new offset, which
replaces the boot's stored offset. Events stored before it keep their placement.

### Stored history kinds

Five kinds are not stored. On the phone fixture they are 41% of the changes:

| Kind | Share | Why it can go |
| --- | --- | --- |
| `CELLULAR_HIGH_TX_POWER` | 20.3% | No rule or track uses it; signal bins and radio time are counters |
| `STATE_CHANGE` | 11.5% | Per-UID sensor on/off events; per-window sensor time comes from S5 (R09) |
| `COLLECT_EXTERNAL_STATS` | 3.6% | Internal stats collection, nothing to show |
| `DISPLAY_STATE` | 2.9% | The screen and Doze state flags carry it |
| `BATTERY_CHARGE` | 3.1% | Samples and snapshots hold the same counter |

Battery level, status, plug, health, temperature and voltage stay. A screen-off discharge has no
samples, and capacity windows need temperature "throughout" (SPEC 6.3).

### Restated state

Each incremental read's first record restates the whole state (ADR-0014). The collector keeps the
state at the end of the previous read: the baseline plus later changes, for state flags and for
level, status, plug and Doze mode. If the new baseline differs, a change was merged into the
previous read's last record. The collector then stores a `STATE_MISMATCH` gap from that record
to the new read's first record, instead of a transition at the first record.

This state lives in memory. The first read after Coulomb's process starts is not compared.

### Heavy snapshot sources (P2)

| Source | When | Timeout |
| --- | --- | --- |
| S1, S2 (light data) | Every snapshot | — |
| S5 proto (S6 `-c` if it fails) | Every heavy snapshot | 2 s |
| S7 history | After S5 | What the budget left, at most 2 s; 15 s for a catch-up read |

- **Only what P2 consumes.** S5 gives the per-UID modeled mAh, wakelocks, alarm and job counts,
  network bytes and the device timers; S7 gives the event timeline. Every other source is read
  first by the phase that uses it, with its cost measured then:
  - S10 `alarm` and S13 `jobscheduler` (2.3 MB of text on the phone) to P5, with the alarm and job
    detail views. Their per-UID counts are already in S5.
  - S19 `thermalservice` and S21 `battery` to P4, with health. S1 already gives temperature,
    health and plug state.
  - S4, S14–S17 and S22 to P4: attribution and health.
  - S11 and S12 to P5, with the Doze and live wakelock views.
  - S8 and S9 stay as probes: missing on both test devices, or root only.
- **In-app first.** Collector dumps ask `:core:privileged` for `RoutePreference.IN_APP_FIRST`:
  where the app's own grants allow a dump, it runs in the app's process, and the worker is bound
  only when that read is unavailable. A worker bind costs 474 ms on the phone (P1 measurement),
  more than the whole CPU target. This adds a parameter, with the P1 behaviour as its default,
  to `PrivilegedAccess.dumpBatteryStats` and `dumpService`.
- **Budget.** The whole heavy snapshot is capped at 4 s (SPEC 7.5). A source not run because of
  it is marked `skipped` in `sourceStatusJson`.
- **The catch-up read.** When there is no cursor yet, or the last read failed, the read may have
  the whole history buffer to get through: 2.3 MB of text in the phone fixture. A 2 s cap could
  then fail every time, and Coulomb would never store a timeline. Measurement honesty outranks the
  functional spec (SPEC 0.2), so this read gets 15 s and is the one exception to the 2 s and 4 s
  caps of SPEC 7.5. To keep self-drain bounded it runs at most once per 30 minutes; in between, a
  failed read retries with the normal budget. The 72 h run measures how long it takes.
- **Raw copies.** The parser's input is copied for the blob, up to 8 MB; past that the copy is
  dropped, not cut.
- **Measured later.** The 72 h run measures each source's cost.

### Snapshot rows

- `Snapshot` gains `statsRealtimeMs` (`total_realtime_ms`). Without it, a reset cannot be told
  from a clock change (ADR-0017 amendment).
- **Counter rows from S5:**
  - `uid_power`: modeled per-UID mAh, as `component = "all"` and `processState = "any"`;
  - `wake_stat`: partial wakelocks, kernel wakelocks and wakeup reasons;
  - `alarm_stat`, `job_stat`, `net_stat`;
  - `uid_stat` keys: CPU, foreground, process-state times, sensors, GNSS, Wi-Fi scans,
    Bluetooth scans, audio, video, camera, flashlight;
  - `device_stat` keys: screen, Doze, radio, signal and brightness bins, discharge totals.
- **Not reported is null or absent, never 0** (the parser rule).

### Trigger policy

The policy is pure (`TriggerPolicy`); the engine supplies what it knows.
- **Level change** (SPEC 7.2): a sample only (ADR-0015). If the stored sessions no longer match
  the device, as after a missed plug or screen broadcast, it becomes a heavy snapshot (light
  without dump access), since sessions start and end at snapshots.
- **Package added, replaced or removed:** a light snapshot row. They are a few a day, and the UID
  package table needs a row's time.
- **App open, refresh and unlock:** heavy, but skipped if a heavy snapshot ran in the last 60 s.
- **Everything else is heavy:** screen on and off, plug, the 30-minute screen-on snapshot, boot,
  Coulomb's own update, and `SERVICE_STARTED`. That last trigger is new: the service started, or
  the system restarted it after killing the process, with no other trigger.
- **First trigger of a process:** always heavy, so the history read shows what happened while
  Coulomb was not running.
- **No dump access yet** (privileges not bound after boot, or revoked): a wanted heavy snapshot is
  stored as a light row and is owed. The owed snapshot runs when access returns, or on the next
  trigger once dumps work, and keeps the original trigger.

### Blobs

A blob is the gzip of the raw S5 proto. It is kept for a heavy snapshot that:
- starts or ends a charge or discharge session (plug triggers, boot);
- is the first heavy snapshot of a local day;
- is taken for a user reference (P4, with the screens that set them);
- or has a source whose parse was incomplete. For those, the incomplete source's raw text is
  kept too, for diagnosis.

That is 5 to 10 a day, at about 340 KB each. Blobs go after 30 days or when the folder passes
128 MB, oldest first. A blob's job is to let windows that are kept for good be recomputed. Keeping
one for every heavy snapshot would be about 1 GB a month.

### Windows

Windows run between heavy snapshots (ADR-0015 kinds):
- **Consecutive:** from the previous heavy snapshot to the new one, at every heavy snapshot.
- **Session:** from a session's start snapshot to its end snapshot, at close. Screen sessions
  store no detail (ADR-0015).
- **Day:** from the first heavy snapshot of a local day (the `TODAY` reference) to the first one
  of the next day.
- **Reference** windows are computed on request in P4, when the screens that show them exist.

**Measured drain.** The charge counter's change when calibration found it reliable and both ends
are in one boot; else the level change times the capacity (Estimated, `LEVEL_DELTA`); else null.
Across a reboot the counter is not trusted: some fuel gauges re-estimate it at boot.

The capacity is S5's `power_use_summary.battery_capacity_mah` (BatteryStats android-17.0.0_r1
L8786, android-16.0.0_r4 L8754, through `populateBatterySessionInfo`, BatteryUsageStatsProvider
android-17.0.0_r1 L296-303, android-16.0.0_r4 L297-304). It is the platform's estimate, not the
design capacity: the power profile's value at first, replaced on battery by `charge_full / 1000`
or, failing that, by charge ÷ level above 25% (BatteryStatsImpl android-17.0.0_r1 L11098-11099 and
L13319-13327, android-16.0.0_r4 L11506-11507 and L13722-13730). The design capacity, wear and the
learned capacity come in P4.

**Resets.** The platform resets its statistics when the device is unplugged at a high level
(`shouldResetOnUnplugLocked`, BatteryStatsImpl android-16.0.0_r4 L13530-13550, android-17.0.0_r1
L13127-13147), so most days have a reset. A window's counters are taken over a chain, split at
each reset (`Attribution.window`, ADR-0017). The chain is the window's two ends plus, for each
reset between them, the stored snapshots on either side of it. Between two resets counters only
grow, so other snapshots would add nothing. Counters are pruned two hours back, so the collector pins the
last heavy snapshot before the latest reset with a hidden automatic reference, `BEFORE_RESET`.
A window that spans one reset then keeps its earlier part. Two resets in one window leave it a
lower bound, marked partial.

A reset after a long charge marks the window partial when the last snapshot before it is more
than 2 minutes older. Most platform counters only advance on battery, so this is likely too
cautious. The 72 h run checks whether any counter moves between two plugged-in snapshots before
the rule is relaxed.

**Detail rows.** UIDs with at least 0.1 mAh, at most 30, get their own rows; the rest are summed
into `OTHER_UID` per component and process state. Wakelock rows need at least 1 s, at most 30
per kind. Stat rows follow the same UIDs. Timers and counts are Measured; modeled energy is
Modeled.

**Quality.** As the drain rate's (ADR-0017): source quality times resolution, halved for a
partial window.

**Day windows.** A local day starts at its first heavy snapshot, not at midnight: no alarm wakes
the device at midnight (SPEC 7.4). When a heavy snapshot falls on a later local date than the
`TODAY` reference, it closes the day window and becomes the new `TODAY`.

### Sessions

`SessionRules` (ADR-0015) decides opens and closes. At close the collector fills:
- start and end level, charge and time;
- the drain source (counter or level) and the drained mAh;
- time-weighted average current and the peak current: the highest charge current for a charge
  session, the largest discharge current otherwise;
- minimum, mean and maximum temperature;
- for a charge session, the minutes at or above 80% and at 100%. A sample stands for at most
  2 minutes, so a gap in the samples does not count as time at that level;
- quality: source quality times resolution, halved unless the session closed on a state change
  (a reboot or process gap leaves the end uncertain).

A session closed by a reboot ends where the old boot was last seen: its last sample or snapshot,
whichever is later. Its end snapshot is that boot's last heavy snapshot, so it still gets a window.
Wear and capacity estimates are left null until P4, and deep Doze time until P5.

### Calibration

After each snapshot commits, the tracker reads the last 60 minutes of samples and runs ADR-0017's
calibration: charge counter trust, current sign and current scale. The device profile is updated
when the result improves or the counter verdict changes. Once quality reaches 0.9 and the counter
verdict is known, the tracker stops running.

### UID packages

The UID-to-package table is updated from S5. All UIDs are written with the first heavy snapshot of
a local day; later snapshots write only UIDs whose package list changed.

### Samplers and wakelocks

- **Rates as in SPEC 7.3.** There is no sampler while unplugged with the screen off.
- **Plugged in.** Each sample takes a partial wakelock with a 200 ms timeout, released in
  `finally`.
- **No alarms.** Delays are coroutine delays on the monotonic clock. While the CPU sleeps they do
  not fire, so the device is never woken. A plugged-in, screen-off sample happens when the device
  is awake anyway, and on each `BATTERY_CHANGED`.
- **Screen-on heavy snapshot.** The 30-minute one is a delay that runs only while the screen is
  on.

### Actor and service

- **One consumer.** Triggers go into one `Channel`. Its single consumer applies the trigger policy:
  - snapshot kind;
  - a 60 s debounce for app open and refresh;
  - a pending heavy snapshot after boot until privileges bind.
- **One transaction per snapshot.** Each snapshot writes, in one Room transaction:
  - history events and gaps, then the snapshot with their mark;
  - counters;
  - session changes (`SessionRules`);
  - windows;
  - moved automatic references;
  - the boot's history offset.
- **After commit.** The calibration tracker runs, then retention when due.
- **The service.** Full mode runs a `specialUse` foreground service (subtype `battery_monitoring`)
  with a minimum-importance notification. It is sticky. It may start from the background on:
  - `BOOT_COMPLETED`, since `specialUse` is on the default allowlist
    (`FGS_BOOT_COMPLETED_RESTRICTIONS`, ActiveServices android-17.0.0_r1 L389-404,
    android-16.0.0_r4 L368-383; `DEFAULT_FGS_BOOT_COMPLETED_ALLOWLIST`, ActivityManagerConstants
    android-17.0.0_r1 L199-205, android-16.0.0_r4 L195-201);
  - `MY_PACKAGE_REPLACED`, which is sent with a temporary foreground-service allowlist
    (BroadcastHelper android-17.0.0_r1 L714-720 and L288-298, android-16.0.0_r4 L734-740 and
    L288-298).
- **The boot receiver is not exported.** Both broadcasts come from system_server, whose own pid
  passes the component check (BroadcastSkipPolicy android-17.0.0_r1 L136-145, android-16.0.0_r4
  L133-142; `ActivityManagerService.checkComponentPermission` android-17.0.0_r1 L6898-6902,
  android-16.0.0_r4 L6235-6239).
- **Receivers.** Screen, unlock, plug, battery, clock-change and package receivers are registered
  at runtime in the service, not exported.
- **Until onboarding exists (P3),** opening the app starts the service. `adb shell am start` on the
  main activity is also how a headless run starts it.
- **Lite mode** (WorkManager, 6 h) arrives with Settings in P4. Until then Full is the only mode.

### Retention

Retention runs once a day while charging, on the next heavy snapshot after 24 h, in the actor. It
turns "N days ago" into a `(bootSeq, elapsedNs)` key from the boot table.
- Samples: 14 days.
- Aggregates: 180 days.
- History ring: 30 days.
- Window detail, by kind (ADR-0015).
- Counters: 2 hours back, except the latest heavy snapshot, references (`BEFORE_RESET` among
  them) and the starts of open sessions (ADR-0015).
- Blobs: as above.

`VACUUM` runs only while charging, when the database has freed more than 10% of its pages.

## Validation

On the API 37 emulator (2026-11-06), with the P1 grants in place:
- Installing an update started the service through `MY_PACKAGE_REPLACED`, and a reboot started it
  through `BOOT_COMPLETED`. The system logged both allowances.
- Heavy snapshots took 75–250 ms of wall time, with S5 and S7 read in the app's process.
- History was stored with no gaps: 421 events at first, 674 after the reboot. Each boot's offset
  was accepted from its first read.
- Unplug, screen off, level steps, screen on and replug were simulated with `dumpsys battery`, and
  the battery was reset afterwards. Sessions, windows and references came out as designed. A
  stats reset at a full unplug was seen (`total_realtime_ms` 1869306 → 510), and `BEFORE_RESET`
  was set.
- The emulator's charge counter is a fixed 10000 µAh. Calibration rejected it, and drain fell back
  to `LEVEL_DELTA` with the emulator's 3000 mAh capacity.
- The sessions open at the reboot closed with `REBOOT` at the old boot's last sample.

Two defects found there are fixed: awake screen-off time was null rather than 0 when the counter
did not move, and a session closed by a reboot had no end snapshot.

## Consequences

- The 72 h run measures:
  - per-source cost;
  - heavy snapshot rate;
  - blob count;
  - history rows a day.

  These replace ADR-0015's assumed rates before the schema freeze.
- Adding a deferred source needs its parser, its capability probe and a row here.
- One `Snapshot` column is added before the freeze. The identity hash in `SchemaTest` changes once.
- `SnapshotTrigger` gains `SERVICE_STARTED`. Triggers are stored as text, so the schema is unchanged.
