# Audit: Onboarding, capability step (`design-lab/onboarding.html`)

- **Date:** 2026-11-07, phase P3 (SPEC 11.12, 14, 17).
- **Method:** inline technical audit (impeccable 4.7.1 `audit`).
- **Evidence:**
  - the detector (`npx -y impeccable@4.8.0 detect design-lab/ --json`) returned `[]` with exit code 0;
  - token contrast;
  - captures at 412 and 360 px, light and dark;
  - markup review.
- **Scope:** the lab shows the last onboarding step: the capability report after the Shizuku grants, with the SPEC 14.2 monitor callout. The other steps are P4 work.

## Audit health score

| # | Dimension | Score | Key finding |
|---|-----------|-------|-------------|
| 1 | Accessibility | 4 | Granted and not-requested states have both an icon label and a text difference |
| 2 | Performance | 4 | Static, no script, no charts |
| 3 | Theming | 4 | Status icons use the good and secondary tokens |
| 4 | Responsive design | 4 | Mono permission names wrap cleanly at 360 px; the sticky action bar keeps the primary action in reach |
| 5 | Implementation integrity | 4 | The step states what Coulomb can and cannot see, in the capability terms of SPEC 4 |
| **Total** | | **20/20** | **Excellent** |

## Implementation integrity verdict

**Pass.**
- The lede is honest about a limit: power rails are not exposed on this device, so per-component energy comes from Android's power model.
- Every grant names the permission it stands for.
- WRITE_SECURE_SETTINGS is shown as not requested, and is asked for only when the Action Center is turned on (SPEC 4.1 T1, 13, 14.4).
- The One UI sleeping-apps callout follows SPEC 3.4 and 14.2: it explains why in one sentence and offers a link to battery settings.
- The detector reports zero findings.

## Executive summary

- **Issues:** 0 P0, 0 P1, 0 P2, 2 P3.
- **Next:** polish; design the other onboarding steps in P4 against the same tokens.

## Findings

**[P3] Placeholder destinations**
- **Location:** `onboarding.html`, the Back link and "Open battery settings" (`href="#"`).
- **Category:** Implementation integrity (lab only).
- **Impact:** none in the lab. The real destinations are an Activity result and a system settings intent, which a static page cannot express.
- **Recommendation:** in P4, resolve the battery settings intent against AOSP and One UI before wiring it. Do not guess an intent action (SPEC 0.3).
- **Suggested command:** `/impeccable harden`.

**[P3] Step indicator as a progress bar**
- **Location:** `.onb-steps`, which has `role="progressbar"` with `aria-valuemin="1"`, `aria-valuemax="4"` and `aria-valuenow="4"`.
- **Category:** Accessibility.
- **Impact:** TalkBack reads it as a percentage rather than "step 4 of 4". The visible "Step 4 of 4" label right below compensates.
- **Recommendation:** in Compose, set `stateDescription = "Step 4 of 4"` on the indicator, or merge it with the label node.
- **Suggested command:** `/impeccable clarify`.

## Patterns and systemic issues

None specific to this screen. The 48 px hit-area rule now covers the tonal button in the callout.

## Positive findings

- **Each grant has two names.** A plain-language name sits over the monospace permission constant, so both audiences can read it.
- **Status never rests on colour alone.** Every status icon has an accessible label ("Granted", "Not requested"). The not-requested row adds a "why" line.
- **The primary action stays in reach.** The sticky action bar keeps "Go to Now" within thumb reach above the gesture area.

## Recommended actions

1. **[P3] `/impeccable clarify`:** step indicator state description in the port.
2. **[P3] `/impeccable harden`:** verified settings intents in P4.
3. **`/impeccable polish`:** final pass.

## Re-check after the critique fixes (2026-11-07)

The critique (`2026-11-07-critique-onboarding.md`) changed this screen after the audit above. The gates were run again on the changed lab:
- Detector, static: `npx -y impeccable@4.8.0 detect design-lab/ --json` and the plugin launcher both return `[]`.
- Detector, rendered: zero findings at 412×915 and at 360×800.
- Font scale 2.0: Android's non-linear conversion applied to text only, at 412 px. No horizontal overflow, and no clipped text apart from the single-line app bar title. Compose does not clip that title vertically, so it is an artifact of the proxy.
- Type: every size and weight maps to a SPEC 11.3 role (ADR-0023 §3). No 14 px remains.
- The provenance key's definitions are Body 16/24. After two shortened definitions, the rendered scans find no stragglers.

The audit score above stands; nothing new rose to P0 or P1.
