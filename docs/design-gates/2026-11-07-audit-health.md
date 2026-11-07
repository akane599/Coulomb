# Audit: Health (`design-lab/health.html`)

- **Date:** 2026-11-07, phase P3 (SPEC 11.12, 17).
- **Method:** inline technical audit (impeccable 4.7.1 `audit`).
- **Evidence:**
  - detector `npx -y impeccable@4.8.0 detect design-lab/ --json` returned `[]` (exit 0);
  - token contrast;
  - captures at 412 and 360 px, light and dark;
  - markup review.
- **Scope:** static lab with synthetic data.

## Audit health score

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 4 | Cross-check table now has row headers and a name (fixed) |
| 2 | Performance | 4 | Static, no script |
| 3 | Theming | 4 | Interval band and bracket in accent, all from tokens |
| 4 | Responsive design | 3 | Capacity scale and trend labels shrink at 360 px |
| 5 | Implementation integrity | 4 | The estimate interval is the same everywhere it appears and is shown against design capacity |
| **Total** | | **19/20** | **Excellent** |

## Implementation integrity verdict

**Pass.** The capacity estimate is consistent wherever it appears:

| Place | Value |
| --- | --- |
| Hero | 4,610 ± 70 mAh |
| Scale bracket | 4,540–4,680 |
| End of the trend | Vertical bracket at the same interval |
| Cross-check | Coulomb 92% |

92% is 4,610 / 5,000.

- **Provenance and sources:**
  - The estimated value carries the interval mark.
  - System and kernel values carry the measured mark with their source named (Android state of health, kernel fuel gauge).
  - The caption says where design capacity comes from (power profile).
- **Wear stress:** the session counts (18 + 9 + 3 = 30) drive share bars of 60%, 30% and 10%.
- **Detector:** zero findings.

## Executive summary

- **Issues:** 0 P0, 0 P1, 1 P2, 1 P3. One P3 was fixed during the audit.
- **Top open issue:** chart text at 360 px. This is the system-wide rule.
- **Next:** polish.

## Findings

**[P2] Scale and trend labels shrink at 360 px**
- **Location:**
  - `<!-- chart:health-scale -->` and `<!-- chart:capacity-trend -->`;
  - `tools/build.py` `health_scale()` and `capacity_trend()`.
- **Category:** Responsive.
- **Impact:** the 10 px tick labels ("4,000 / 80%") and the "Design capacity" label render at about 8.6 px.
- **Recommendation:** shared port rule (sp text, container-width plots).
- **Suggested command:** `/impeccable adapt`.

**[P3] Cross-check table had no row headers or name (fixed)**
- **Location:** `health.html`, Cross-check.
- **Category:** Accessibility.
- **Fix applied:**
  - Source cells are row headers.
  - The table is labelled by its section heading.
  - Rendering is unchanged.

**[P3] "Rename" repeats without context**
- **Location:** `health.html`, Chargers, three `Rename` text buttons.
- **Category:** Accessibility.
- **Impact:** a screen-reader user tabbing through hears "Rename" three times.
- **Recommendation:** in the port, give each button its charger's name as content description ("Rename Desk charger"). In the lab, add `aria-label` when the screen is next touched.
- **Suggested command:** `/impeccable clarify`.

## Patterns and systemic issues

- **A visual section with no visible heading gets a visually hidden one.** "Cycles and age" uses `.visually-hidden`, so heading navigation still lands on it. Keep this pattern in the port with `semantics { heading() }` on an invisible node.

## Positive findings

- **The scale shows the reference.** The bracket sits on a graduated scale that ends at design capacity, so 92% reads as distance from the reference.
- **The trend shows its evidence.** Each ring is one qualifying window, and the band is the 95% interval. The caption says both in one sentence.
- **Charging habits use neutral share bars, not a hue.** Wear stress is a state, not a power component.

## Recommended actions

1. **[P2] `/impeccable adapt`:** shared chart label rule.
2. **[P3] `/impeccable clarify`:** contextual names for repeated row actions.
3. **`/impeccable polish`:** final pass.

## Re-check after the critique fixes (2026-11-07)

The critique (`2026-11-07-critique-health.md`) changed this screen after the audit above. The gates were run again on the changed lab:
- Detector, static: `npx -y impeccable@4.8.0 detect design-lab/ --json` and the plugin launcher both return `[]`.
- Detector, rendered: zero findings at 412×915 and at 360×800.
- Font scale 2.0: Android's non-linear conversion applied to text only, at 412 px. No horizontal overflow, and no clipped text apart from the single-line app bar title. Compose does not clip that title vertically, so it is an artifact of the proxy.
- Type: every size and weight maps to a SPEC 11.3 role (ADR-0023 §3). No 14 px remains.
- Crosses on the interval band are 3.90 to 5.60:1. The figure strips reflow: 3 columns, then 2 + 1.

The audit score above stands; nothing new rose to P0 or P1.
