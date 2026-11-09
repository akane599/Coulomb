# Critique: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, design review only. The detector does not apply to a native
  screen. One fresh read-only agent read:
  - SPEC 1.1–1.2, 6, 7.3, 10.1, 10.9, 10.11, 11, 12.3 and 14.4;
  - PRODUCT.md, DESIGN.md, ADR-0026, ADR-0043 and ADR-0046;
  - the lab page, the sources and strings in both languages, and the goldens of the time.
- Scores are the agent's, on the screen before the fixes below. Decisions are in ADR-0046, "Gate
  amendments".

## Score: 28/40 (70%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | A copper "0.0 W" while current leaves the battery |
| 2 | Match with the real world | 3 | "Coulomb tells the charger" |
| 3 | User control and freedom | 3 | Target is one reversible tap |
| 4 | Consistency and standards | 2 | Phase and charger share unmarked; no bracket; Turkish suffixes |
| 5 | Error prevention | 3 | A stored target outside the group selects nothing |
| 6 | Recognition rather than recall | 3 | Every figure is labelled |
| 7 | Flexibility and efficiency | 3 | No scrubbing (as Health) |
| 8 | Aesthetic and minimalist design | 3 | The target time appears twice |
| 9 | Error recovery | 2 | One "times need" line for several causes |
| 10 | Help and documentation | 3 | Phases are glossary terms |

**Strengths.** Power is the one copper Display figure with its Derived mark. Times are ranges,
Estimated and rounded outward, and absent while held, drawing or full. The wave is still. The strip
reflows at 200%, and the curve table marks each column's source.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Phase and charger share carry no provenance | **Fixed.** Estimated marks after the phase term and after a matched charger's share. |
| Major | Drawing shows a copper "0.0 W" | **Fixed.** Net power is signed ("−1.3 W") and in ink, not copper (`Formats.signedWatts`); the reader no longer clamps. |
| Major | "Times show after three charges that went past this level" is wrong in several states | **Fixed.** One reason per case: below this charge's highest, too few charges that started below this level and reached the target, the same for full. |
| Major | Turkish suffixes after placeholders ("’te", "’ten", "’e") | **Fixed.** Rephrased so no suffix follows a placeholder: "%1$s, tahmini %2$s", "Başlangıç %1$s", "Seviye %1$s → %2$s". |
| Major | The signature bracket is missing | **Fixed.** The session chart draws the target marker, the copper cone and the bracket with the clock range, as the lab and Now do (`CurveProjection`). |
| Minor | Dashes, not "Calibrating" (SPEC 6.1) | **Fixed** in the hero and the Current tile. |
| Minor | The target note implies an alert exists | **Fixed.** "There is no alert at the target yet." |
| Minor | Learning copy is vague | **Fixed.** "…after 2 minutes of charging below 50%." |
| Minor | "Nothing was lost" is unverifiable | **Fixed.** "Your history stays on this phone." |
| Minor | Orphan words | **Accepted.** Body and caption already use `LineBreak.Paragraph`, Android's high-quality breaker; a balanced breaker for short notes is a design-system change for the whole-app polish pass. |
| Minor | A range breaks at its dash at 200% | **Accepted.** `format_range` is shared by every screen; the break falls after the dash, which still reads as a range. |
| Minor | The hero scale has no "View as table" action | **Fixed.** A custom action opens a table of the level and when the target and full are reached. |
| Minor | The hero digits roll every second | **Fixed.** No roll on Charging. |
| Minor | Fixtures produce impossible goldens | **Fixed.** Each state has its own curve, and the last power matches the hero. |
| Minor | A target outside the group selects nothing | **Fixed.** The stored target joins the group (`charging-target-70`). |
| Minor | Turkish "pilden çekiyor", "temel değerinin %96 kadarı" | **Fixed.** "pilden besleniyor", "temel değere göre %96" (no suffix after the figure). |
| Nit | The target time shows on the scale and in the times | **Accepted.** The lab shows both: the clock answers "when", the span "how long". |
| Nit | New-charger line joins a name to two sentences | **Fixed.** One resource: "Unnamed USB charger · new, saved when this charge ends". |
| Nit | The curve does not mirror in RTL | **Accepted.** As Health's chart; time runs left to right in the lab's Arabic review. |
| Nit | "Loading the charge" | **Fixed.** "Reading the charge", "Şarj okunuyor". |

## Result

No critical finding. All five majors are fixed and verified in the goldens. The accepted items are
recorded above with their reasons.
