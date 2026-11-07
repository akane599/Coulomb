# Critique: Drain, Apps lens (`design-lab/drain.html`)

- Date: 2026-11-07, phase P3 (SPEC 11.12, 17)
- Method: dual-agent (A: a92acbe2b317526e6 · B: a51f1055ec66f02cc). One impeccable 4.7.1 `critique` pass over all seven screens (ADR-0022 §6).
  - **A** is the design review: Nielsen's heuristics and five personas (Jordan, Alex, Sam, Riley, Casey).
  - **B** is the detector and browser evidence.
- Scores are A's, on the lab before the fixes below. Each disposition describes the lab after the fixes.
- Decisions that change SPEC are in ADR-0023.

## Score: 26/36 (72%), Good

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | The scope is stated; there is no as-of time |
| 2 | Match with the real world | 3 | "Not in the power model" and "Reference vs reference" are jargon |
| 3 | User control and freedom | 3 | Lenses and expansion are reversible |
| 4 | Consistency and standards | 3 | Static component items were styled as filter chips |
| 5 | Error prevention | 3 | Ten unlabeled grey segments invite guessing |
| 6 | Recognition rather than recall | 3 | mAh and share side by side; the marks still need recall |
| 7 | Flexibility and efficiency | 3 | Lenses and expandable rows; no sort control |
| 8 | Aesthetic and minimalist design | 3 | The segmented bar adds ink that carries no information |
| 9 | Error recovery | n/a | No error or empty state is mocked |
| 10 | Help and documentation | 2 | No glossary for "power model"; the reconcile chart has no table |

**Design specificity.** Built for this product. The measured total is the Display numeral, modeled rows sit beneath it, and the unattributed gap is drawn (hatched), not footnoted.

**Cognitive load.**
- Fails minimal choices: the window selector had six options.
- Fails chunking: ten identical grey segments.

**Emotional journey.**
- The measured 1,204 mAh reassures, and the hatched gap reads as candour.
- The clipped window chips and the unreadable bar raise small doubts.

**Strengths.**
- The type hierarchy encodes honesty: Measured gets the Display role, and Modeled rows sit under it.
- The rows add up:
  - 402 + 168 + 96 + 88 + 74 + 41 + 36 + 157 = 1,062 mAh modeled;
  - plus 142 mAh unattributed = 1,204 mAh.
- The window note gives both the duration and the level span.

## Priority issues and dispositions

| Sev | Issue | Disposition |
|---|---|---|
| P2 | Window selector overload: six chips, three clipped or off screen | **Fixed.** Three presets plus More (a sheet with Last night, Custom range, Compare two periods); ADR-0023 §4. At 412 px the row ends at 380 px, inside the 16 px gutter; at 360 px More is cut at the edge, which hints that the row scrolls. |
| P2 | The reconciliation bar drew identities nobody could read | **Fixed.** Two parts only: "Modeled 1,062 mAh" and the hatched "Unattributed 142 mAh". A "View as table" semantics action is on every chart (ADR-0023 §5). |
| P3 | Share denominator unstated: rows sum to 88% | **Fixed.** The list caption reads "Shares are of the 1,204 mAh measured." |
| P3 | Static component items looked tappable | **Fixed.** They are now swatch-and-text items with no container. |
| P3 | Unattributed out of rank order, with no reason given | **Fixed.** It has its own group, "Measured, but no app accounts for it", with the detail "Outside Android's power model". The group heading is an `h2` (the detector's skipped-heading rule). |

**Persona red flags.**
- **Alex:** wants to sort by wakeups or background time. The Wakeups and Components lenses answer that; a sort control was not added.
- **Jordan:** tapped a component and nothing happened.
  - Now: no container, so nothing invites a tap.
- **Casey:** a clipped chip at the edge invited a swipe.
  - Now: the presets fit at 412 px.

**Minor observations.**
- The checked switch track was 1.39:1 against `bg`.
  - **Fixed:** switches are neutral ink, 16.6:1 in light mode (ADR-0023 §7).
- At 360 px, row details were cut off with an ellipsis ("mo…", "po…").
  - **Fixed:** names and details wrap (SPEC 11.3: reflow, never clip).

## Questions for the P3 direction review

- Is Unattributed an app row, or does it belong only in the reconciliation figure? It is now a separate group in the list.

## Detector and browser evidence (B)

- **At critique time:** static `[]`; rendered at 412×915, zero; overlay clean in light and dark.
- **After the fixes:**
  - Static `[]`, both entry points, once the group heading became an `h2`.
  - Rendered: zero at 412×915 and at 360×800.

Questions skipped: autonomous run (SPEC 0.6); questions folded into the P3 direction review.
