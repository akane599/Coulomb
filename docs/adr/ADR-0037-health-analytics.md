# ADR-0037: Capacity estimates and wear at session close

- Status: accepted
- Date: 2026-11-08
- Phase: P4

## Context

SPEC 17 lists "analytics for capacity" in P4, and protocol D (SPEC 16.6) compares Coulomb's capacity
with the system's and the OEM's after three qualifying charge sessions. The estimator itself
(`Capacity`, SPEC 6.3) and the wear model (`WearModel`, SPEC 6.4) exist from P2 and passed their
independent review there (ADR-0017). Nothing called them: no `capacity_estimate` or
`health_summary` row was written, and the session's `stressIndex` and `cycleEquivalents` stayed null
(`SessionSummary`).

A session's samples are compacted after it closes (ADR-0015), so whatever needs them runs at the
close, inside the snapshot's transaction.

## Decisions

1. **The capacity window of a charge or discharge session runs from its first level change to its
   last** (`SessionHealth.capacityWindow`). A level change is a sample whose level differs from the
   previous sample's, or from the session's start level for the first sample; only samples with a
   charge reading count. ΔSoC and ΔQ are taken between those two samples, and the temperatures over
   the samples between them. `Capacity.rejection` then applies SPEC 6.3's conditions.
   - R: a level change is sampled when `LEVEL_CHANGED` arrives (`TriggerPolicy`), just after the
     true level crossed a step, so ΔSoC between two changes is exact. The session's own ends can be
     up to a point off each, which is 5% of a 40-point window. A delay in delivery shifts both ends
     alike and mostly cancels in ΔQ; protocol D measures what is left.
2. **Cycle equivalents** are |ΔQ| / C_est, with the charge counter's ΔQ and the current estimate.
   Without a counted ΔQ or an estimate they are |ΔSoC| / 100.
   - R: a level change of the whole capacity is one cycle whatever the capacity is, so the fallback
     needs no estimate, and early sessions are not left out of the running sum.
3. **The stress index** is `WearModel`'s level stored as its ordinal (0 low, 1 moderate, 2 high) in
   the existing `stressIndex` column. It stays null without an estimate or a calibrated peak
   current, since the C-rate needs both. Discharge sessions count no time above 80%: the column is
   kept for charge sessions (`SessionSummary`).
   - R: no schema change for a three-level value; the KDoc names the coding.
4. **The running sum counts charge sessions.** `health_summary.cycleEquivalentsSum` is the sum over
   closed charge sessions (`SessionDao.cycleEquivalentsSum`).
   - R: charge sessions are fewer and densely sampled, since the phone is awake on the charger.
     Over weeks, charge and discharge throughput agree within the charging losses.
5. **The day's health summary is rewritten at every charge or discharge close**, keyed by the local
   epoch day: the estimate with its interval, the platform cycle count from the closing reading (S1
   `EXTRA_CYCLE_COUNT`) and the running sum.
   - Health %, the system's state of health and the OEM's value stay null here. They need the design
     capacity (SPEC 6.3: power profile, then sysfs, then user entry) and S23, whose sources come with
     the Health screen. The rows are rewritten with them then.
   - The wear of the closing session uses the estimate that already includes its own window.

## Consequences

- `SessionHealth` (pure) and `SnapshotWriter.health`/`healthSummary` do the work; `SessionDao` gains
  `cycleEquivalentsSum`. No schema change.
- Tests: `SessionHealthTest` covers the window, the rejections, the fallbacks and the stress levels;
  `CollectorEngineTest` follows a 20% to 100% charge through to its estimate, wear and summary.
- The window choice and the wear wiring get their independent estimator review before the P4 gate
  (AGENTS.md), together with the Health screen's sources.

## Amendments after the estimator review (2026-11-09)

- **A window's ends move the session's way** (§1): a level change counts as an end only when it goes
  up while charging or down while discharging, and the window must move that way overall. A step
  back, such as a load on the charger, has not just crossed a step the way the session runs.
- **Minutes at 80% or more and at 100% hold the level across gaps** up to 6 hours, as the habits'
  `timeAtOrAbove` does (ADR-0044 §2). Every level change is sampled, so a sparse gap while the phone
  sleeps on the charger is time at that level. Minutes above 38 °C keep the two-minute cap, since
  a temperature can change without a sample.
