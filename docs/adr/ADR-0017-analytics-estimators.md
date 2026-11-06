# ADR-0017: Analytics estimators

- Status: accepted (fills gaps in SPEC 6 and 7.8; no SPEC change)
- Date: 2026-11-06
- Phase: P2

## Context

SPEC 6 fixes the methods: calibration by sign and scale, drain rate as ΔQ / Δt, capacity as a
weighted median with a bootstrap interval, wear, and attribution windows split at resets. It
leaves some details open. Each choice below is the more accurate, cheaper or easier-to-revert
option (SPEC 0.6). All of it lives in `:core:analytics` as pure functions with unit tests.

## Decision

### Calibration (SPEC 6.1, `Calibration`)

**Sign evidence.** A reading counts as evidence only in two cases:
- unplugged with the screen on, where current must be negative;
- plugged with the level above its lowest value since plugging in, where current must be positive.

A raw reading of zero says nothing. The sign is chosen once the last 20 pieces of evidence agree.
One disagreeing reading among them holds it back, so heavy use while charging cannot flip it.

**Charge counter.** The counter is checked for three faults:
- *Unsupported:* no reading has a counter.
- *Stuck:* one value for more than 15 minutes while the level changes.
- *Coarse:* its step is above 1 mAh. The step is the greatest common divisor of the counter's
  changes, not their size, since real drain between readings makes the changes large while the
  divisor stays the counter's resolution.

With fewer than five changes, the counter is "unknown".

**Scale.** Two ways, depending on the counter:
- *Reliable counter, window of at least 10 minutes, non-zero change:* the scale whose integrated
  current (trapezoids) comes closest to the counter's change is chosen. Quality is 1 minus the
  relative error.
- *Otherwise:* the scale is the only one whose mean power falls between 0.05 W and 25 W, at
  quality 0.4. One of the two scales is off by a factor of 1000, so the power check alone tells
  them apart.

Either way, the chosen scale must pass the power check, or calibration stays incomplete.

### Drain rate (SPEC 6.2, `Drain`)

**ΔQ** comes from one of three sources, in order:
- the charge counter, when it is reliable (Measured);
- integrated current, when the current is calibrated (Estimated);
- the level alone (Estimated, no current).

**Rule.** A window under 15 minutes and under 20 mAh shows "Collecting". Ends in different boots
give no rate (SPEC 7.8). The UI shows the level-based %/h until calibration is complete.

### Capacity (SPEC 6.3, `Capacity`)

- **Rejection order.** A window is rejected for the first failing reason, checked in this order:
  ΔSoC, temperature, reboot, counter, then zero charge.
- **Temperature factor.** It uses the window's mean temperature. Its range is already bounded by
  the 15–40 °C rule.
- **Recency.** It is applied when estimates are combined, from each one's age then, so stored
  weights never go stale.
- **Weighted median.** The smallest value whose cumulative weight reaches half the total.
- **Bootstrap.** Resamples draw windows with replacement and take their weighted median. The
  interval is the nearest-rank 2.5% and 97.5% of the 1,000 medians.
- **Seed.** The resampling is seeded from the inputs, so the same windows always give the same
  interval. A number does not change between two openings of the screen.
- **Display.** The ±1% of design floor is applied before rounding to 10 mAh.

### Wear (SPEC 6.4, `WearModel`)

- **Factors.** Each factor (time at or above 80%, peak temperature, C-rate) is Low, Moderate or
  High against `stress-thresholds.properties`.
- **Index.** The session's index is its worst factor, and that factor is named as the cause.
- **C-rate** is the session's peak current magnitude over the capacity estimate.
- **Starting values** are 60 and 240 minutes, 38 and 43 °C, and 0.7 and 1.2 C. They are
  conservative, and the file changes without a code change.

### Attribution windows (SPEC 6.5, `Attribution`)

**Reset.** A different stats epoch at the two ends means a reset inside the window.
- *With a snapshot taken before the reset (same epoch as the start):* the window is that part plus
  the end's counters, which start from zero.
- *Without one:* only the part after the reset is known. The window is marked partial, a lower
  bound.

**Counters that go down.** A counter that goes down without a reset, as when an app is removed,
counts as zero and is counted in `wentDown`. It never counts as negative use.

**Reconciliation.** It needs both measured and modeled totals. The "scale to measured" view is
Derived and needs both to be positive.

### Sessions (SPEC 7.2, 7.8, `SessionRules`)

