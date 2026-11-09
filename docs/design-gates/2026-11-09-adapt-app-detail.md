# Adapt: App detail (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `adapt`, native variant, in the same subagent run as `animate`.

**What holds.** The layout switches on available space: panes need 2 × 360 dp × font scale, two
columns 2 × 280 dp × font scale, and 200% text falls back to one list. Gutters follow the window
class. The chart's text stays in sp. RTL and Arabic goldens exist.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Stacked rows hide the column headers | **Fixed.** Labelled values (`page-200`, `medium-rail`). |
| Major | In split view the list pane pads its End edge, the detail its Start edge | **Fixed.** `consumeWindowInsets(safeDrawing.only(End))` on the list, `only(Start)` on the detail. The 3-button landscape check waits for the emulator. |
| Minor | Mono tags break mid-word; Drain's fix is internal | **Fixed.** `breakable` and `tagLines` moved to `:core:ui`. |
| Minor | A 280 dp column is narrower than an inline three-column table needs | **Accepted.** Stacked rows now carry their labels, so the stacked table reads well at 712 to 775 dp (`medium-rail`). |
| Minor | The list pane's minimum isn't scaled by font | **Fixed.** The minimum and maximum take the font scale. |
| Minor | Crossing the column threshold resets scroll | **Accepted.** It happens only on a resize or fold, and keeping a position across two differently shaped lists has no clear right item. |
| Minor | The goldens misrepresent the layout | **Fixed.** `pane` at w840 in a 399 dp box, and `medium-rail` at 620 dp. **Deferred:** a landscape phone split, with the emulator run. |
| Nit | KDoc says a third of the width; the share is 40% | **Fixed.** |

## Result

No critical finding; both majors are fixed. The landscape split check is queued for the emulator.
