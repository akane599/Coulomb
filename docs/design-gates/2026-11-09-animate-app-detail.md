# Animate: App detail (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `animate`, run by one fresh read-only subagent together with `adapt` and
  `optimize`. It checked the Navigation3 1.2.1 `NavDisplay` signature in the published jar before
  proposing the shared element.

**What holds.** The springs match DESIGN.md (spatial 0.95, effects 1.0; stiffness 1400, 700 and
300). Nothing moves while idle. Reduced motion is read live. The chart's draw-in reads its progress
only in the draw and layer phases. Content fades in once, and predictive back is a seekable fade.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The shared element from an app row (SPEC 11.6) is missing, and it is feasible | **Fixed.** `SharedTransitionLayout` around `NavDisplay(sharedTransitionScope = …)`; `sharedElement(key)` in `:core:designsystem` reads the scope and `LocalNavAnimatedContentScope`; the key `app-icon-<uid>` is on Drain's row tile and the header tile, with the spatial spring. Off in split view and under reduced motion. The on-device look waits for the emulator. |
| Minor | The header figure doesn't roll | **Fixed.** `rolling = true`, as on Drain's total. |
| Minor | Picking another row in split view probably cuts the detail | **Accepted.** One pane changes its content; a cut keeps the list still and matches the reduced-motion rule. To confirm on the emulator. |
| Minor | Crossing the column threshold draws the chart in again | **Accepted.** A resize or fold is rare, and the draw-in is short and plays once per crossing. |
| Minor | Prefetch may start the draw-in off screen | **Accepted.** Prefetch composes only an item or two ahead; the trend is last and short, so most of its draw-in is still seen. |
| Nit | Under reduced motion, navigation cuts rather than crossfades | **Accepted**, as on Drain's sheets: NavDisplay scales its own animation to zero. |
| Nit | No motion test for App detail or the panes | **Accepted.** The rolling figure and draw-in share `NowMotionTest`'s and `DrainMotionTest`'s components; the shared element needs a running navigation and is left to the emulator. |

## Result

No critical finding; the major is fixed.
