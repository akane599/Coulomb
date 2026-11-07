# Audit: Charging (`design-lab/charging.html`)

- **Date:** 2026-11-07, phase P3 (SPEC 11.12, 17).
- **Method:** inline technical audit (impeccable 4.7.1 `audit`).
- **Evidence:**
  - detector `npx -y impeccable@4.8.0 detect design-lab/ --json` returned `[]` (exit 0);
  - token contrast;
  - captures at 412 and 360 px, light and dark;
  - markup review.
- **Scope:** a static lab with synthetic data. The screen is shown during a charge (status bar 19:05).

## Audit health score

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 3 | Target radio group has no roving-focus model (lab has no script); everything else meets AA |
| 2 | Performance | 4 | Static, no script |
| 3 | Theming | 4 | Charging is the accent role: numeral, wavy bar, cone, power area |
| 4 | Responsive design | 3 | Session chart labels shrink at 360 px |
| 5 | Implementation integrity | 4 | The ETA bracket lands on the 80% target line; the hero, curve and ETA agree |
| **Total** | | **18/20** | **Good** |

## Implementation integrity verdict

**Pass.** The figures agree with each other:

| Figure | Value |
| --- | --- |
| Power, from current and voltage | 18.4 W ≈ 4.58 A × 4.02 V |
| Level now | 52% |
| Charging target | 80% |
| ETA to 80% | 22–28 min |
| Bracket on the curve's target line | 19:27–19:33 (19:05 plus 22–28 min) |
| Charger average | 21.0 W, 85% of the 24.6 W shown on Health |

- The wavy level bar ends at 52%, and its tick at 80% matches the selected target.
- No charger-health judgment is invented: the row states the ratio and makes no verdict.
- The detector reports zero findings.

## Executive summary

- **Issues:** 0 P0, 0 P1, 1 P2, 1 P3. One P3 was fixed during the audit.
- **Top open issues:**
  1. Session chart text at 360 px.
  2. The target radio group's keyboard model.
- **Next:** polish.

## Findings

**[P2] Session chart labels shrink at 360 px**
- **Location:** `<!-- chart:charging-curve -->`, `tools/build.py` `charging_curve()`.
- **Category:** Responsive.
- **Impact:** "19:27–19:33", the percentage gridlines and the power labels render at about 8.6–9.5 px.
- **Recommendation:** shared port rule (sp text, plot width from the container).
- **Suggested command:** `/impeccable adapt`.

**[P3] Target group lacks a keyboard model**
- **Location:** `charging.html`, Target `.window-sel` (`role="radiogroup"`).
- **Category:** Accessibility.
- **Standard:** WAI-ARIA APG radio group.
- **Recommendation:** in Compose, use `selectableGroup()` plus `Role.RadioButton`. Same fix as the Drain window selector.
- **Suggested command:** `/impeccable harden`.

**[P3] Target chips were 40 px hit areas (fixed)**
- **Location:** `lab.css`, `.window-sel button`.
- **Category:** Accessibility.
- **Fix applied:** the hit area is now 48 px.

## Patterns and systemic issues

- **One accent meaning.** On this screen accent means "charging" throughout:
  - the 18.4 W numeral;
  - the wavy level bar;
  - the cone and bracket;
  - the power area.
- **Contrast of the accent numeral.** It is Display size (large text), which needs 3:1. It measures 3.98 in light, 7.99 in dark and 8.78 in black.

## Positive findings

- **The ETA is a range with its mark.** "80% in 22–28 min" carries the interval mark. The full-charge time is also a range, not a falsely precise minute.
- **The phase is named, not implied.** The text says "constant current".
- **The switch label states its effect exactly:** "Alert me when it reaches 80%".

## Recommended actions

1. **[P2] `/impeccable adapt`:** shared chart label rule.
2. **[P3] `/impeccable harden`:** selection semantics shared with Drain.
3. **`/impeccable polish`:** final pass.

## Re-check after the critique fixes (2026-11-07)

The critique (`2026-11-07-critique-charging.md`) changed this screen after the audit above. The gates were run again on the changed lab:
- Detector, static: `npx -y impeccable@4.8.0 detect design-lab/ --json` and the plugin launcher both return `[]`.
- Detector, rendered: zero findings at 412×915 and at 360×800.
- Font scale 2.0: Android's non-linear conversion applied to text only, at 412 px. No horizontal overflow, and no clipped text apart from the single-line app bar title. Compose does not clip that title vertically, so it is an artifact of the proxy.
- Type: every size and weight maps to a SPEC 11.3 role (ADR-0023 §3). No 14 px remains.
- Every figure carries its own mark. The neutral switch is 16.0 to 17.6:1.

The audit score above stands; nothing new rose to P0 or P1.
