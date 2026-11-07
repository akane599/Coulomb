# Audit: App detail (`design-lab/app-detail.html`)

- **Date:** 2026-11-07, phase P3 (SPEC 11.12, 17).
- **Method:** inline technical audit (impeccable 4.7.1 `audit`).
- **Evidence:**
  - detector `npx -y impeccable@4.8.0 detect design-lab/ --json` returned `[]` (exit 0);
  - token contrast;
  - captures at 412 and 360 px in light and dark;
  - markup review.
- **Scope:** static lab with synthetic data. "Halcyon" is an invented app.

## Audit health score

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 4 | Tables now have row headers and accessible names (fixed) |
| 2 | Performance | 4 | Static, no script |
| 3 | Theming | 4 | Component colours come from the data palette; the app icon is synthetic content |
| 4 | Responsive design | 3 | Legend values split across lines at 360 px (fixed); trend chart labels shrink at 360 px |
| 5 | Implementation integrity | 4 | Wakeup evidence, process states and the trend tell one consistent story |
| **Total** | | **19/20** | **Excellent** |

## Implementation integrity verdict

**Pass.** The screen's figures agree with each other and with Now and Drain:
- The app total of 402 mAh is 33% of 1,204 mAh.
- The components sum to 402: 188 + 121 + 74 + 12 + 7.
- The process states also sum to 402: 236 + 98 + 51 + 17.
- The 41 wakeups match the 41 alarm wakeups, the Now insight, and the Timeline event.
- The 14-day trend marks the update that doubled daily use. The figures strip and the actions follow from that evidence.

The provenance mark on the app total is the modeled ring, as SPEC 6 requires for power-model attribution. The detector reports zero findings.

## Executive summary

- **Issues:** 0 P0, 0 P1, 1 P2, 0 open P3.
- **Fixed in this pass:**
  - one P2: the legend wrap;
  - one P3: table semantics.
- **Open issue:** the trend chart's small labels shrink further at 360 px. This is the system-wide chart rule; see the Now audit.
- **Next:** polish after the critique synthesis.

## Findings

**[P2] Legend values split across lines at 360 px (fixed)**
- **Location:** `lab.css` `.legend-list .v`; `app-detail.html` "Where it went".
- **Category:** Responsive.
- **Impact:** "121 mAh" broke into "121 / mAh" beside the two-line "Mobile radio" label, which separated the number from its unit.
- **Fix applied:** `white-space: nowrap` on legend values. Verified at 360 px and 412 px.

**[P2] Trend chart labels shrink at 360 px**
- **Location:** `<!-- chart:app-trend -->` and `tools/build.py` `app_trend()`.
- **Category:** Responsive.
- **Impact:** at 360 px the 10 px gridline labels (150/300/450) and the date labels render at about 8.6 px.
- **Recommendation:** the shared port rule. Compose draws chart text in sp at native size and lets the plot width follow the container.
- **Suggested command:** `/impeccable adapt`.

**[P3] Tables had no row headers or names (fixed)**
- **Location:** the four `.data-table` elements: by process state, wakelocks, alarms, jobs.
- **Category:** Accessibility.
- **Fix applied:**
  - The first cell of every body row is now a row header (`<th scope="row">`), so screen readers announce "Foreground, Time, 52 min".
  - Each table is labelled by its h3 (`aria-labelledby`).
  - The stylesheet now targets `thead th` for column headers, so the rendering is unchanged.

## Patterns and systemic issues

- **Long system identifiers** (wakelock tags, alarm actions, job names) use `<wbr>` break points after separators. They wrap at meaningful boundaries at 360 px instead of overflowing.
- **Port rule:** the Compose port needs the same rule (break after `/`, `:` and `.`). Record it in DESIGN.md.

## Positive findings

- **Monospace by meaning:** identifiers that users may copy or search for are monospace, while everything else uses the UI face.
- **Actions show their state:** "Now unrestricted" and "Set to rare by Coulomb yesterday" sit beside the actions, and the Undo action is reachable (SPEC 13).
- **External link:** "App info" carries the open-in-new icon, so leaving the app is announced visually.

## Recommended actions

1. **[P2] `/impeccable adapt`:** the chart label rule and the identifier break rule in DESIGN.md and the port.
2. **`/impeccable polish`:** final pass.

## Re-check after the critique fixes (2026-11-07)

The critique (`2026-11-07-critique-app-detail.md`) changed this screen after the audit above. The gates were run again on the changed lab:
- Detector, static: `npx -y impeccable@4.8.0 detect design-lab/ --json` and the plugin launcher both return `[]`.
- Detector, rendered: zero findings at 412×915 and at 360×800.
- Font scale 2.0: Android's non-linear conversion applied to text only, at 412 px. No horizontal overflow, and no clipped text apart from the single-line app bar title. Compose does not clip that title vertically, so it is an artifact of the proxy.
- Type: every size and weight maps to a SPEC 11.3 role (ADR-0023 §3). No 14 px remains.
- Header-level provenance marks cover every table and chart. Numbers are joined to their units with no-break spaces, so "121 mAh" no longer splits at 360 px.

The audit score above stands; nothing new rose to P0 or P1.
