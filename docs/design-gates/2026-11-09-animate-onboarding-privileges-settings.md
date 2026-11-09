# Animate: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `animate`, native, in one read-only agent run with `adapt` and
  `optimize`. Inputs:
  - SPEC 11's battery rule and motion section;
  - DESIGN.md's motion tokens and PRODUCT.md;
  - the code and the goldens.

## Checked, nothing to change

- Nothing loops: no infinite transition, progress indicator or frame loop.
- Step changes use `CoulombCrossfade`, a 150 ms crossfade under reduced motion.
- The choice group's fill and check follow reduced motion.
- Onboarding crossfades into the shell.
- The stepper is static and mirrors in RTL.

## Findings and dispositions

| Sev | Screen | Finding | Disposition |
|---|---|---|---|
| Minor | Onboarding | The footer and the stepper blink out on each step, inside the crossfade | **Fixed** for the footer: it sits outside the crossfade and stays. **Accepted** for the stepper: it scrolls with the step (lab), and its label changes with the step. |
| Minor | Onboarding | Shizuku stages and the result head swap at once | **Fixed.** Both crossfade in place, keyed on the stage and on the result's kind. |
| Nit | Onboarding | After process death Welcome shows for a frame | **Fixed.** The model's first value is the saved step. |
| Nit | Both | "Show ADB commands" opens with no transition | **Accepted.** The block opens below the button the user tapped, so nothing above it moves. The same toggle appears on every screen. |
| Nit | Settings | No fade-in, unlike the other screens; the save note pushes the rows down | **Fixed** for the fade: `FadeInOnce` once the values are read. **Accepted** for the note: it appears only after a failed write, and TalkBack announces it. |

## Result

Nothing animates while idle. Every finding is fixed or accepted with its reason.
