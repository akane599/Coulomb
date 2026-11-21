# Per-screen gates: Timeline and Night Report

- Date: 2026-11-21, phase P5 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, `audit`, `harden`, `clarify`, `adapt`, `animate`, `optimize`
  and `polish`, run as one independent read-only review with all eight references open. The detector
  does not apply to a native screen. The reviewer read PRODUCT.md, DESIGN.md, SPEC 10.6, 10.7, 10.10,
  10.11 and 11, ADR-0056, the sources of `:feature:timeline`, the shared chart and page components,
  both string files and 30 goldens. One file holds the run; the command of each finding is in its row.

## Scores before the fixes

| Gate | Score |
|---|---|
| critique (Nielsen, of 40) | 25 |
| audit (native dimensions, of 20) | 14 |

No Critical. Four Major.

## Findings and dispositions

| # | Sev | Command | Finding | Disposition |
|---|---|---|---|---|
| 1 | Major | critique, harden | No locked or partial state: without the worker the page only said "no battery history". | **Fixed.** The view model reads the privilege state. Access lost shows SPEC 4.5's block where the history would be; Basic mode shows why nothing is added here and "Open Privileges". |
| 2 | Major | harden | "Not read yet" and "read failed" looked like "no history"; a later failed read was silent. | **Fixed.** A read that is due or running says "Reading this range…"; a failed one says so above the chart that was read before, with "Try again". "No history" is said only when the read is done. |
| 3 | Major | critique | The night page's Timeline carried one Measured mark over a Modeled level, and sections without figures carried marks. | **Fixed.** No header mark on the chart; a line under it names both sources. "What kept it awake" has its mark only with records. |
| 4 | Major | adapt, audit | The window could be moved only with two touch fingers; TalkBack and a mouse had no way. | **Fixed.** "Earlier" and "Later" buttons beside the readout move half a window; the chart offers the same two as accessibility actions; a wheel or trackpad scrolls the window sideways and zooms with Ctrl held. Per-track event lists for TalkBack are not built: "View as table" gives each track's totals (finding 26). |
| 5 | Minor | critique | The current panel is blank where nothing was sampled, with no reason. | **Fixed** in words: the caption says when current is sampled. The scale still comes from the whole read, so a drag does not rescale under the finger. |
| 6 | Minor | clarify | An outlined span meant three things; the caption explained one. | **Fixed.** The caption names the tracks it applies to and says what outlined means on Doze. |
| 7 | Minor | critique | The chosen range went to weight 600. | **Fixed.** One weight (DESIGN.md). |
| 8 | Minor | animate | Toolbar buttons move by a few pixels when the selection changes. | **Accepted.** Reserving the check's width in every button puts each unchosen label off centre; the move is under 6 px on a centred bar. |
| 9 | Minor | harden | At 200% the chosen range can sit at the scroll edge. | **Open, minor.** For the whole-app pass. |
| 10 | Minor | harden | Identifiers broke inside a word; a single tag skipped `breakable`. | **Fixed.** |
| 11 | Minor | audit | Band tap targets are 22 dp tall. | **Accepted** (ADR-0056 amendment): a tap picks by band and a mis-tap's sheet names its track; 48 dp bands would double the chart's height. |
| 12 | Minor | critique, harden | Event details gave times without a day; a scrub left the old pick in the panel. | **Fixed.** A day line when the event is not today's; a scrub clears the pick. Picked marks are not tinted. |
| 13 | Minor | critique | A top-app stretch is headed "Top app" with the package in mono; a run has no link. | **Accepted** (ADR-0056 §12). |
| 14 | Minor | critique, animate | The skeleton is not the page's shape; the page swapped in without the fade. | **Fixed** for the fade. The skeleton stays the shared one. |
| 15 | Minor | clarify | "8.0 times an hour" from two wakeups. | **Fixed.** A count under an hour of history, a whole "about N" over more. |
| 16 | Minor | critique | The readout dropped the current's sign. | **Fixed.** Signed, as the axis is. |
| 17 | Minor | clarify | The night list's value column mixes a rate, a level change and a dash. | **Accepted** (ADR-0056 §4); the detail page says which it is. |
| 18 | Minor | critique | No glossary terms on the night page. | **Open, minor.** Moving Drain's definitions to `:core:ui` is for the whole-app pass. |
| 19 | Minor | clarify | Turkish "aralık" named three things. | **Partly fixed.** The density span is "çerçeveli bölüm"; the column name stays. |
| 20 | Minor | clarify | The no-history state said one thing twice and offered no next step. | **Fixed.** One sentence that names "Now"; no table button over nothing. |
| 21 | Minor | adapt | On expanded the caption runs long and the toolbar can cover the night row. | **Open, minor.** Expanded polish is P6's (SPEC 17). |
| 22 | Minor | harden | RTL: the night head's date sits opposite its figure; the draw-in sweeps from the physical left. | **Open, minor.** For the whole-app pass. |
| 23 | Minor | harden | A read landing mid-gesture could end the gesture. | **Fixed.** The gesture is keyed on the direction only and reads the layout as it is. |
| 24 | Minor | optimize | Per-frame copying while panning. | **Open, minor.** From source only; measured in the 60 fps check on the phone, which is deferred (ADR-0053). |
| 25 | Minor | optimize | `series()` on the main thread; night tables sorted in composition. | **Open, minor**, with 24. |
| 26 | Minor | audit | "View as table" omits level, current, signal and temperature. | **Open, minor.** For the whole-app pass. |
| 27 | Minor | adapt | Goldens short of SPEC 11.12's matrix. | **Partly fixed.** New: locked, reading, read failed. True black arrives with the Theme setting in P6. |
| 28 | Nit | critique | Gridlines at 0, 50 and 100, a comment says quarters. | **Accepted.** |
| 29 | Nit | animate | The scrub haptic is `SegmentFrequentTick`, SPEC 11.6 names `CLOCK_TICK`. | **Accepted** (ADR-0056 amendment): the segment tick is the platform's constant for a slider crossing a step. |
| 30 | Nit | audit | Range radios without `selectableGroup()`. | **Fixed.** |
| 31 | Nit | clarify | "woke 1 times" in the night chart's summary. | **Open, nit.** |
| 32 | Nit | critique | Fixtures show states that cannot occur. | **Open, nit.** |

## Result

All four Majors are fixed. Of 23 minors, 10 are fixed, two partly, four accepted with a recorded reason
and seven stay open as minor, each named for the whole-app pass, P6's expanded polish or the deferred
60 fps check. 24 goldens re-recorded in `:feature:timeline` (3 new).

Not checked here: motion and frame rate on the phone, a mouse on DeX, TalkBack traversal. The wheel
and the accessibility actions are from source; no device has run them.
