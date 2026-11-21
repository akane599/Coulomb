# Whole-app audit, run 2

- Date: 2026-11-21, phase P5 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native), run as one independent read-only review with a fresh
  brief, split into five slices: the shell, navigation, notifications, shared components and all
  strings; Now, Charging and Health; Drain and App detail; Timeline and the Action Center;
  onboarding, Settings and the widgets. All 354 goldens were opened, and all 1,364 English strings
  were held against Turkish by script and read pair by pair. Every finding above minor was opened
  again at the commit under review. Nothing ran on a device.
- Result: **0 critical, 3 major.** This run does not count towards the two clean runs. The first
  of the three is critique run 2's first: run 1's own fix had hidden a control.

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
| 1 | Major | On Privileges, "Re-run probes" was not drawn once a matrix was stored. | **Fixed** (critique run 2, #1). |
| 2 | Major | Timeline at large text: once the labels stack, the 56 dp current panel held both end values inside it, so "−1.00 A" sat level with the zero line and "3.00 A" below the peak it names. | **Fixed** (ADR-0063 §6). The panel is at least three label lines tall at any text size, and each end value is centred on the line it names, with half a line kept for it past the panel. `timeline-day-200` shows it. |
| 3 | Major | Drain's Components "View as table" left out the "Not broken down" energy and took its shares of the smaller sum, so one component had two shares on one screen. | **Fixed** (ADR-0063 §7). The table uses the lens's total and closes with the same row. New golden `drain-components-s4-table`. |
| 4 | Minor | "The command failed and nothing was changed." is said where the engine cannot know (a reset with no output, a rollback that could not be read back). | **Open, minor.** The writes have never run on a device (`docs/questions.md`). |
| 5 | Minor | A taken-over Doze value is a log row with "Revert" and counts in "Revert all", though nothing is written. | **Open, minor.** |
| 6 | Minor | "Later" does nothing while the window ends at now; "Earlier" the same at the 30-day limit. | **Open, minor.** |
| 7 | Minor | The outlined-span explanation shows only with a wake rate and a window over 6 h; Top app's outlined stretches are never explained. | **Open, minor.** |
| 8 | Minor | Privileges: rows stored as "Available" stay in full ink after access is lost. | **Open, minor** (critique run 2, #20). |
| 9 | Minor | The Last night and Level widgets test their fit by height only; at 200% two lines cannot fit by width. From arithmetic, not rendered. | **Open, minor.** |
| 10 | Minor | Goldens show per-record screen-off notes that production never renders for partial and kernel records; App detail's wake tables name no counting base. | **Open, minor.** |
| 11 | Minor | Health's cross-check said Android and the manufacturer "report no health value", though Android's value is not read at all without the battery statistics grant. | **Fixed.** "Coulomb has no health value from Android or the manufacturer on this phone." |
| 12 | Minor | In a charge session's chart a grid line runs through a phase label. | **Open, minor.** |
| 13 | Minor | Health's capacity table lists the daily estimate only; the windows drawn as crosses are in no table. | **Open, minor.** |
| 14 | Nit | Compare prints "+7.7" beside rates 70 and 77; Alarms uses a bare "/h". | **Open, nit** (critique run 2, #31). |
| 15 | Nit | Turkish: the night chart's summary said the level "indi" on a charge; the no-worker note omits the Doze exception. | **Fixed** for the summary ("geldi"). **Open, nit:** the note. |
| 16 | Nit | Two "Charge readings" tables run in opposite orders. | **Open, nit.** |
| 17 | Nit | The Health widget's sample and Health's change margin were not rounded to 10 mAh. | **Fixed** (critique run 2, #16, #29). |
| 18 | Nit | The tile kept its last spoken level when there was no reading. | **Fixed.** |
| 19 | Nit | Settings' "Allow notifications" keeps its label after a refusal, when it opens the system page. | **Open, nit.** |

Nothing recorded as open in run 1 was raised again at a higher severity.

## Not verifiable from source and goldens

TalkBack traversal and what is spoken; motion, the Now and Charging crossfade, predictive back,
haptics, frame rate while panning, the wheel on DeX; notification taps, channel names after a
language change, the Live Update's promoted look, the tile in a real panel, widgets on a launcher;
the bottom bar's Turkish label at 200% on windows narrower than 412 dp; dialogs with the keyboard
up; how often the breakdown leaves a remainder; whether the Coulomb-only cross-check occurs on a
device.
