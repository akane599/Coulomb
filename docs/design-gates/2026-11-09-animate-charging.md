# Animate: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `animate`, native, in the same agent run as `adapt` and `optimize`.

**What holds.** The screen is still at 1 Hz: the power figure does not roll, the wave does not
move and the chart draws in once. A 1 Hz redraw moves only the line's end. The content fades in
once. Reduced motion turns the chart's draw-in and the fade into 150 ms crossfades.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | A dot at the line's end, which DESIGN.md rules out ("the line meets now with no dot") | **Fixed.** Removed. |
| Minor | The projection was drawn outside the draw-in, so the cone stood complete before the line reached it | **Fixed.** It draws inside the reveal. |
| Minor | The time axis's end moved with each second's reading, so the whole curve crept left | **Fixed.** The axis ends on the next 5 minutes (`endMs`). |
| Minor | Under reduced motion the Now/Charging swap was instant: Compose's own transitions end at once at a 0 animator scale | **Fixed.** `CoulombCrossfade` fades the new screen in on the effects spring, or over 150 ms on the real clock. |
| Minor | The skeleton flashed for a read that takes a few milliseconds | **Fixed.** It waits 150 ms, as Health's. |

## Result

No critical finding. The major is fixed and verified in the goldens.
