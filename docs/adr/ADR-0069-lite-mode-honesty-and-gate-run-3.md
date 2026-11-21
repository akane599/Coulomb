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

## Run 4 (2026-11-22)

The fourth gate run found where §1 and §2 did not reach (`docs/design-gates/2026-11-22-*-run-4.md`).

7. **Every return is an app open, and an open screen learns its age.** `APP_OPENED` is posted at
   each `onStart`, not only at `onCreate`, so a return from recents, a widget or the tile takes
   Lite's one reading (the trigger policy already debounces it to a minute). Charging, which can
   stand in for Now, gets pull to refresh. The screens read the clock again once, at the moment the
   reading turns old, rather than only at resume; nothing ticks in between.
8. **An old reading draws nothing as now.** Now's state line drops the current, and the time left
   and the projection are left out; the note sits under the level and is worded by mode. In Lite,
   Now's live current says Lite does not follow it, and Charging's head is "Readings". The Level
   widget puts the time in the state's place and never drops it on one line, stepping the level
   down to title size if it must.

## Run 5 (2026-11-22)

9. **Charging and Now's verdict follow §8 too.** With an old reading Charging leaves out the
   times to target and to full and every projection, draws its power in ink rather than copper,
   heads its strip "Readings", and words its note by mode. Now's verdict names its end ("from the
   unplug to the last reading") instead of counting "ago" to a reading that is not now. The stale
   notes are polite live regions, the tile is not lit from an old reading, and a section caption
   wraps only when it would take more than half the row.

## Run 6 (2026-11-22)

10. **What TalkBack and the widget say follows §8 too.** Charging's sheets are built from the same
    stale-aware reading as its page, and its spoken summary leaves out "power now". Now and the
    Level widget share one old-reading verdict that names its end (`lastReadingLine`). The 2×1
    widget's time takes the longest form that fits beside the level, from `dayClock`; it goes under
    the level where two lines fit; and where nothing fits (the smallest size at large text) the
    level is drawn muted and TalkBack says the time, rather than cutting it. Reason: the honest
    choice that never truncates (SPEC 12.4); a tap opens Now, which shows the time.

## Run 7 (2026-11-22)

11. **The 2×1 widget's time keeps its day.** Run 7 was clean. Its minors are fixed: each form of
    the time is tried at hero size and then at title size before a shorter one, so a fuller time
    beats a larger number, and the dayless form is dropped. Where no form fits beside the level or
    under it (the smallest size, or the 2×1 from about 130% text) the muted level carries a small
    clock glyph, so colour is not the only cue, and TalkBack says the time.

## Run 8 (2026-11-22)

12. **The whole-app design gate passes.** Runs 7 and 8 found nothing above minor in either the
    audit or the critique (SPEC 11.12). Run 8's minors (the remaining day references on an old
    reading, the Last night widget's bare form, the clock glyph beside the mark) are recorded open
    in its gate files and are not built under the gate.

## Verified

Unit tests (sample counts, the 0.0 filter, the import threshold), every golden re-recorded and
looked at where it changed, and `tools/ci/check.sh all`. Not verified: Lite mode on a device (the
six-hour job, the cancelled Live Update), the tile and widget on One UI.
