# Whole-app audit, run 3

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native), run as one independent read-only review with a fresh
  brief in five slices (shell and strings; Now, Charging, Health; Drain; App detail, Timeline,
  Night; onboarding, Settings, console, Data, Action Center). About 310 of 406 goldens were opened,
  including every one new or changed since run 2; 1,638 English strings were held against Turkish
  by script and the 297 new pairs read one by one. Every finding above minor was reopened at
  `110769e`. Nothing ran on a device.
- Result: **0 critical, 9 major.** This run does not count towards the two clean runs.

## Scores before the fixes

| Dimension | Score (of 4) |
|---|---|
| Accessibility | 3 |
| Performance | 3 |
| Appearance and theming | 4 |
| Platform conformance | 3 |
| Adaptivity | 3 |
| **Total** | **16 / 20** |

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| M1 | Major | One Measured mark over rails and consumers. | **Fixed** (critique run 3 #4). |
| M2 | Major | Discharge level change marked Measured. | **Fixed** (critique run 3 #3). |
| M3 | Major | On the S25 Ultra the temperatures sheet would list the unread sub-battery sensor at 0.0 °C as a second "Battery", and type 5 (PA) had no name. | **Fixed** (ADR-0069 §5). An exact 0.0 beside warmer readings is left out, with a test built on the phone's own lines; types 5 and 9 are named. |
| M4 | Major | The window chip clipped a long reference span's name and could squeeze out its chevron. | **Fixed**. The chip's label wraps between words and the icon keeps its room; golden `drain-between-long-references-200`. |
| M5 | Major | The references sheet described the behaviour `e6a0206` removed. | **Fixed** (critique run 3 #5). |
| M6 | Major | In Lite, a snapshot could post an ongoing, promoted charging update that nothing ended at the unplug. | **Fixed** (ADR-0069 §3). Lite cancels it and posts none. |
| M7 | Major | Lite's alarms: four sources, three answers, and the code still checked them at Lite snapshots. | **Fixed** (critique run 3 #2). |
| M8 | Major | "Nothing runs without your tap" and onboarding's "never changes a setting without your tap" stood beside an automation Doze write. | **Fixed**. Both say what an allowed app may repeat; Settings' automation note names the return to stock and that it needs the Action Center on. |
| M9 | Major | Importing a bundle's settings turned the temperature alarm on, though the offer says alarms stay as they are. | **Fixed**. A file's threshold applies only while the alarm is on here; test added. |
| — | Minor | Turkish "Ayrıcalıklar"; "Seyrek". | **Fixed**. |
| — | Minor | Lite note: "Nothing runs in the background" beside the 6-hour job. | **Fixed**: "No service runs in the background". |
| — | Minor | Night not-collected goldens no longer reached the lines they guard. | **Fixed**: taller frames. |
| — | Minor | "120 Hz" breakable. | **Fixed**: a no-break space. |
| — | Minor | Others (live regions on the new sheets, the session search's per-keystroke formatting and IME key, group labels on choice groups, DataTableSheet rows without column names, Mono for identifiers, split times at 200%, a stale "not collected" line on App detail for an app with no row, the overflow menu's surface, the keyboard gap, wide notes, missing goldens of the new views). | Open, minor. Listed for the next pass; none misleads or stops a user. |

## Recorded, not re-raised

As the run's own list: run 1 #4–#7, #12, #18–#22, #24, #26–#28; run 2 #4, #5, #8–#10, #12–#14, #19;
the per-screen gates' recorded minors; ADR-0034, 0036, 0058, 0064, 0066 and 0068 decisions; the open
questions in `docs/questions.md`.
