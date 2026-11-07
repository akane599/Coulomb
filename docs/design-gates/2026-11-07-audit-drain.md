# Audit: Drain (`design-lab/drain.html`)

- **Date:** 2026-11-07, phase P3 (SPEC 11.12, 17).
- **Method:** inline technical audit (impeccable 4.7.1 `audit`).
- **Evidence:**
  - Detector: `npx -y impeccable@4.8.0 detect design-lab/ --json` returned `[]` (exit 0).
  - Token contrast computed with WCAG 2.x formulas.
  - Captures at 412 and 360 px, light and dark.
  - Markup review.
- **Scope:** static HTML lab with synthetic data and no script. Selection controls show their selected state only.

## Audit health score

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 3 | Radio and tab groups have the right roles but no roving-focus keyboard model (lab has no script) |
| 2 | Performance | 4 | Static, no script, one sprite |
| 3 | Theming | 4 | Data palette and accent are tokens in all three themes |
| 4 | Responsive design | 4 | Window selector scrolls by design with a clipped last chip as the affordance; rows truncate cleanly at 360 px |
| 5 | Implementation integrity | 4 | App rows did not sum to the modeled total (fixed with a "22 more apps" row); the reconcile bar shows the unattributed gap as a hatched segment |
| **Total** | | **19/20** | **Excellent** |

## Implementation integrity verdict

**Pass.**

The screen's central claim is the reconciliation of measured charge against modeled attribution. The data holds together:

| Item | Value |
| --- | --- |
| Charge counter, measured | 1,204 mAh |
| Power model, modeled | 1,062 mAh |
| Unattributed | 142 mAh (12%) |

- The unattributed share appears in three places, and the numbers match:
  - the bar's hatched gap;
  - the last row, "Not in the power model";
  - the chart's accessible title.
- Per-app values now sum to the modeled total, after the fix below: 905 mAh in the listed apps plus 157 mAh in "22 more apps".
- Component chips use the data palette, where hue means power component.
- The detector reports zero findings.

## Executive summary

- **Issues:** 0 open P0, P1 or P2, and 2 open P3. One P1 and two P3s were fixed during the audit.
- **Top issues:**
  1. The window radio group and the lens tabs have no arrow-key model. In the lab every chip is a separate tab stop.
  2. The horizontal window selector has no affordance beyond the clipped chip at the edge.
- **Next:** carry the selection semantics into Compose (`selectableGroup`, `Role.RadioButton`, `Role.Tab`), then polish.

## Findings

**[P1] The app list did not reconcile with the modeled total (fixed)**
- **Location:** `drain.html`, the apps list. `tools/build.py` `drain_reconcile()` holds the segment values.
- **Category:** Implementation integrity.
- **Impact:** the eight rows summed to 87% of the measured total:
  - seven apps, 905 mAh;
  - unattributed, 142 mAh.

  The reconcile bar's segments, however, included 157 mAh of further apps (33 + 29 + 95). A user adding up the rows would find 13% missing on the screen whose job is to account for every mAh.
- **Standard:** SPEC 1.1 and 6.5, measurement honesty: attribution must reconcile with the measured total.
- **Fix applied:**
  - A "22 more apps" row (157 mAh, 13%, modeled ring, "Each under 3%") sits before the unattributed row.
  - The rows now sum to 1,204 mAh: 905 + 157 + 142.
  - The Compose list keeps the same rule. When a list is truncated, a remainder row always closes it.

**[P3] Selection groups lack a keyboard model**
- **Location:** `drain.html`, `.window-sel` (`role="radiogroup"`) and `.segmented` (`role="tablist"`).
- **Category:** Accessibility.
- **Impact:** a keyboard or switch user reaches each of the nine chips as a separate stop instead of arrowing within a group.
- **Standard:** WAI-ARIA APG radio group and tabs patterns.
- **Recommendation:**
  - Lab: acceptable, since there is no script.
  - Port: `Modifier.selectableGroup()` on the row, plus `Modifier.selectable(role = Role.RadioButton)` or `Role.Tab` per chip. TalkBack then announces position and count.
- **Suggested command:** `/impeccable harden`.

**[P3] Tabs had no tab panel (fixed)**
- **Location:** `drain.html`, lens tabs.
- **Category:** Accessibility.
- **Fix applied:**
  - Each tab has an `id` and `aria-controls="lens-panel"`.
  - The switch and the list are wrapped in `role="tabpanel"`, labelled by the selected tab.

**[P3] Selector buttons were 40 px hit areas (fixed)**
- **Location:** `lab.css`, `.window-sel button`, `.segmented button`.
- **Category:** Accessibility.
- **Fix applied:** a pseudo-element extends the hit area to 48 px vertically. The 4 px padding of `.window-sel` keeps the extension inside the group.

**[P3] Scrollable selector affordance**
- **Location:** `.window-sel` (`overflow-x: auto`, scrollbar hidden).
- **Category:** Responsive.
- **Impact:** at 412 px and 360 px the next chip is visibly cut at the gutter, which signals scrolling. There is no other cue.
- **Recommendation:** keep it. This is the Material pattern for scrollable chip rows. In Compose, use a `LazyRow` with edge content padding so the first chip aligns with the gutter.

## Patterns and systemic issues

- Selection controls (window, lens, target) share one missing keyboard model. Fix them together in the port, not per screen.
- Any truncated list of shares needs a remainder row so the visible parts sum to the whole. This also applies to Now's top drains: there, "See all" is the remainder and the hero states the total.

## Positive findings

- **Expand buttons:**
  - 48 px, with `aria-expanded`;
  - names are specific ("Show Wayline's components").
- **App rows:**
  - 72 px tall;
  - long details truncate with an ellipsis instead of wrapping into the value column.
- **Toggle:** "Include system apps" is a real checkbox with `role="switch"` inside a label, so the whole row is the target.
- **Unattributed row:** it uses the dashed gap icon and the derived mark, so it does not pose as an app.

## Recommended actions

1. **[P3] `/impeccable harden`:** selection semantics for radio and tab groups in the Compose components.
2. **`/impeccable polish`:** final pass.

## Re-check after the critique fixes (2026-11-07)

The critique (`2026-11-07-critique-drain.md`) changed this screen after the audit above. The gates were run again on the changed lab:
- Detector, static: `npx -y impeccable@4.8.0 detect design-lab/ --json` and the plugin launcher both return `[]`.
- Detector, rendered: zero findings at 412×915 and at 360×800.
- Font scale 2.0: Android's non-linear conversion applied to text only, at 412 px. No horizontal overflow, and no clipped text apart from the single-line app bar title. Compose does not clip that title vertically, so it is an artifact of the proxy.
- Type: every size and weight maps to a SPEC 11.3 role (ADR-0023 §3). No 14 px remains.
- Window selector: three presets plus More end at 380 px at 412 px. At 360 px, app names and details wrap instead of being cut off with an ellipsis. The neutral switch is 16.6:1 (light).

The audit score above stands; nothing new rose to P0 or P1.
