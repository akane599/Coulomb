# Critique: Charging (`design-lab/charging.html`)

- Date: 2026-11-07, phase P3 (SPEC 11.12, 17)
- Method: dual-agent (A: a92acbe2b317526e6 · B: a51f1055ec66f02cc). One impeccable 4.7.1 `critique` pass over all seven screens (ADR-0022 §6).
  - **A** is the design review: Nielsen's heuristics and five personas (Jordan, Alex, Sam, Riley, Casey).
  - **B** is the detector and browser evidence.
- Scores are A's, on the lab before the fixes below. Each disposition describes the lab after the fixes.
- Decisions that change SPEC are in ADR-0023.

## Score: 27/36 (75%), Good

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 4 | Power, current, voltage, temperature, both ETAs, target and start: complete |
| 2 | Match with the real world | 3 | "constant current" in the hero |
| 3 | User control and freedom | 3 | Target and alert are reversible |
| 4 | Consistency and standards | 3 | The Measured mark followed "Desk charger", not a number |
| 5 | Error prevention | 3 | No risky actions |
| 6 | Recognition rather than recall | 3 | Labeled figures |
| 7 | Flexibility and efficiency | 3 | A row of target presets |
| 8 | Aesthetic and minimalist design | 3 | The one-row "Desk charger" section was thin |
| 9 | Error recovery | n/a | No error state is mocked |
| 10 | Help and documentation | 2 | No explanation of the charging phase |

**Design specificity.** Built for this product:
- a copper Display numeral;
- a wavy level used only here;
- a bracket on the session curve.

The figures are physically consistent: 4.58 A × 4.02 V = 18.4 W.

**Cognitive load.** No failures; the target selector has four options.

**Emotional journey.**
- A clear "it's charging well" glance at 18.4 W.
- Trust from the ETA ranges.
- A brief "what does that mean?" at "constant current".

**Strengths.**
- The figures are physically consistent.
- Accent discipline: copper only on the numeral and the wavy level.
- ETAs are ranges, and all of SPEC 10.9 is present.

## Priority issues and dispositions

| Sev | Issue | Disposition |
|---|---|---|
| P2 | Provenance misplaced and sparse | **Fixed.** Every figure has its own mark:<br>- 18.4 W is Derived;<br>- 52% is Modeled;<br>- A, V and °C are Measured;<br>- both ETAs are Estimated. |
| P2 | The signature move was half-applied: the tick said only "Target 80%" | **Fixed.** Beside the target tick: "80% at 19:27–19:33", followed by the Estimated bracket mark. |
| P3 | Jargon in the hero | **Fixed.** The hero reads "52% · full speed", where "full speed" is a glossary term that explains constant current and constant voltage (ADR-0023 §9, SPEC 10.9). |
| P3 | The target selector is far from its tick | **Kept** in the lab. The selector is the accessible control. Making the tick draggable is a direction-review question for the Compose port. |
| P3 | A one-row "Desk charger" section | **Fixed.** Folded into the hero: "Desk charger · 85% of its baseline". |

**Contrast.** The copper numeral is 3.98:1 on light `bg`, which passes as large text at 56 px.

**Persona red flags.**
- **Casey:** the time to 80% was not at the 80% tick.
  - Fixed.
- **Jordan:** "constant current".
  - Fixed.
- **Sam:** copper must never be reused for small text.
  - It isn't: `accent` never carries text (SPEC 11.4).

## Questions for the P3 direction review

- Should the level (52%) or the power (18.4 W) get the Display role, given that Charging replaces Now?
- Should the target tick be draggable?

## Detector and browser evidence (B)

- **At critique time:** static `[]`; rendered zero; overlay clean.
- **After the fixes:**
  - Static `[]`.
  - Rendered: zero at 412×915 and at 360×800.
  - The neutral switch is 16.0 to 17.6:1.

Questions skipped: autonomous run (SPEC 0.6); questions folded into the P3 direction review.
