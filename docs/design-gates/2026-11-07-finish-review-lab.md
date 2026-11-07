# Finish review: the design lab (`design-lab/`)

- Date: 2026-11-07, phase P3 (SPEC 11.12 step 3, 17)
- Method: impeccable 4.7.1 finish review (`impeccable-finish-reviewer`, one reviewer for both rounds).
  - The reviewer judged the seven screens against the direction contract (`.impeccable/surfaces/design-lab-now-html.md`), SPEC 11 and the critique and audit records.
  - Inputs: 28 captures (light and dark; the 412 × 915 viewport at 2x and the full page at 1x), plus 200% text captures in round 2.
  - The reviewer's list was the only work list. No second detector or critique ran between rounds.
- Round budget: 2.

## Round 1: fix

The reviewer kept the direction:
- the bracket on a graduated scale, with copper only for the bracket, charging power and selection;
- the cool slate ground;
- Drain's arithmetic (1,062 + 142 = 1,204 mAh).

It returned eight material fixes.

| # | Fix | What changed |
| --- | --- | --- |
| 1 | Now: only two of the three Top drains rows cleared the navigation bar at 412 × 915 | The verdict sentence moved into the hero, and the hero's gaps went to 16. Sections are 24 apart on every screen. A section head's text button keeps its 48 dp target without adding height. The third row now ends 11 px above the bar. The hero is 31.9% of the viewport. |
| 2 | The direction contract said "seed: none (unavailable)" | It now says the seed was not rolled because SPEC 11 pins the direction (ADR-0022). |
| 3 | App detail said "Most of it was the screen"; the screen was 188 of 402 mAh (47%) | "The screen took the largest share, 188 of its 402 mAh, and mobile data took 121 mAh." |
| 4 | Charging's figures disagreed. The baseline share was 75%, not 85%; 4.58 A would reach 80% in about 17 min, not 22–28; the session curve and its 24 W start fitted neither. | One charging model in `design-lab/tools/build.py` now drives the level, both session curves and the estimates. See [the charging model](#the-charging-model). |
| 5 | Missing provenance on Drain's bar labels, Health's "First used" and Health's charge sessions | Drain's labels moved into HTML under the bar: Modeled on 1,062 mAh and Derived on the 142 mAh unattributed (measured minus modeled; SPEC 6.7). "First used" carries Measured. "Charge sessions" carries Modeled, because level deltas come from the fuel gauge. |
| 6 | Onboarding: a filled container competed with the primary action, and the last step was half filled | The "Keep the monitor running" block is a ruled row. The current step fills completely: the critique's half-filled current step is reversed. |
| 7 | Motion tokens were missing from `lab.css` | `:root` holds the SPEC 11.6 springs: damping 0.95 and 1.0, and stiffness 1400, 700 and 300. It also holds the 150 ms reduced-motion crossfade. `--ease-out` is labelled as the lab's tween curve. |
| 8 | No evidence for 200% text | Captures of Now, Drain and Charging at 200%, plus App detail's tables at 360 dp. See [200% text](#200-text). |

### The charging model

