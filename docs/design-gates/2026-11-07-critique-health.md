# Critique: Health (`design-lab/health.html`)

- Date: 2026-11-07, phase P3 (SPEC 11.12, 17)
- Method: dual-agent (A: a92acbe2b317526e6 · B: a51f1055ec66f02cc). One impeccable 4.7.1 `critique` pass over all seven screens (ADR-0022 §6).
  - **A** is the design review: Nielsen's heuristics and five personas (Jordan, Alex, Sam, Riley, Casey).
  - **B** is the detector and browser evidence.
- Scores are A's, on the lab before the fixes below. Each disposition describes the lab after the fixes.
- Decisions that change SPEC are in ADR-0023.

## Score: 25/36 (69%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | The basis is stated: 14 charge and discharge windows |
| 2 | Match with the real world | 2 | "Since 12 Aug", "qualifying window", "power profile" |
| 3 | User control and freedom | 3 | Rename and See all; nothing destructive |
| 4 | Consistency and standards | 2 | A ring meant "window" here and Modeled elsewhere |
| 5 | Error prevention | 3 | No risky actions |
| 6 | Recognition rather than recall | 3 | Every figure labeled |
| 7 | Flexibility and efficiency | 3 | See all; a list of chargers |
| 8 | Aesthetic and minimalist design | 3 | Calm, with three figure strips |
| 9 | Error recovery | n/a | No error state is mocked |
| 10 | Help and documentation | 3 | The caption explains the encodings |

**Design specificity.** Built for this product:
- the capacity interval is a bracket on an 80–100% scale;
- three sources are cross-checked in a table;
- no health score, no badge.

**Cognitive load.** Fails progressive disclosure: a long tail of statistics with no verdict to anchor it.

**Emotional journey.**
- An authoritative number with its interval builds trust.
- The candid cross-check is the peak.
- Then a long run of figures with no "so what".

**Strengths.**
- The signature bracket is applied to capacity.
- A candid three-source cross-check.
- All seven SPEC 10.8 items are present.

## Priority issues and dispositions

| Sev | Issue | Disposition |
|---|---|---|
| P2 | No verdict layer | **Fixed.** The verdict follows the scale: "About 110 mAh lower than on 12 August, within the first estimate's ±150 mAh range." A suggested "Holds 92% of its design capacity after 312 cycles", but the caption under the numeral already says "92% of the 5,000 mAh design capacity". The verdict adds the trend and how much of it is real. |
| P2 | The ring was overloaded (window vs Modeled) | **Fixed.** Windows are crosses, and the four provenance shapes are now reserved (ADR-0023 §10). The caption reads "Each cross is one qualifying window", with "qualifying window" as a glossary term. |
| P2 | The cross-check overstated provenance: Android's and the fuel gauge's figures were marked Measured | **Fixed by redefinition** (ADR-0023 §1, superseding ADR-0022 §3). Both are model outputs, so both are now Modeled. The note reads "Three estimates by three methods; they agree within 2 points." The cycle count stays Measured, since it is a counter. |
| P2 | Figure strips did not reflow at large text | **Fixed.** The strips are a wrapping flex row: 3, then 2 + 1 at 360 px, with no clipping at font scale 2.0. |
| P3 | Copy details | **Fixed.** Coulomb's count reads "since 12 Aug; Android counted 63". Charger rows say "Baseline · last seen …". Each Rename has its own label ("Rename Desk charger"). **Kept:** the three Rename buttons, since SPEC 10.8 item 7 requires rename and an overflow menu would hide it. |

**Persona red flags.**
- **Jordan:** "is 4,610 mAh ± 70 good?" The caption under the numeral says "92% of the 5,000 mAh design capacity", and the verdict says whether it is changing.
- **Riley:** the system's estimate was shown as more certain than Coulomb's.
  - Fixed: all three estimates are marked by method.
- **Sam:** strips collided at large text.
  - Fixed.

## Questions for the P3 direction review

- Is the cross-check more valuable than the trend chart? Should it come first?

## Detector and browser evidence (B)

- **At critique time:** static `[]`; rendered zero; overlay clean.
- **After the fixes:**
  - Static `[]`.
  - Rendered: zero at 412×915 and at 360×800.
  - Crosses on the interval band: 3.90 to 5.60:1.

Questions skipped: autonomous run (SPEC 0.6); questions folded into the P3 direction review.