The collector does not apply each broadcast as an edge. On every look it compares the open
sessions with the device state, and closes them in this order of reasons:
1. sessions from an earlier boot, as *reboot*;
2. every session, when monitoring stops;
3. a second session of a kind, and a change found that nobody watched (Coulomb's process was not
   running), as *data gap*;
4. the rest that the state no longer wants, as *state changed*.

Then it opens each wanted kind that is not open. A repeated, missed or reordered broadcast cannot
leave two sessions of a kind open.

## Consequences

- The collector feeds these functions with stored samples and snapshots. It stores their results
  with provenance and quality.
- The independent review of the estimators and attribution diffs (AGENTS.md) ran before the P2
  gate; its findings are in the amendment below.

## Amendment 2026-11-06: independent review

A fresh read-only review checked the module against SPEC 1, 6, 7.2 and 7.8, and reproduced
cases with a probe against the built classes. It found nine defects and five doubts. All nine are
fixed, each with a test.

**Calibration**
- **Sign while plugged.** "The level is above its lowest value since plugging in" let draining
  while plugged (a game on a weak charger) count as charging, and could flip the sign.
  - Requiring only that the last level change was upward still failed: at 5 s sampling, about 120
    readings pass before the level drops.
  - Now a plugged reading counts only when its level step began with a rise and ended with
    another rise. Evidence is confirmed by the next level change, never assumed.
- **Counter derived from the level.** Its steps (48,124 and 48,123 µAh) share no divisor, so it
  passed as reliable. Now a counter that never moves during at least 10 minutes of unplugged,
  screen-on use at one level is coarse, whatever its divisor. While unplugged with the screen on,
  a real counter moves far more than 1 mAh in that time.
- **Unsupported markers.** `Long.MIN_VALUE` and values of 0 or less count as no counter (S2).

**Drain rate**
- **Provenance.** The rate now has two provenances:
  - the mean current's is Measured only from the counter;
  - the shown %/h is always Estimated, because it divides by the capacity estimate or comes
    from the fuel gauge's level.
- **Quality** (SPEC 6.7) is the charge source (counter 1.0, integrated current 0.8, level 0.5)
  times how finely the change was resolved, which is 1 minus one step over the change.

**Capacity**
- **Seed.** The bootstrap seed included the windows' ages, so the interval moved between two
  screen openings. It is now seeded from capacities and static weights only.
- **Floor after rounding.** The ±1% floor was applied before rounding, which allowed ±30 where 37
  was the minimum. The bounds are now rounded outward from the rounded estimate, low down and
  high up.
- **No design capacity.** The floor is now ±1% of the estimate, never a zero-width interval.
- **Direction.** A window whose charge or level moves against its direction is rejected
  (`DIRECTION`).
- **Quality** is the window count (full at 3) times 1 minus the half-width over 10% of the
  estimate. One window is low confidence.
- **Ties.** The weighted median takes the midpoint when exactly half the weight lies on each side,
  instead of the lower value, which biased few windows low.

**Attribution**
- **Clock changes are not resets.** The platform moves `start_clock_time_ms` with every
  wall-clock change (`noteCurrentTimeChangedLocked` → `adjustStartClockTime`, android-16.0.0_r4
  L4657-4667, android-17.0.0_r1 L4196-4206), so exact equality reported false resets.
- **How a reset is found.** It now comes from `total_realtime_ms`, which restarts at a reset
  (`initTimes`, android-17.0.0_r1 L10983-10990):
  - within one boot, a reset happened when the counted time grew more than 60 s less than
    elapsed time;
  - across a reboot, when it grew less than the new boot's uptime minus 5 minutes, since the
    platform starts counting when system_server starts.
  - Without it, the start time decides. Within one boot it is measured against the clock offset
    (start minus (wall − elapsed) stays fixed through clock changes); across a reboot any change
    counts.
- **Partial windows.** A window spans the chain of stored snapshots, so two resets add up. A step
  across a reset is partial unless the reset came within 2 minutes of the earlier snapshot. Before,
  any snapshot before the reset made the window complete, however much earlier it was.
- **Removed keys** count in `wentDown`.

**Sessions**
- Coulomb's process can be dead during an unplug and replug that history shows. The session
  rules now take the kinds whose state changed unseen, and end those sessions with a data gap.
  Before, one charge session spanned two charges and could pass as "one continuous charge".

**Doubts left to the collector**
- Pass null, never the platform's markers, for a missing counter. The rules above also guard
  against this.
- Fill `changedUnseen` from the battery history for the span the process was dead.
