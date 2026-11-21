# Whole-app critique, run 6

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, Assessment A, one independent read-only review with a fresh
  brief; about 55 of 412 goldens opened (every golden run 5 changed, Now and Charging variants, 18
  widget goldens), 1,682 strings compared by script, run 5's diff read in full, at `c7dc7e2`.
  Nothing ran on a device.
- Result: **0 critical, 3 major.** This run does not count towards the two clean runs.

## Scores before the fixes

28 / 40 (run 5: 27).

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | Charging's sheets and spoken summary still projected from an old reading. | **Fixed** (audit run 6 #1). |
| 2 | Major (re-raised) | The widget's verdict still counted "ago" beside "As of", disagreeing with Now. | **Fixed** (ADR-0069 §10). Now and the widget share one line that names its end (`lastReadingLine` in core/ui). |
| 3 | Major | The 2×1 widget cut its time. | **Fixed** (audit run 6 #2). |
| — | Minor | The 2×1 day judged at draw time; goldens that depended on the day they were recorded. | **Fixed**: `dayClock`, which needs no clock. |
| — | Minor | Flat old-reading verdict; target note; Turkish "pili açtığında" and "saat". | **Fixed**. |
| — | Nit | The wave stayed copper. | **Fixed**. |
| — | Nit | "In this window" wraps beside "Since unplugged" at 200%. | Open, nit: readable, as the review says. |
| — | Nit | `settings-lite` shows °F beside °C alarm choices (a test global). | Open, nit: golden artifact. |
