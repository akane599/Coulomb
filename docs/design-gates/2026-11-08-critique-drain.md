# Critique: Drain (Compose, `:feature:drain`)

- Date: 2026-11-08, phase P4 (SPEC 11.12, 17). Dispositions recorded 2026-11-09.
- Method: impeccable 4.7.1 `critique`, design review only; the detector does not apply to a native
  screen. One fresh read-only agent read SPEC 1.2, 6, 10, 11 and 19.3, PRODUCT.md, DESIGN.md,
  ADR-0035 and ADR-0036, the lab page, the Drain sources and strings, and all 27 goldens of the
  time.
- Scores are the agent's, on the screen before the fixes below. Decisions are in ADR-0036 §17-33.

## Score: 27/40 (68%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | The previous window's figures stay under a new choice; no as-of time |
| 2 | Match with the real world | 2 | Raw kernel names and wakeup reasons have no plain-language line |
| 3 | User control and freedom | 3 | Wakeups "Show all" has no way back |
| 4 | Consistency and standards | 2 | Mono unused; Components % has another base than Apps %; no glossary terms or pull to refresh |
| 5 | Error prevention | 3 | The system-apps default contradicts SPEC 14.4 |
| 6 | Recognition rather than recall | 3 | Apps names its denominator, Components does not |
| 7 | Flexibility and efficiency | 3 | Compare two periods is missing |
| 8 | Aesthetic and minimalist design | 3 | Two switch rows push the first app down to about 490 dp |
| 9 | Error recovery | 3 | Empty states do not suggest another window |
| 10 | Help and documentation | 2 | No glossary terms and no wakeup dictionary |

**Design specificity.** Built for this product: the measured total in the Display numeral with the
Measured dot, the hatched unattributed gap, the Modeled ring on every row, neutral share bars, data
hues only in Components, copper only on the chosen window. The missed opportunity was Wakeups, a
generic list where a timetable belongs.

**Strengths.** The rows add up to the Display figure (959 + 103 + 142 = 1,204 mAh), and the caption
names the denominator. Edge cases are handled candidly. At 200% text values drop under names, tabs
stack and choices wrap.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | An Estimated total shows as one exact number | **Fixed.** An Estimated total is a range with the bracket mark; the caption says how it was mixed. With no known error the gap is not printed (§17, §30). Goldens `drain-estimated`, `drain-harden-*`. |
| Major | The old window's figures sit under the new chip | **Fixed.** The body is the skeleton until the new report arrives (§19). Golden `drain-stale`. |
| Major | Components shares have an unstated base | **Fixed.** The caption names the modeled total as the base, and each row has a share bar (§20). |
| Major | SPEC 10.3 content missing | Kernel subsystems and the dictionary: **fixed** (§32). Screen-off share: the read layer gives it to kernel records as to the others; the sample had none, and the goldens now show it. Component chips and top apps per component: **with S4** (ADR-0035, §33). Compare two periods: **open**, built next. |
| Major | System apps shown by default, against SPEC 14.4 | **Fixed.** Off by default and kept in the settings store (§18). |
| Major | Medium width is one column | **Fixed.** Two columns once both fit (§23). Golden `drain-apps-expanded`, `-components-expanded`, `-wakeups-expanded`. |
| Major | Wakeups terms have no glossary tooltip | **Fixed.** The group heads are glossary terms. |
| Major | Raw identifiers not in Mono | **Fixed.** Tags, kernel names and reasons are in Mono. |
| Minor | Components and Wakeups rows lack share bars | **Fixed** (§20, §31). |
| Minor | Bars lack "View as table" | **Fixed.** A semantics action opens a table sheet (§25). Goldens `drain-reconcile-table`, `drain-components-table`. |
| Minor | Remainder affordances unclear | **Fixed.** Expanded or collapsed state, expand and collapse actions, a plain stand-in for non-tappable groups (§26). |
| Minor | "5 more apps" undercounts | **Fixed.** "N more apps and small ones" when unnamed UIDs add to it (§26). |
| Minor | Two switch rows between tabs and list | **Fixed.** "Scale to measured" sits under the reconcile bar, Apps lens only (§24). |
| Minor | No as-of time, no pull to refresh | **Fixed** (§28). |
| Minor | "80% with the screen off" is ambiguous | **Fixed.** "80% held with the screen off", EN and TR. |
| Minor | "Show all" has no "Show fewer" | **Fixed.** The same button toggles, with its state. |
| Nit | TR: "Diğer" for More; mixed verbs | **Fixed.** "Daha fazla"; "Şarjdan çıkalı" throughout. |
| Nit | RTL glues the mark to "mAh" | **Fixed.** Inline marks take their paragraph's direction (`ProvenanceMark`). |
| Nit | "Ranked by time held" is wrong for counts | **Fixed.** The caption says records with no time rank by count. |
| Nit | Group heads in Title | **Fixed.** Label, as DESIGN.md gives list-group heads. |

**Deliberate, not flagged:** the 10% unattributed row (§11), the 3% row threshold (§8), "The phone
itself" (§9), hidden system apps in the remainder (§10), the sentence that replaces the bar (§13),
neutral components at the bar's end (§14), the window kept in memory (§7).

## Result

All findings fixed except two. The chips wait for S4 (ADR-0035). Compare two periods is open and
blocks this gate until it lands.
