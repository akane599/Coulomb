# ADR-0069: Lite mode says how old a reading is, and design gate run 3's fixes

- Status: accepted
- Date: 2026-11-22
- Phase: P6 (SPEC 6.7, 7.1, 11.12, 12)
- Builds on: ADR-0064 §5 (Lite mode), ADR-0068 (parity views), ADR-0027 (one-line decisions)

## Context

The third whole-app design gate (`docs/design-gates/2026-11-22-*-run-3.md`) found that Lite mode,
which stops the collector's service, left every live surface showing the last Full-mode reading
as if it were now, and that Lite's alarms and Live Update said one thing and did another. It also
found provenance marks that claimed more than the source gives, and copy that the reference fix
`e6a0206` had made untrue.

## Decisions

1. **Every snapshot stores its reading as a sample.** The screens read the battery from the latest
   sample, and in Lite only snapshots run (an app open, a refresh, the six-hour job). One more row
   a snapshot in Full mode is negligible beside the sampler's rate. Reason: the cheapest change that
   makes Lite's screens current whenever Coulomb has read anything.
2. **A reading older than 15 minutes shows its time** (`BatteryNow.STALE_MS`). Now and Charging
   print "Last reading at …"; Now drops the live current, whose chart would label an old point
   "now"; the tile's detail and the Level widget's state line carry the time. The widget is drawn
   only at a snapshot, so in Lite it always carries the time. Full mode reads every 2 to 60 s with
   the screen on, so a quarter-hour gap means the collector is not running. The screens judge age
   against the clock read at each resume; the goldens pass no clock and are unchanged.
3. **Lite gives up the two alarms and the Live Update in code, as ADR-0064 §5 already said in
   words.** With no service, nothing would end a charge's update at the unplug, and an alarm would
   ring at a snapshot hours late. Settings' Alerts and Charging's target note say so; the choices
   are kept for Full mode. The daily digest still posts with the first snapshot after its time.
4. **Energy consumers are marked Modeled under their own head.** Android does not say which
   consumers a maker measures; marking them Modeled claims nothing the source does not give. Rails
   keep the Measured mark.
5. **The temperatures sheet leaves out an exact 0.0 °C beside warmer readings.** The S25 Ultra's
   HAL lists its sub-battery at 0.0 next to the main battery (fixture `thermal.txt`), which would
   read as a second, false battery temperature. A real 0.0 °C next to warm sensors is not a state a
   working phone reaches.
6. The rest of the run's majors are copy or one-line fixes recorded in the gate files: discharge
   levels Modeled, one empty-state reason per case for old references, the window chip wraps, the
   Action Center's and onboarding's no-tap claims name what an allowed app may repeat, and an
   imported temperature threshold applies only while the alarm is on.

## Verified

Unit tests (sample counts, the 0.0 filter, the import threshold), every golden re-recorded and
looked at where it changed, and `tools/ci/check.sh all`. Not verified: Lite mode on a device (the
six-hour job, the cancelled Live Update), the tile and widget on One UI.
