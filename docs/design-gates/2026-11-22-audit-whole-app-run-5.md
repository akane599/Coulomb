# Whole-app audit, run 5

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native), one independent read-only review with a fresh brief.
  About 160 of 412 goldens opened (every run 4 golden, all 35 widget goldens, each screen's
  variants), 26 string files compared by script, run 4's diff read in full, at `02a5ea3`. Nothing
  ran on a device.
- Result: **0 critical, 1 major.** This run does not count towards the two clean runs.

## Scores before the fixes

16 / 20: accessibility 3, performance 3, theming 4, platform conformance 3, adaptivity 3.

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | Charging still drew an old reading as now: copper power, times to target and full, the scale's projection, "Live readings" in Full mode; only a note was added. | **Fixed** (ADR-0069 §9). With an old reading Charging leaves out the times and every projection, draws the power in ink, heads the strip "Readings", and words its note by mode with "pull down to read it now". |
| — | Minor | Run 4's caption cap split short captions at 200% ("Since 6:31 / PM"). | **Fixed**. A caption that fits in half the row keeps its width on one line; only a longer one wraps within a third. |
| — | Minor | Now's verdict said "ago" to the old reading. | **Fixed** (critique run 5 #2). |
| — | Minor | The 2×1 widget's time had no day. | **Fixed**: "at 2:30 PM", with the day when not today. |
| — | Minor | The stale note appeared without a live region. | **Fixed**: polite live region on Now and Charging. |
| — | Nit | The tile stayed lit from an old charging reading. | **Fixed**: lit only from a fresh one. |
