# Whole-app audit, run 7

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native), one independent read-only review with a fresh brief;
  about 105 of 417 goldens opened (all new ones, every screen's variants, 18 widget goldens), 1,646
  strings compared by script, run 6's diff read in full, at `d76503f`. Nothing ran on a device.
- Result: **0 critical, 0 major. Clean**: the first of two consecutive clean runs.

## Scores

16 / 20: accessibility 3, performance 3, theming 4, platform conformance 3, adaptivity 3.

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Minor | The 2×1 widget dropped the day at the default size: it tried every form at hero size before title size. | **Fixed** (ADR-0069 §11): a fuller time beats a larger number, and the dayless form is gone. |
| 2 | Minor | The muted, time-less level reached the smallest size and the 2×1 at 200%, with grey as the only cue. | **Fixed** (ADR-0069 §11): a small clock glyph beside the level; the ADR states the real scope. |
| 3 | Minor | The scale table had no target row without a time to it. | **Fixed**: the target is always a row, unmarked (a setting). |
| — | Nit | Lite's widget says "to the last reading" beside a fresh one. | Open, nit: in Lite the widget is not redrawn, so the reading's end is the honest anchor (ADR-0069 §2). |
| — | Nit | `charging-slowing-tr-200`, `drain-days`, `settings-lite` fixture details. | Open, nit: fixtures only. |
