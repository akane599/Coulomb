# ADR-0043: Charge details and charger profiles

- Status: accepted
- Date: 2026-11-09
- Phase: P4

## Context

Health lists charge sessions with their curves and the chargers with a baseline (SPEC 10.8 items 6
and 7). Charging compares a session with its charger's baseline (SPEC 10.9), and R13 flags a slow
charge against it. SPEC 8 has `ChargeDetail` and `ChargerProfile`, but nothing wrote them.

Android exposes no charger identity. `EXTRA_MAX_CHARGING_CURRENT` and `_VOLTAGE` would help, but
the phone reports 0 for both (`fixtures/sm-s938b/S938BXXSDDZJ1/battery.txt`). Samples keep the plug
type, the calibrated current and the voltage.

## Decisions

1. **Figures at a charge's close** (`Charging.figures`, pure):
   - power per sample is current × voltage;
   - full speed is the median current below 50%, or over the first third of the samples when the
     charge started higher;
   - the taper (`ccToCvLevel`) is the lowest level from which every later sample stays below 70% of
     full speed, with at least three such samples and above the full-speed samples. A dip that
     recovers is not a taper;
   - `avgW` stores the median power before the taper (the whole session without one), the figure a
     charger's speed is judged by. A charge that tapers spends its end slow whatever the charger;
   - `peakW` is the highest sample; minutes above 38 °C as SessionSummary counts minutes;
   - fewer than 10 samples with a current give no power figures.
   - R: these are the charger's figures, not the battery's. The constants live beside the code
     and the independent estimator review checks them.
2. **A charger is a plug type and a full-speed power.** A session with power figures matches the
   profile of its plug type whose baseline is nearest, within 25%; otherwise it starts a new,
   unnamed profile. The baseline is the median `avgW` of the profile's latest 10 sessions; last seen
   moves to the session's end.
   - R: without an identity, power is the only stable signature. Two chargers of one plug type
     within 25% of each other merge; the owner can rename, and a mismatch costs a label, not a
     figure. Matching is per session and easy to replace.
3. `targetLevel` and `protectionMode` stay null until the charge alarm and S22 arrive.

## Consequences

- `Charging`, `ChargeSample`, `ChargeFigures`, `ChargerBaseline` in `:core:analytics`;
  `SnapshotWriter.chargeDetail`; three `SessionDao` queries. No schema change.
- Tests: `ChargingTest` (taper, start above 50%, recovering dip, too few samples, matching,
  baseline); `CollectorEngineTest` follows a charge to its detail and a new profile.
- Gets the independent estimator review with ADR-0037 and ADR-0042 before the P4 gate.

## Amendments after the estimator review (2026-11-09)

- **Full speed needs early samples.** A charge with fewer than 10 powered samples below 50% (a
  top-up started high) has a peak but no full speed and no taper. Its first third is already in
  the constant-voltage phase on many phones, so it would understate the charger.
- **A hold is not a taper.** When the level never rises after the low-current tail starts (a
  phone held at a charge limit), there is no taper level.
