# Critique: Now (`design-lab/now.html`)

- Date: 2026-11-07, phase P3 (SPEC 11.12, 17)
- Method: dual-agent (A: a92acbe2b317526e6 · B: a51f1055ec66f02cc). One impeccable 4.7.1 `critique` pass over all seven screens (ADR-0022 §6).
  - **A** is the design review: Nielsen's heuristics scored 0–4, the cognitive-load checklist, and five personas:
    - Jordan wants the verdict;
    - Alex is a power user;
    - Sam relies on TalkBack and large text;
    - Riley stress-tests;
    - Casey is distracted and uses one hand.
  - **B** is the detector and browser evidence:
    - `npx -y impeccable@4.8.0` and the plugin launcher;
    - static scans and rendered scans at 412×915;
    - the injected overlay in light and dark.
- Scores are A's, on the lab before the fixes below. Each disposition describes the lab after the fixes.
- Decisions that change SPEC are in ADR-0023.

## Score: 25/36 (69%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | Nothing says what the time-remaining range rests on |
| 2 | Match with the real world | 3 | "Deep sleep 88%" is Evidence vocabulary with no gloss |
| 3 | User control and freedom | 3 | Insight cards cannot be snoozed or dismissed |
| 4 | Consistency and standards | 3 | The filled "Restrict background" was a link |
| 5 | Error prevention | 3 | Consequences are stated, but the button implies a one-tap action |
| 6 | Recognition rather than recall | 2 | Four provenance shapes with no key; 74% had no mark |
| 7 | Flexibility and efficiency | 3 | One tap to evidence; the projection cannot be scrubbed |
| 8 | Aesthetic and minimalist design | 3 | About 60 px of dead space under the scale |
| 9 | Error recovery | n/a | No error or degraded state is mocked |
| 10 | Help and documentation | 2 | "Why am I seeing this?" on only one card; no glossary |

**Design specificity.** The screen is built for this product. The copper bracket on a graduated scale, the projection cone, the timetable session strip and the drawn provenance marks are Coulomb's own; the insight cards and ruled rows are standard Material 3.

**Cognitive load.** Fails working memory: four shapes to remember, and no key. No decision point has more than four options.

**Emotional journey.**
- Calm confidence at the hero.
- Curiosity at the linked app name in the verdict.
- A letdown when "Restrict background" opened a long page instead of acting.

**Strengths.**
- The estimate is never a point: the bracket spans 20:10–21:40, and the same range is printed beside the level.
- The verdict answers how much and why in one sentence, and its numbers agree with every other screen.
- The live current chart has an honest caption about its update rate.

## Priority issues and dispositions

| Sev | Issue | Disposition |
|---|---|---|
| P2 | Provenance cannot be read: no key, long-press only, no mark on 74% | **Fixed.** 74% carries the Modeled mark, since the fuel gauge models the level (ADR-0023 §1). Onboarding teaches the four marks. Long-press stays because rows own the tap, and a TalkBack "Explain provenance" action is added (ADR-0023 §2). |
| P2 | Insight cards lack their lifecycle and contain a false action | **Fixed.** Each card has an overflow button in its head (snooze, dismiss) and "Why am I seeing this?". The primary button opens the SPEC 13 preview sheet (`aria-haspopup="dialog"`) instead of navigating (ADR-0023 §8). |
| P3 | Dead band in the hero (`min-height: 284px`) | **Fixed.** The min-height is gone and the projection chart is taller. The hero measures 276 px, 30.2% of 915, so the contract's 30% still holds. |
| P3 | The axis mixes relative ticks with an absolute bracket label | **Fixed.** The axis is in clock time: 13:00, now, 16:00, 18:00, 00:00. |
| P3 | Glyph orphans at large text | **Fixed.** Each number, its unit and its mark sit in a group that cannot break. |

**Persona red flags.**
- **Jordan:** marks with no key, so "Deep sleep 88%" could be good or bad.
  - Now: a key at onboarding, and "Deep sleep" is a glossary term.
- **Alex:** "Restrict background" cost a page load plus a scroll.
  - Now: the preview sheet opens in place.
- **Sam:** the range mark wrapped away from "left".
  - Now: kept together.

**Minor observations.**
- "See all" and "Why am I seeing this?" share the text-button style. Kept: "See all" carries a chevron, which marks navigation.
- The Watch card ends on an external link to battery protection. Kept: it is the SPEC 13 settings deep link, and the icon marks it as external.

## Questions for the P3 direction review

- If Jordan leaves after three seconds, which carries the answer, the bracket or the sentence? Could one of them go?
- Should the level ever appear without a mark? It now carries Modeled.

## Detector and browser evidence (B)

- **At critique time:**
  - Static scans, both entry points: `[]` with exit 0.
  - Rendered scan at 412×915: zero findings.
  - Overlay, light and dark: "No anti-patterns found".
- **After the fixes:**
  - Static: `[]` from `npx -y impeccable@4.8.0` and from the plugin launcher.
  - Rendered: zero findings at 412×915 and at 360×800.
- **Font scale 2.0:** checked with Android's non-linear conversion applied to text only, at 412 px. No horizontal overflow.

Questions skipped: autonomous run (SPEC 0.6); questions folded into the P3 direction review.
