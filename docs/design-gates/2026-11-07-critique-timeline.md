# Critique: Timeline (`design-lab/timeline.html`)

- Date: 2026-11-07, phase P3 (SPEC 11.12, 17)
- Method: dual-agent (A: a92acbe2b317526e6 · B: a51f1055ec66f02cc). One impeccable 4.7.1 `critique` pass over all seven screens (ADR-0022 §6).
  - **A** is the design review: Nielsen's heuristics and five personas (Jordan, Alex, Sam, Riley, Casey).
  - **B** is the detector and browser evidence.
- Scores are A's, on the lab before the fixes below. Each disposition describes the lab after the fixes.
- Decisions that change SPEC are in ADR-0023.

## Score: 24/36 (67%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | The cursor readout is a live region with time and state |
| 2 | Match with the real world | 3 | "light Doze" in the readout has no gloss |
| 3 | User control and freedom | 3 | Ranges plus a jump back to now |
| 4 | Consistency and standards | 2 | "90%" used Now's Display slot; the pressed range had no check |
| 5 | Error prevention | 2 | Dense ticks drew solid bands (P1) |
| 6 | Recognition rather than recall | 3 | Every track labeled at the left |
| 7 | Flexibility and efficiency | 3 | Range presets and a track chooser |
| 8 | Aesthetic and minimalist design | 3 | Eleven tracks, dense but ruled |
| 9 | Error recovery | n/a | No error state is mocked |
| 10 | Help and documentation | 2 | No units on Signal and Temperature; no "View as table" |

**Design specificity.** The strongest instrument screen:
- eleven directly labeled tracks;
- signed current;
- a crosshair readout in the Data role.

**Cognitive load.** Fails chunking: eleven tracks with no grouping.

**Emotional journey.**
- A power user's instrument moment, with the event card telling the story.
- Then doubt, when the readout contradicted the bands under the cursor.

**Strengths.**
- All eleven SPEC 10.6 tracks are present, each labeled directly.
- Discharge plots below the axis, with signed units.
- The event card turns the cursor position into a sentence that links to its evidence.

## Priority issues and dispositions

| Sev | Issue | Disposition |
|---|---|---|
| **P1** | **The dense ticks misstated the measurement.** 39 ticks about 1 px apart merged into solid Awake, Wakelocks and Mobile radio bands. That read as "awake continuously", while the readout and the event card describe 5-minute alarms. | **Fixed.** Runs of three or more events closer than 3 px become an outlined density span: a 20% tint with a 1 px stroke in the track's hue. The caption reads: "Outlined spans on Awake, Wakelocks and Mobile radio are events too close to draw one by one at this range: here 12 wakeups an hour. Zoom to 6 h to see each." The stroke is 3.10 to 5.95:1 against the track background. This is now a SPEC rule (ADR-0023 §6, SPEC 10.6 and 11.8). |
| P2 | "90%" read as the current charge | **Fixed.** The readout fuses time and figure at Title size, "Today 10:45 · 90%", and the level carries the Modeled mark. No Display numeral on this screen. |
| P2 | The pressed range was hard to see (1.22:1 light, 1.56:1 dark) | **Fixed.** The pressed range shows a check icon, as the window selector does. |
| P3 | Unlabeled tracks | **Partly fixed.** The caption gives the scales: signal from none to great, temperature from 26 to 40 °C. The alternating top-app spans are **deferred to the Compose port**, where the scrub readout names the app under the cursor. |
| P3 | Ambiguous "jump to now" icon | **Fixed.** A "Now" text button labeled "Jump to now". |

**Other fixes from the review.**
- "light Doze" in the readout is a glossary term.
- The tracks are grouped: power and state first, radios after a gap.
- "View as table" is under the caption.
- At font scale 2.0 the floating toolbar's ranges scroll inside it, so Layers and Now stay in reach and nothing leaves the screen.

**Persona red flags.**
- **Alex:** concluded there was a stuck wakelock.
  - Now: spans and the stated rate say "12 an hour".
- **Sam:** had no table alternative.
  - Now: one exists.
- **Riley:** at 7 d every alarm-heavy day would be solid.
  - Now: the same density rule applies at every range.

## Questions for the P3 direction review

- At 24 h, should density be the basic primitive, with ticks only on zoom? The lab now does this.
- What does a Display numeral earn on a screen whose subject is time? The lab now uses none.

## Detector and browser evidence (B)

- **At critique time:** static `[]`; rendered zero; overlay clean.
- **After the fixes:**
  - Static `[]`.
  - Rendered: zero at 412×915 and at 360×800.
  - Toolbar at font scale 2.0: it spans 16 to 396 px, and the ranges scroll (301 px of content in 231 px).

Questions skipped: autonomous run (SPEC 0.6); questions folded into the P3 direction review.
