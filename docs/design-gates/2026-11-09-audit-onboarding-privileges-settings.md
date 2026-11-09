# Audit: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit`, native variant. One fresh read-only agent read the screens,
  their models, the shared `:core:ui` and `:core:designsystem` pieces, the shell wiring, the
  privileged facade and the capability store against SPEC 3, 11, 14.1–14.4 and 15, DESIGN.md and
  ADR-0047, and looked at the goldens.

## Score: 16/20

| Dimension | Score | Key issue |
|---|---|---|
| Accessibility | 3 | No live region on stage changes; capability state read twice; busy buttons tappable |
| Performance | 3 | Small work in composition |
| Theming | 4 | Tokens throughout; dark goldens |
| Responsive | 3 | One edge in the two-column check |
| Anti-patterns | 3 | Onboarding's model outlives the screen; failures not shown |

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| P1 | A hang or stage change is silent for TalkBack (`OnboardingSteps.kt`) | **Fixed.** Polite live region on the stage container. |
| P2 | `OnboardingViewModel` lives on in the activity after onboarding; its catch-up bootstrap could fire later | **Fixed.** The catch-up runs only while the stored flag says onboarding is not done. |
| P2 | The Android 17 line repeats on the hang screen | **Fixed** (see the critique). |
| P2 | The hang state offers no fork or Sui links (SPEC 4.5) | **Fixed.** Both screens show the fork and Sui buttons on a hang or a manager too old. |
| P2 | Capability state read twice (state description plus visible text) | **Fixed.** The state description is gone; the merged row reads the visible word once. |
| P2 | "Checking…" and "Granting…" stay enabled; the probe-failed note has no live region | **Fixed.** Disabled while busy; the note is a polite live region. |
| P2 | A failed or partial bootstrap shows nothing | **Fixed.** "Some grants didn't go through. Use the ADB commands below." (live region), when a call throws or `allGranted` is false. |
| P3 | Two quick taps on Allow request the permission twice | **Fixed** in `PrivilegeController`: a request within 5 s of an open one is ignored, unless flagged as hanging; tested. |
| P3 | The export lambda holds the activity's context | **Fixed.** The model takes the `Uri` and writes through the application's resolver on `Dispatchers.IO`. |
| P3 | Intents fail silently (no browser), no `SecurityException` catch | **Accepted.** Logged; every target is a system screen or a browser URL, which a phone without either cannot use anyway. |
| P3 | The two-column check uses the window, not the capped row | **Fixed.** `min(maxWidth, MaxTwoColumnWidth)`. |
| P3 | `currentTimeMillis` read in composition | **Fixed.** Read on resume; the device value is remembered. The label lookup stays keyed in `remember`. |
| P3 | Settings note outside the toggleable row; a DataStore `IOException` would crash | **Fixed.** Note inside the row; writes catch and log `IOException`. |
| P3 | Privileges and Settings open without a pane title | **Fixed.** `pane = true`. |
| Uncertain | `ACTION_POWER_USAGE_SUMMARY` lands near Never sleeping apps on One UI | **Open** in `docs/questions.md`. |
| Uncertain | `CreateDocument` may drop `.md` on some providers | **Accepted.** The suggested name carries it; the provider decides. |

## Result

The P1 and every P2 are fixed. Each P3 is fixed or accepted with its reason above.
