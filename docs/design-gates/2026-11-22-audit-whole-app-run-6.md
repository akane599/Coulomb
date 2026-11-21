# Whole-app audit, run 6

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native), one independent read-only review with a fresh brief;
  about 75 of 412 goldens opened (every golden run 5 changed, each screen's variants, 14 widget
  goldens), every string file compared by script, run 5's diff read in full, at `c7dc7e2`. Nothing
  ran on a device.
- Result: **0 critical, 2 major.** This run does not count towards the two clean runs.

## Scores before the fixes

16 / 20: accessibility 3, performance 3, theming 4, platform conformance 3, adaptivity 3.

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | Charging with an old reading: the scale's TalkBack table still listed "Reaches 80%" and "Reaches full", and the curve's spoken summary said "power now". | **Fixed** (ADR-0069 §10). Both sheets are built from the same stale-aware reading as the page; the summary leaves out "power now". Golden `charging-scale-table-stale`. |
| 2 | Major | The 2×1 widget cut its time ("at Nov 8, 2:30 P"), and its day was judged at draw time. | **Fixed** (ADR-0069 §10). The time takes the longest form that fits ("As of Sun 2:30 PM", "Sun 2:30 PM", "2:30 PM") from `dayClock`, goes under the level where two lines fit, and where nothing fits the level is drawn muted and TalkBack says the time. Goldens at minimum size, 130%, 200% and Turkish. |
| — | Minor | The copper wave stayed copper with the power in ink; the target note promised a time. | **Fixed**: the wave is ink when stale; the note drops the time clause. |
| — | Minor | Now's old-reading verdict had no flat form ("fell 0%"). | **Fixed**: "the level did not change". |
| — | Minor | Turkish "saat Paz 14:30". | **Fixed**: the widget uses the stacked form's wording. |
| — | Nit | `charging-slowing-tr-200` showed a time to 80% at 81%. | **Fixed** in the fixture. |
