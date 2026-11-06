# ADR-0018: The collector

- Status: accepted (fills gaps in SPEC 7; adds one `Snapshot` column)
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
| S7 history | After S5 | 2 s; 15 s for a catch-up read (cursor 0, or after a failed read) |

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

### Light snapshots

- A level change (SPEC 7.2) is stored as a sample only (ADR-0015).
- Boot, package replaced and package changed store a light snapshot row. They are a few a day,
  and windows and references need a row to point at.

### Blobs

A blob is the gzip of the raw S5 proto. It is kept for a heavy snapshot that:
- starts or ends a charge or discharge session (plug triggers, boot);
- is the first heavy snapshot of a local day;
- is taken for a user reference;
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

**Resets.** The platform resets its statistics when the device is unplugged at a high level
(`shouldResetOnUnplugLocked`, BatteryStatsImpl android-16.0.0_r4 L13530-13550, android-17.0.0_r1
L13127-13147), so most days have a reset. A window's counters are taken over a chain: its two ends
and every snapshot in between whose counters are still stored, split at each reset
(`Attribution.window`, ADR-0017). Counters are pruned two hours back, so the collector pins the
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
- **The service.** Full mode runs a `specialUse` foreground service (subtype `battery_monitoring`).
  It may start from `BOOT_COMPLETED`, since `specialUse` is on the default allowlist
  (`FGS_BOOT_COMPLETED_RESTRICTIONS`, ActiveServices android-17.0.0_r1 L389-404, android-16.0.0_r4
  L368-383; `DEFAULT_FGS_BOOT_COMPLETED_ALLOWLIST`, ActivityManagerConstants android-17.0.0_r1
  L199-205, android-16.0.0_r4 L195-201).
- **Receivers.** Screen, plug and battery receivers are registered at runtime in the service.
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

## Consequences

- The 72 h run measures:
  - per-source cost;
  - heavy snapshot rate;
  - blob count;
  - history rows a day.

  These replace ADR-0015's assumed rates before the schema freeze.
- Adding a deferred source needs its parser, its capability probe and a row here.
- One `Snapshot` column is added before the freeze. The identity hash in `SchemaTest` changes once.
