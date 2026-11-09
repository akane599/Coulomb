# Adapt: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `adapt`, native, in the same agent run as `animate` and `optimize`.

**What holds.** The layout follows the available width and the font scale. At 200% text it falls
back to one column, capped at 840 dp. Pane bounds scale with text, figure strips wrap and chart
text is in sp. RTL goldens exist.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | After a session opens on an expanded window, the chosen row is off screen in the one-column pane | **Fixed.** The one-column list state is held above both layouts. When a session is chosen and its row is not visible, the list scrolls to it (`requestScrollToItem`). |
| Major | Medium windows of 600–711 dp get one column, though SPEC 10.10 asks for two | **Accepted, per ADR-0036 §23**, which already set this rule for Drain: two columns need 2 × 280 dp between the gutters. A narrower column wraps every row, and one column reads better. The `medium` golden now renders 620 dp beside the rail (one column). The new `medium-wide` renders 759 dp beside the rail (two columns). |
| Minor | The two-column row is not centred on a very wide window | **Fixed.** Centred. |
| Minor | Session detail and the full list have no width cap | **Fixed.** 840 dp, centred. |
| Minor | The design dialog does not scroll | **Fixed.** Its content scrolls. |
| Minor | Goldens show layouts the app can't produce | **Fixed.** `expanded` has no selection. `pane` and `pane-dark` are 368 dp, the list pane of a 1000 dp window. |

## Still on the emulator

Split insets with 3-button navigation, a landscape phone, and the shared element into the detail.

## Result

No critical finding. One major is fixed. The other follows a recorded decision (ADR-0036 §23).
