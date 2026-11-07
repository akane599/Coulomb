# Audit: Timeline (`design-lab/timeline.html`)

- **Date:** 2026-11-07, phase P3 (SPEC 11.12, 17).
- **Method:** inline technical audit (impeccable 4.7.1 `audit`).
- **Evidence:**
  - detector `npx -y impeccable@4.8.0 detect design-lab/ --json` returned `[]` (exit 0);
  - token and translucent-fill contrast;
  - captures at 412 and 360 px, light and dark;
  - markup review.
- **Scope:** static lab with synthetic data. The scrubber, the ranges and the layers sheet are not interactive.

## Audit health score

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 3 | One `role="img"` summary covers a 13-band chart; the port needs a navigable alternative |
| 2 | Performance | 4 | One generated SVG (41 paths plus band rectangles); static |
| 3 | Theming | 4 | States use neutral ink, components use the data palette, charging uses accent |
| 4 | Responsive design | 3 | Band labels and axis labels shrink with the viewBox at 360 px |
| 5 | Implementation integrity | 4 | The bands agree with the level line, the current panel and the scrubber readout |
| **Total** | | **18/20** | **Good** |

## Implementation integrity verdict

**Pass.** The bands are consistent with each other:
- The level falls while the screen is on.
- It rises only inside the Charging band, and charge current shows above the axis there.
- The 5-minute wakeups during screen-off produce matching Awake, Wakelock and current spikes.
- Deep Doze never appears in today's screen-off period, as the chart's accessible title states.

The scrubber readout and the event row match the data at 10:45:
- 90%, screen off, light Doze, 46 mA;
- the 10th Halcyon wakeup since 09:55.

The detector reports zero findings.

## Executive summary

- **Issues:** 0 P0, 0 P1, 2 P2, 1 P3. Two P2s were fixed during the audit.
- **Top open issues:**
  1. Screen-reader users get one long summary instead of navigable events.
  2. Chart text shrinks at 360 px.
- **Next:** specify the Compose semantics for the timeline (see the first finding), then polish.

## Findings

**[P2] The chart is one image to assistive technology**
- **Location:** `timeline.html`, `<svg class="chart timeline" role="img" aria-labelledby="tl-t">`.
- **Category:** Accessibility.
- **Impact:** the summary sentence is accurate, but a TalkBack user cannot step through the bands or the events, or hear what the scrubber reads at a given time.
- **Standard:** WCAG 1.1.1 is met by the summary. The SPEC 10.6 timeline behaviour needs more than a summary.
- **Recommendation:**
  - In Compose, make the scrubber an adjustable control. Use `semantics { progressBarRangeInfo }` with custom actions for previous and next event.
  - Announce the readout line ("10:45, 90%, screen off, light Doze, 46 mA") as its state description.
  - Expose the event row as the entry point to the event list.
- **Suggested command:** `/impeccable harden`.

**[P2] The current areas had no edge (fixed)**
- **Location:** `tools/build.py` `timeline()`, `lab.css` `.cur-out` and `.cur-in`.
- **Category:** Accessibility.
- **Impact:** the translucent fills (30% secondary, 45% accent) fell below 3:1 against the background. The shape of the current trace depended on fill alone.
- **Standard:** WCAG 1.4.11.
- **Fix applied:** 1 px edge strokes, `.cur-out-edge` in text-secondary (6.26:1 light) and `.cur-in-edge` in accent (3.98:1 light). The fills keep the area reading.

**[P2] Light Doze and alternate Top app segments were about 2:1 (fixed)**
- **Location:** `lab.css`, `.trk-doze-light` and `.trk-app-alt`.
- **Category:** Accessibility.
- **Impact:** light Doze at 45% and alternate app segments at 50% were hard to see. Light Doze was hard to tell from the empty track.
- **Standard:** WCAG 1.4.11.
- **Fix applied:** lighter states are now outlined, with a text-secondary stroke at 3:1 or better and a 20% tint. Deep Doze and the primary app segments stay solid. Verified in light and dark.

**[P3] Chart text shrinks at 360 px**
- **Location:** `<!-- chart:timeline -->`.
- **Category:** Responsive.
- **Impact:** band labels at 13 px become about 11 px, and axis labels at 10 px become about 8.6 px.
- **Recommendation:** use the shared port rule. Draw text in sp. Size band label columns to the measured longest label.
- **Suggested command:** `/impeccable adapt`.

## Patterns and systemic issues

- **Translucent area fills need a solid edge.** The same rule applies to:
  - the Now cone (accent at 14%) and the Charging cone, which already carry solid edge lines;
  - the Health capacity band, which carries the estimate line and the bracket.
- Record the rule in DESIGN.md: a translucent fill never carries meaning alone.

## Positive findings

- **Floating toolbar:**
  - 48 px buttons, with `aria-pressed` on the range;
  - a separator between the range and the tools;
  - the chart reserves 104 px below it, so the toolbar never covers the time axis.
- **Colour roles:**
  - Hue is reserved for power components.
  - States (Doze, Top app, Signal, Temperature) use neutral ink, so the palette keeps one meaning.
- **Event row:** "Halcyon's alarm woke the phone" links straight to the app's evidence.

## Recommended actions

1. **[P2] `/impeccable harden`:** timeline semantics in Compose (adjustable scrubber, event actions, readout state description).
2. **[P3] `/impeccable adapt`:** sp chart text and measured label columns.
3. **`/impeccable polish`:** final pass.

## Re-check after the critique fixes (2026-11-07)

The critique (`2026-11-07-critique-timeline.md`) changed this screen after the audit above. The gates were run again on the changed lab:
- Detector, static: `npx -y impeccable@4.8.0 detect design-lab/ --json` and the plugin launcher both return `[]`.
- Detector, rendered: zero findings at 412×915 and at 360×800.
- Font scale 2.0: Android's non-linear conversion applied to text only, at 412 px. No horizontal overflow, and no clipped text apart from the single-line app bar title. Compose does not clip that title vertically, so it is an artifact of the proxy.
- Type: every size and weight maps to a SPEC 11.3 role (ADR-0023 §3). No 14 px remains.
- Density-span strokes are 3.10 to 5.95:1 against the track. At font scale 2.0 the floating toolbar's ranges scroll inside it, and the toolbar stays within the gutters.

The audit score above stands; nothing new rose to P0 or P1.
