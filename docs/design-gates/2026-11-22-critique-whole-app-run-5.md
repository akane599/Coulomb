# Whole-app critique, run 5

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, Assessment A, one independent read-only review with a fresh
  brief; about 80 of 412 goldens opened (all five new ones and every screen's variants), 1,679
  strings compared by script, run 4's diff and the Lite paths traced at `02a5ea3`. Nothing ran on a
  device.
- Result: **0 critical, 2 major.** This run does not count towards the two clean runs.

## Scores before the fixes

27 / 40 (runs 3 and 4: 27).

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | An old reading still drew "now" on Charging; the golden showed "80% at 7:06–7:10 PM" at 7:20 PM with 52%. | **Fixed** (audit run 5 #1). |
| 2 | Major | Now's verdict counted "ago" to the old reading, so "Since unplugged 5 h 12 min ago" was false an hour later; Drain's note for the same span was honest. | **Fixed** (ADR-0069 §9). With an old reading the sentence names its end: "From the unplug to the last reading, 5 h 12 min, the battery fell 26%." |
| — | Minor | The 2×1 widget's bare clock; Charging's note not worded by mode; short captions wrapping; no live region; "six" vs "6 hours". | **Fixed**. |
| — | Minor | One fact in four wordings across surfaces ("Last reading at", "as of", "at", "As of"). | Open, minor: screens say "Last reading at", surfaces outside the app "as of"/"at" by the room they have. |
