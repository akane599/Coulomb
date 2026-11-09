# Adapt: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `adapt` (native), in the run with `animate` and `optimize`.

## Checked, nothing to change

- Privileges and Settings use the shell's predictive pop.
- Insets: the shell pads the pane; onboarding pads the `safeDrawing` sides.
- The two-column decision uses the capped width and scales with the font size.
- At 200% text everything wraps without clipping.
- Technical values stay left-to-right in RTL.

## Findings and dispositions

| Sev | Screen | Finding | Disposition |
|---|---|---|---|
| Major | Onboarding | Back between steps was commit-only (`BackHandler`), while SPEC 3 and DESIGN.md ask for predictive back; onboarding stands in place of the shell, so it gets none of the shell's handling | **Fixed.** `PredictiveBackHandler`: the step fades and shrinks slightly with the gesture (only the fade under reduced motion). On commit the previous step crossfades in, as the shell's pop does; a cancel returns it (`permissions-back`). At the first step back leaves the app with the system animation. |
| Minor | Privileges | Rotation or a fold between one and two columns reset the scroll | **Fixed.** The scroll states sit above the layout branches. **Accepted:** an open ADB block closes on that change, and one tap reopens it. |
| Minor | Onboarding | The footer spanned the window while the body is capped at 720 dp | **Fixed.** The footer's buttons end with the body's column (`permissions-wide`). |
| Nit | Settings | No RTL or wide golden | **Fixed.** `settings-ar`, `settings-wide`. |

## Result

The major finding is fixed. The predictive back gesture itself is checked on the emulator in the
same run as the Macrobenchmark.
