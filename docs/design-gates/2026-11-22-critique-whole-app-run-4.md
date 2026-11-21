# Whole-app critique, run 4

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, Assessment A, one independent read-only review with a fresh
  brief in three slices; about 120 of 407 goldens opened, every string pair checked by script, Lite
  mode and run 3's fixes traced at `5b2d30c`. Nothing ran on a device.
- Result: **0 critical, 4 major.** This run does not count towards the two clean runs. All four
  are where ADR-0069 did not hold; the Drain, App detail and Timeline slice had nothing above minor.

## Scores before the fixes

27 / 40 (run 3: 27).

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | A warm return took no reading; Charging had no pull to refresh. | **Fixed** (audit run 4 #1). |
| 2 | Major | Now's hero presented an old reading as current. | **Fixed** (audit run 4 #3). |
| 3 | Major | In Lite, Now showed "Live current" with a one-point chart and "Updates while this screen is open", and Charging "Live readings", while Settings says Lite has no live current. | **Fixed** (ADR-0069 §8). In Lite, Now's section says Lite does not follow live current and Full mode does; Charging's head is "Readings". |
| 4 | Major | The Level widget dropped the time at 2×1. | **Fixed** (audit run 4 #4). |
| — | Minor | Turkish "Modellenmiş" and "canlı akım"; the automation note's condition; Now's note blaming Lite in Full mode. | **Fixed**. |
| — | Minor | Others (the Live Update until the next snapshot after switching to Lite, the two battery temperatures' sources, the widget's verdict fixed at draw time, two-reference empty text, old references selectable, "Open Halcyon", the night's two lines, the wake source's night names, nits). | Open, minor. |
