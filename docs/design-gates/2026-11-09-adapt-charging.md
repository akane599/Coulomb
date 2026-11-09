# Adapt: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `adapt`, native, in the same agent run as `animate` and `optimize`.

**What holds.** One column, capped at 720 dp as Now's (ADR-0034). The hero and figure strips wrap
at 130% and 200%. Chart text is in sp. RTL and Turkish goldens exist.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | At an 85 or 90% target, the marker's value overlapped the 100% grid label | **Fixed.** A grid line within a label's height of the marker keeps its hairline and drops its value (`target-85`). |
| Nit | The bracket label sits on the 100% hairline at a 90% target | Accepted: the label stays legible. |
| Nit | A fifth target chip wraps alone, with uneven corners | Accepted: only with a target set outside the group. |
| Nit | The expanded width leaves empty space beside the column | Accepted (ADR-0034). A two-pane Charging layout is in BACKLOG.md. |

## Result

No critical finding. The major is fixed and verified in the goldens.
