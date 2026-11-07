# Critique: Onboarding, step 4 of 4 (`design-lab/onboarding.html`)

- Date: 2026-11-07, phase P3 (SPEC 11.12, 17)
- Method: dual-agent (A: a92acbe2b317526e6 · B: a51f1055ec66f02cc). One impeccable 4.7.1 `critique` pass over all seven screens (ADR-0022 §6).
  - **A** is the design review: Nielsen's heuristics and five personas (Jordan, Alex, Sam, Riley, Casey).
  - **B** is the detector and browser evidence.
- Scores are A's, on the lab before the fixes below. Each disposition describes the lab after the fixes.
- Decisions that change SPEC are in ADR-0023.

## Score: 24/32 (75%), Good

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | All four progress segments showed done before the user finished |
| 2 | Match with the real world | 3 | The lede packs "kernel wakeups", "power rails" and "power model" |
| 3 | User control and freedom | 3 | Back to Shizuku |
| 4 | Consistency and standards | 3 | Standard app bar and footer button |
| 5 | Error prevention | 3 | The step that prevents gaps in the record was easy to miss |
| 6 | Recognition rather than recall | 3 | Every grant labeled |
| 7 | Flexibility and efficiency | n/a | A single confirmation step |
| 8 | Aesthetic and minimalist design | 3 | Clean |
| 9 | Error recovery | n/a | No denied-grant state is mocked |
| 10 | Help and documentation | 3 | The why-copy on the locked row |

**Design specificity.** Built for this product:
- the capability summary comes before the checklist;
- plain grant names are paired with raw constants in mono;
- the locked row is candid.

**Cognitive load.** No failures.

**Emotional journey.**
- Relief at "Coulomb can see where your battery goes".
- Respect for the candour about what it cannot see.
- Then a fast exit that skipped the step protecting data quality.

**Strengths.**
- The summary comes before the checklist.
- A candid locked row.
- Raw permission constants for power users, kept secondary.

## Priority issues and dispositions

| Sev | Issue | Disposition |
|---|---|---|
| P2 | "Keep the monitor running" sat below the fold, behind the sticky footer | **Fixed.** It moved above the grants, directly under the summary, with an outlined button (ADR-0023 §11, SPEC 14.1). |
| P2 | "Go to Now" set no expectation | **Fixed.** The button reads "Show my battery", and the footer line says "First results after 15 minutes with the screen off." |
| P3 | The lede is the place to teach provenance | **Fixed.** The Modeled mark is attached to "Android's power model". A new "How to read the marks" key follows the grants, with one line per mark and "Touch and hold any figure to see where its number comes from." |
| P3 | The tonal button had no visible boundary (1.08:1) | **Fixed.** It is an outlined button. |
| P3 | Headline role on a compact layout | **Fixed.** The title uses Title 20/26 (ADR-0023 §3). |

**Other fixes from the review.**
- The progress bar shows three segments done and the fourth half full.
- The grants caption reads "These stay granted if Shizuku stops or the phone restarts." This answers Alex's question.

**Persona red flags.**
- **Jordan:** never saw the sleep warning.
  - Now: it comes second on the page.
- **Casey:** the callout button was invisible.
  - Now: outlined.
- **Alex:** did not know which grants survive.
  - Now: stated.

## Questions for the P3 direction review

- Should this step say plainly that the grants stay after Shizuku stops? The lab now does.

## Detector and browser evidence (B)

- **At critique time:** static `[]`; rendered zero; overlay clean.
- **After the fixes:**
  - The first rendered scan of the new key found two stragglers: "model" and "range" alone on second lines. Both definitions were shortened, and definitions now wrap "pretty".
  - Static `[]`.
  - Rendered: zero at 412×915 and at 360×800.

Questions skipped: autonomous run (SPEC 0.6); questions folded into the P3 direction review.
