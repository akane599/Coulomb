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
- The independent review of the estimators and attribution diffs (AGENTS.md) runs before the P2
  gate; its findings amend this ADR.
