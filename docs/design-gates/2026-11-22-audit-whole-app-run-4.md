# Whole-app audit, run 4

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native), one independent read-only review with a fresh brief in
  five slices. About 203 of 407 goldens opened (every changed one), all 1,412 translatable English
  strings held against Turkish by script, the ADR-0069 pairs read one by one, and run 3's fixes
  checked one by one at `5b2d30c`. Nothing ran on a device.
- Result: **0 critical, 5 major.** This run does not count towards the two clean runs. Four of the
  five are where run 3's Lite fix did not reach.

## Scores before the fixes

16 / 20: accessibility 3, performance 3, theming 4, platform conformance 3, adaptivity 3.

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | A return to a live activity took no reading: `APP_OPENED` was posted only in `onCreate`, the only snapshot in Lite; Charging, which can stand in for Now, had no pull to refresh. | **Fixed** (ADR-0069 §7). `APP_OPENED` is posted at every `onStart` (the trigger policy keeps it to one a minute); Charging has pull to refresh. |
| 2 | Major | Age was judged only at resume, so a screen left open never showed it. | **Fixed** (ADR-0069 §7). The clock is read again at the moment the reading turns old, once; nothing ticks in between. |
| 3 | Major | With an old reading, Now's hero still showed the current, the time left and a projection whose "now" was the old reading's time, and the note sat below them. | **Fixed** (ADR-0069 §8). The state line drops the current and its Measured mark, the time left and the projection are left out, and the note sits under the level, worded by mode. Goldens `now-discharging-stale`, `now-discharging-lite`. |
| 4 | Major | At 2×1 the Level widget dropped the time line when it did not fit. | **Fixed** (ADR-0069 §8). The time takes the state's place and is never dropped on one line (the level steps down to title size for it); stacked, "As of" with the day. Goldens `widget-level-as-of`, `widget-level-as-of-tall`. |
| 5 | Major | A section caption (the span's name) crushed the wake page's "In this window" title. | **Fixed**. A caption wraps within a third of the row and the title keeps the rest (`Page.kt`); `wake-reason-200` shows a wrapped caption. |
| — | Minor | Stale note always blamed Lite; automation note's condition on all calls; onboarding missing "returns it to stock"; Turkish "varsayılana"; import offer's threshold unconditional; Timeline's sampling caption. | **Fixed**. |
| — | Minor | Others (Lite alarm rows' own notes and the permission ask, Data's button labels, `dayClock` days, the wrapped chip's ripple and radius, "X to Y" with "to" in a name, old references selectable, the issue line hiding the empty reason, R01's day, "at least N" on a partial night, `System.currentTimeMillis` in `EmptyWindow`). | Open, minor. |
