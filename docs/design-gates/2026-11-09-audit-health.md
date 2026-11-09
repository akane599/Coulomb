# Audit: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit`, native. One fresh read-only agent read the code and 25 goldens
  (no Gradle run, no device).
- Score (before the fixes below): **15/20, Good**:
  - accessibility 2;
  - performance 3;
  - theming 4;
  - platform conformance 3;
  - adaptivity 3.

## What holds

- **Accessibility.** Tokens only: no raw colours, no fixed `sp`. Targets are at least 48 dp; rows
  are 64 dp. Each chart clears its semantics and sets a summary plus "View as table". Titles,
  subheads and the session head are headings. Rename reads "Rename <name>". The draw-in becomes a
  cross-fade under reduce motion.
- **Font scaling.** 130% and 200% reflow without clipping. Tables stack with labelled values.
- **RTL and theming.** The `ar` and `harden-rtl` goldens mirror correctly. Light, dark and true black
  read clearly.
- **Performance.** Keys and content types on every lazy item; charts do not draw while idle.
- **Adaptivity.** Two columns at 700 and 1000 dp. One column in the ≤ 480 dp list pane. Panes
  consume their insets; the session pane has a pane title.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Session rows are unlabelled buttons; name and value focus apart | **Fixed.** Inner merges apply only beside an action. A row without one is a single labelled button. A charger row merges name and figure, with Rename apart. Tested by `rowsReadAsUnits`. |
| Major | At 200% the hero's mark is squeezed off and "±" splits from its number | **Fixed.** The hero is a FlowRow on the baseline. "± N" plus its mark move as one unit, with a no-break space. Seen in `harden-200` with a 6-digit figure. |
| Minor | Uneven labels without a design capacity | **Fixed.** See the critique: steps to 50,000, ends first, `ScaleTest`. |
| Minor | `Sections` rebuilt on every recomposition | **Fixed.** Remembered with its inputs. |
| Minor | "See all" leaves an open session in the back stack | **Fixed.** `AppNavigator.openList` closes a detail on top first (`AppNavigatorTest`). |
| Minor | The session pane title doesn't say which session | **Accepted.** The pane's first heading is the session's date, read right after the title. A dynamic pane title would need the session read before the pane opens. |
| Minor | No golden of the real expanded state | **Fixed.** `health-pane` and `health-pane-dark`: the 368 dp list pane of a 1000 dp window, with a selection. `health-expanded` now has none. |
| Minor | Trend summary doesn't say which way capacity moved | **Fixed.** "14 qualifying windows from Aug 10 to Nov 9; the estimate went from 4,680 mAh to 4,610 mAh", as plurals. The estimate is compared from the third window, as the line is drawn. |
| Minor | Dialogs reject input silently | **Fixed.** Rename cuts pastes to 40 with a counter. The design field keeps digits only. It shows "too large" at once and "too small" on Save or from four digits. |
| Minor | No maximum width on one column | **Fixed** on Health (840 dp). The session pane is at most the detail pane's width. |
| Nit | `ar` goldens can't validate copy alignment | **Accepted.** No Arabic strings ship; the goldens check mirroring, not copy. |

## Device checks (queued for the TalkBack walk)

- How "± 70" and the en dash placeholder are spoken in EN and TR.
- Focus when the Rename and Design dialogs open, and whether the error is announced.
- Whether swapping the session beside the list announces the pane change.
- Whether "See all" needs context.

## Result

No critical finding. Both majors are fixed and verified.
