# Critique: App detail (`design-lab/app-detail.html`)

- Date: 2026-11-07, phase P3 (SPEC 11.12, 17)
- Method: dual-agent (A: a92acbe2b317526e6 · B: a51f1055ec66f02cc). One impeccable 4.7.1 `critique` pass over all seven screens (ADR-0022 §6).
  - **A** is the design review: Nielsen's heuristics and five personas (Jordan, Alex, Sam, Riley, Casey).
  - **B** is the detector and browser evidence.
- Scores are A's, on the lab before the fixes below. Each disposition describes the lab after the fixes.
- Decisions that change SPEC are in ADR-0023.

## Score: 25/36 (69%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | The scope is set, but the window cannot be changed |
| 2 | Match with the real world | 3 | "Standby bucket", "rare", "Cached" are raw platform terms |
| 3 | User control and freedom | 4 | The Undo row names who, what, when and the previous value |
| 4 | Consistency and standards | 2 | One provenance mark on the page; a legend list against SPEC 11.8 |
| 5 | Error prevention | 3 | "Restrict background" had no consequence line here |
| 6 | Recognition rather than recall | 3 | Tables labeled; plain notes under tags |
| 7 | Flexibility and efficiency | 2 | Actions sat about 2.5 screens down |
| 8 | Aesthetic and minimalist design | 3 | The Jobs table repeats the prefetch wakelock |
| 9 | Error recovery | n/a | No error state is mocked |
| 10 | Help and documentation | 2 | No glossary for bucket or process-state terms |

**Design specificity.** Mostly specific to the product:
- the Story sentence;
- the 14-day chart tied to the 8.12 update;
- mono tags with breakpoints;
- the Undo row.

The stacked tables are generic.

**Cognitive load.** Fails grouping: the network strip's heading was visually hidden, so it read as part of the wakeups section.

**Emotional journey.**
- An "aha" from the Story and the trend caption.
- Confidence through the wakelock tables.
- Friction at the decision, behind jargon at the very bottom.

**Strengths.**
- The Undo row models reversibility (heuristic 3 scored 4).
- The trend chart and its caption link cause to effect.
- Raw identifiers stay in the Evidence layer, each with a plain note.

## Priority issues and dispositions

| Sev | Issue | Disposition |
|---|---|---|
| P2 | Provenance coverage collapses to one mark | **Fixed.** Header-level marks (ADR-0023 §2):<br>- "Where it went": Modeled;<br>- process-state Time: Measured; process-state mAh: Modeled;<br>- "Woke the phone 41 times": Measured;<br>- "Network and screen time": Measured;<br>- "Last 14 days": Modeled. |
| P2 | Actions are buried 2.5 screens down | **Fixed at the source.** The Now card's primary button opens the SPEC 13 preview sheet in place (ADR-0023 §8), so nobody has to scroll here to act. Whether Act-rated apps should show Actions before Evidence is a direction-review question. |
| P2 | Action copy is platform jargon, with no consequence line | **Fixed.** The rows now read:<br>- "Now unrestricted. Restricting stops its background alarms; messages may arrive later."<br>- "How often Android lets it run in the background: Rarely, set in the Action Center 10 min ago; was any time", with an Undo labeled "Undo: let it run any time again". |
| P3 | Process states summed to 5 h 22 min inside a 5 h 12 min window | **Fixed.** Cached is 2 h 52 min, so the states sum to 5 h 12 min and the mAh to 402. |
| P3 | Hidden heading and a legend list | **Fixed.** "Network and screen time" is visible. The component bar is labeled directly: wide segments carry name and mAh, and the narrow tail has a leader ("Wi-Fi 12 · Other 7 mAh"). |
| SPEC gap | No window selector (SPEC 10.1) | **Fixed.** A "Since unplugged" window button sits in the header. |

**Persona red flags.**
- **Riley:** process times overran the window.
  - Fixed.
  - Riley also asked why an app set to "rare" yesterday still fired every 5 minutes. The bucket change is now 10 min old, after the data shown.
- **Jordan:** the jargon at the decision point is replaced by plain words.
- **Alex:** can change the window now. The overflow menu stays unspecified in the lab.

**Minor observation.** "Wi-Fi 48 MB at home" implied location knowledge.
- **Fixed:** it now reads "4% in background".

## Questions for the P3 direction review

- Should the page say whether Coulomb's earlier action worked?
- For an app rated Act, should Actions come before Evidence?

## Detector and browser evidence (B)

- **At critique time:**
  - Static `[]`.
  - The rendered scans and the overlay found one advisory straggler: "active" alone on the second line of the bucket row's state.
- **After the fixes:**
  - The straggler is gone: the copy was rewritten, and prose, captions and definitions wrap "pretty".
  - Static `[]`.
  - Rendered: zero at 412×915 and at 360×800.

Questions skipped: autonomous run (SPEC 0.6); questions folded into the P3 direction review.
