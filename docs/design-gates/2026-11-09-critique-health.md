# Critique: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, design review only. The detector does not apply to a native
  screen. One fresh read-only agent read the following, then all 25 goldens of the time:
  - SPEC 1.1–1.2, 6.3–6.8, 10.1, 10.8, 10.10–10.11 and 11;
  - PRODUCT.md, DESIGN.md, ADR-0026, ADR-0037 and ADR-0042–0045;
  - the lab page with its 11-07 critique, and the App detail critique;
  - the sources and strings in both languages.
- Scores are the agent's, on the screen before the fixes below. Decisions are in ADR-0044,
  "Amendments after the estimator review", and in this record.

## Score: 27/40 (68%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | First-week lines blame the phone before anything was read |
| 2 | Match with the real world | 3 | "(SPEC 6.4)" in a definition; a bare OEM key |
| 3 | User control and freedom | 3 | An entered design capacity cannot be undone |
| 4 | Consistency and standards | 2 | Provenance differs for the same kind of figure; Headline on compact |
| 5 | Error prevention | 3 | The design dialog does not say what it replaces |
| 6 | Recognition rather than recall | 3 | Every figure is labelled |
| 7 | Flexibility and efficiency | 3 | No chart can be scrubbed |
| 8 | Aesthetic and minimalist design | 3 | Tall charger rows; strip figures not aligned |
| 9 | Error recovery | 2 | Partial states give a cause without a fix |
| 10 | Help and documentation | 2 | Only "Wear stress" is a glossary term |

**Strengths.** The copper bracket on a graduated scale, the estimate as a range, the three-source
cross-check with no score or badge, crosses for windows and neutral share bars follow DESIGN.md's
Graduated Scale direction. The change line says whether a change is real. Missing figures show a
reason, never a 0. Both evidence charts have a summary and a visible "View as table".

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Wear figures carry the wrong provenance: Derived cycles, no mark on stress, a Modeled header over mixed rows, Measured vs Derived averages | **Fixed.** Cycle equivalents and stress are Estimated in figure, subhead and rows. The sessions header mark is gone; each row marks its gain Modeled and its cycles and stress Estimated. Averages are Derived. A single highest temperature is Measured. |
| Major | The disagreement note names Coulomb when Coulomb's row is absent | **Fixed.** The note is chosen by the sources present (`health_differ_coulomb`, `health_differ`, `health_agree_same`). "Coulomb's health % needs the design capacity" shows whenever there is an estimate without health %. |
| Major | Empty and partial states claim causes not established | **Fixed.** Neutral lines: no cycle count "yet", no values "yet", with every cause that can apply (`health_no_cycles`, `health_no_values`). A link to Privileges waits for that screen (P5). |
| Major | Overriding the design capacity is one-way | **Fixed.** The dialog states the current source and value. An owner's entry offers "Use the reported value", which clears it and reads the power profile or sysfs again (ADR-0044 amendment 5). |
| Minor | "(SPEC 6.4)" in user copy | **Fixed** in EN and TR; "peak temperature". |
| Minor | OEM key not in Mono | **Partly fixed.** The key breaks at word parts (`breakable`). It stays in Caption under the source name: a note under a row is Caption (App detail's rule), and Mono there would read as a second figure. |
| Minor | Axes have no units | **Accepted.** The hero's range label and the trend's design line carry "mAh" in the figure and caption directly above. A unit on every tick would crowd a 412 dp axis. The table view has units. |
| Minor | ± reverses in RTL | **Fixed.** LTR isolate around "± N". |
| Minor | Scale breaks its mark budget without a design capacity | **Fixed.** Steps to 50,000. End labels are placed first, interior ones dropped on collision. `ScaleTest` covers 11,200–13,480. |
| Minor | Numbers split from their units | **Fixed.** Non-breaking spaces in `health_plus_minus`, the cycles plurals, the inline stress words and the separator. |
| Minor | Charger rows too tall (Rename under the figure) | **Accepted.** Rename under the figure keeps the name and figure on one line at 412 dp and 200% text. An end-slot button would push the figure into the name. The row stays one unit with its share bar. |
| Minor | Session screen has no Display figure; Headline title | **Fixed.** The date is a Title heading. The gain is the Display figure with its Modeled mark. |
| Minor | Strip figures don't line up when a heading wraps | **Accepted for now.** `CoulombFigure` is shared by Now, App detail and Health. Aligning across columns is a design-system change for the whole-app polish pass, not one screen. |
| Minor | No chart can be scrubbed | **Accepted.** SPEC 11.8 says how scrubbing looks. Only the Timeline is required to scrub (SPEC 10). Exact values are one tap away in "View as table". The shared crosshair helper comes with the Timeline, and Health's charts can adopt it then. |
| Nit | "design capacity" twice in the first week | **Accepted.** One sentence gives the value, the next its source. Merging them loses the source sentence other states share. |
| Nit | Fixtures produce impossible goldens | **Fixed.** The trend runs 10 Aug to today, half-widths are rounded and the change is against the settled day. |
| Nit | ± as half the interval though it can be uneven | **Fixed.** The larger side. |
| Nit | Design dialog prefill has no separator; no list-detail golden; 130% axis crowding | **Fixed** for the golden (`health-pane`, `health-pane-dark`; `health-expanded` without a selection). **Accepted** for the prefill: an editable number with a separator breaks the digits-only filter. Also accepted: the crowding at 130%, where the labels stay legible and do not overlap. |

## Result

No critical finding. All four majors are fixed and verified in the goldens. The accepted minors and
nits are recorded above with their reasons.
