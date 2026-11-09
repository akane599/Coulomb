# ADR-0046: The Charging screen and its estimates

- Status: accepted (decided under ADR-0027 without a second opinion: a screen and its estimators, no schema or public API change; the estimators had their independent review, below)
- Date: 2026-11-09
- Phase: P4

## Context

SPEC 10.9 lists what Charging shows:
- power in W first;
- current, voltage and temperature;
- time to the target and to full, as ranges;
- the phase in plain words;
- the charger and how it compares with its baseline;
- the session's curve at 1 Hz;
- a target selector that links to the charge alarm.

The spec does not say:
- how Charging takes Now's place;
- how the phase is read;
- how the times are estimated;
- which charger a charge in progress is on;
- what the target selector does before the alarm exists (SPEC 12.3 is P5 work).

## Decision

1. **In place of Now.** While the latest sample says plugged in, the Now destination shows Charging,
   titled "Charging", and Now again on unplug. The navigation item keeps its name. While the screen
   is resumed it sets `VisibleScreen.CHARGING`, so the collector samples every second (SPEC 7.3).
   On pause or when it leaves, it sets the rate of any Coulomb screen back.
2. **Phase** (`ChargePhases`, ADR-0043's taper rule):
   - FULL at 100%;
   - HOLDING when the level has not moved for 10 minutes and the current is at most 10% of full
     speed, or at most 100 mA without one;
   - DRAWING when the median of the last five currents is zero or below;
   - SLOWING at 50% or above when the last five currents are all under 70% of full speed (the
     median current below 50%). A charge with no full speed uses the usual taper level of past
     charges on the same charger, or else on the same plug;
   - FULL_SPEED otherwise;
   - no phase without a calibrated current, or with nothing to tell full speed from slowing.
     Coulomb never says "full speed" by default.
3. **Time to a level** (`ChargeTimes`):
   - Past charges that ran from here to the target say how long the rest takes, because the
     shape above the taper is the battery's. Each one is scaled by this charge's pace against its
     own over the last steps both crossed. That window is at least two steps, at most ten, and at
     least 5 minutes long. The scale is held to 0.5–2.
   - The charger now plugged in is used with three charges or more, else any charger. At most
     the latest ten are used.
   - A past charge counts only if it started below the compared levels, reached the target and
     did not pause on the way (a step of 30 minutes or more and 4 times the median step).
   - The range is the middle half of the scaled times from five charges up, else their spread,
     widened to at least ±10% of the middle.
   - The time spent at the current level comes off, but never more than the step usually takes.
   - There is no time while the level is held, drawing or below this charge's highest so far.
   - With too few charges, the screen says so.
   - Times are Estimated, rounded outward as time left is (SPEC 6.8).
4. **Target.** The 80/85/90/100% group (ADR-0026) sets the target tick and the time to target. It is
   stored as the SPEC 14.4 charge target: Proto field 4, where 0 means the suggested 80%, held to
   50–100%. The alert switch arrives with the alarm in P5 (SPEC 12.3). A switch that did nothing
   would mislead, so until then the screen says the alert comes with notifications.
5. **The charger so far.** This charge's plug type and full speed, as `Charging.figures` takes them
   for a closed charge, are matched by `Charging.match`, the same rule. The match waits for two
   minutes of readings below 50%, so a charger still ramping up is not taken for a slower one.
   - Before that the screen says it is still learning.
   - At 50% or above without those readings, it says it cannot tell.
   - A match shows the charger's name and this charge's full speed as a share of its baseline.
   - No match says the charger is new, and it is saved when the charge ends.
6. **Curve.** The session's level and power come from its raw samples, read incrementally (one
   short query per sample). They are drawn by Health's `ChargeCurveChart`, which thins them to
   about 800 points. Above it, a still copper wave runs to the level on a 0–100% scale, with the
   target tick and the time the target is reached (DESIGN.md: the bracket beside the target
   tick). The wave does not move (Still Screen Rule).
7. **Reading.** Past charges' level lines come from a new `SampleDao.levelSteps` query: level
   changes only, from raw samples or minute aggregates. They are read again only when a charge
   closes, and each closed line is read once.

## Estimator review (2026-11-09)

An independent read-only review found two major and five minor issues. All are fixed and tested:
- **Major:** the time shrank to "0–1 min" while the level held or fell. Fix: no time then, and the
  time at the level is capped at its usual step.
- **Major:** a charge started above 50% always read "full speed". Fix: no phase, or the same
  plug's usual taper level.
- **Minor:**
  - drawing from the battery while plugged in read as charging, now DRAWING;
  - a past charge's part-done first step counted, now a strict start;
  - minute-stamped history swung the pace, now a 5-minute minimum;
  - overnight holds stretched the time to full, now excluded;
  - the live match used another plug-type rule and named a charger during its ramp-up, now the
    closed charge's rule and a 2-minute wait.

Noted, not changed:
- samples stored before calibration have no current on this screen, while a closed charge
  converts them;
- `LiveChargeCache` assumes samples arrive in time order. The collector's single writer stores
  them that way.

## Consequences

- One query per second while Charging is visible and the phone is plugged in, which SPEC 7.3
  allows while charging.
- Time estimates appear only after three comparable charges, so a new install shows none at first.
- Reverting Decision 1 means Now shows while plugged in. Reverting Decision 4 needs no migration:
  Proto field 4 stays reserved.