- **Power.** 18.4 W into the battery up to 70% ("full speed"). The voltage rises linearly through 4.02 V at 52%, so the current there is 18.4 / 4.02 = 4.58 A. Above 70%, the current tapers linearly to 0.9 A at full. The capacity is Health's 4,610 mAh.
- **Session.** It starts at 21% at 18:31 and reaches 52% after 18.5 min, so "now" is 18:50.
- **Estimates.** The model gives 18.2 min to 80% and 48.4 min to full. They are shown as ranges of ±8%: "80% in 16–20 min" (19:06–19:10 on the tick and the curve's bracket) and "Full in 44–53 min".
- **Baseline.** The Desk charger's baseline on Health is now 19.2 W. The hero reads "96% of its baseline", which agrees with "full speed": R13 calls charging slow below 70%. The other chargers' share bars are rescaled.

### 200% text

- **Method.** Android's non-linear curve at 2.0 (`FontScaleConverterFactory`):
  - fromSp 8, 10, 12, 14, 18, 20, 24, 30, 100;
  - toSp 16, 20, 24, 26, 30, 34, 36, 38, 100.

  The curve is applied in the browser to every HTML element's font size and line height separately, as Compose converts each sp value.
- **Reflow.**
  - The hero's side text drops under the reading.
  - Live figures stack.
  - App rows wrap names and details while values hold their column.
  - The window chips wrap instead of scrolling off the edge.
  - Under a container query in `em`, which follows text size, the lens segments stack and table rows put the name above its values. The query never applies at 1.0 or 1.3.
- **Clip scan.** All seven screens at 412 and 360 dp: nothing clips, and no screen scrolls sideways.
- **Limits of the proxy.**
  - Chart labels are inside fixed SVGs and were not scaled. In the port they are sp text, so each chart must re-lay its labels; this carries into P4.
  - Provenance glyphs stay 12 dp.

### Checks after round 1

- Detector `npx -y impeccable@4.8.0 detect design-lab/ --json`: `[]`.
- Rendered detector: zero findings on all seven screens at 412 × 915 and at 360 × 800.

## Round 2: recapture

The reviewer's first answer in round 2 was "recapture", before scoring any fix.
- **Fault.** Some light captures showed the browser's default buttons: gray (#EFEFEF), square, with no selected fill and empty switches. A full comparison against a later clean set found six faulty files out of 28:
  - Charging at both sizes in light, and its viewport capture in dark (gray, square buttons without the #EFEFEF signature);
  - Drain's full page in light;
  - Timeline's full page in light;
  - Now's full page in dark.
- **Cause.** The lab has no scripts. Headless Chrome sometimes took the screenshot while the background, radius and transform transitions were still leaving the default styles. Single re-shoots came out clean, so the fault was a race.
- **Fix.** `capture.sh` captures under reduced motion. The lab's reduced-motion block limits transitions to opacity.
- **Check.** Two full passes are pixel-identical, and no capture contains #EFEFEF. Only the six faulty files differ from the earlier batch, and only within the faulty regions.

## Round 2: verdict

**Disposition: fix.** Seven fixes are resolved and fix 8 is partial. The reviewer scored the eight fixes on the valid set. This is not a new review of the whole surface. It saw no regressions in the 1.0 captures.

| # | Score | Reviewer's note |
| --- | --- | --- |
| 1 | Resolved | All three Top drains rows, including the last share bar and hairline, clear the navigation bar. The hero still leads. |
| 2 | Resolved | The brief records that the seed was not rolled, and why. |
| 3 | Resolved | The story gives the screen's 188 of 402 mAh and mobile data's 121 mAh. |
| 4 | Resolved | 18.4 / 19.2 W is 96%. 18:50 plus 16–20 min agrees with the tick and the bracket at 19:06–19:10. 21% to 52% in 18.5 min fits 4.58 A into 4,610 mAh. Health's bars are rescaled. |
| 5 | Resolved | Drain's Modeled and Derived labels, Health's "First used" (Measured) and "Charge sessions" (Modeled). |
| 6 | Resolved | A ruled callout with no fill; all four steps filled on step 4 of 4. |
| 7 | Resolved | `lab.css` holds the springs and the reduced-motion crossfade, and the reduced-motion block uses it. |
| 8 | Partial | The 200% captures reflow without sideways overflow, but two text faults remain on Drain at 200%. The chart labels are still unscaled. |

What remains of fix 8:
- **Word breaks.** "Unattributed" breaks mid-word, because row names use `overflow-wrap: anywhere` (`lab.css`, `.app-row .name`; also `.t-mono` and `.check-row .t-mono`). Names should break only between words, and the name column should take width from the value column at large text.
- **The "+22" tile.** "+22" spills out of its 40 px tile (`.more .app-icon-gap`).
- **Chart labels.** Labels inside the fixed SVGs are not scaled.

The clip scan missed the first two. It checked for clipping and sideways scrolling, not for broken words or text leaving a fixed box.

### Why the review ends here

- impeccable ends an unattended run after two rounds and carries the rest forward. It runs no third fix batch in the same thread.
- SPEC 11.12's lab gates are steps 1–5. The finish review is impeccable's own check on top of them.
- The three items carry into P4: SPEC 11.12's Compose phase renders every screen at font scale 2.0. They are listed in `docs/progress.md`.
- The owner chooses at the P3 direction review between shipping the lab as it stands and funding a third round.
