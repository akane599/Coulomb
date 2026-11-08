# Animate: Now (Compose)

- Date: 2026-11-08, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `animate`, by the same fresh read-only subagent, on the motion code
  (`RollingText`, `DrawIn`, `animatePlacement`, `FadeInOnce`) and SPEC 11.6. `NowMotionTest` checks
  the motion on the test clock: rows slide and settle, rows jump under reduced motion, and a rolling
  figure reads as one text.
- What holds: nothing animates while idle, the springs are 0.95 and 1.0, and the reduced-motion paths
  are crossfades.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | The whole screen fades in again on every tab return, rotation or theme change | **Fixed.** `FadeInOnce` saves that it has run, as `rememberDrawIn` does (ADR-0034 §35). |
| Minor | The hero's current snaps while the same figure in the Live current head rolls | **No change.** The hero's state line is one sentence with an inline mark ("Discharging · 410 mA ●"). The figure that rolls is the head's. Rolling inside the sentence would split it into separate texts again. |
| Nit | On compact widths the live chart draws in below the fold, unseen | **No change.** The draw-in runs once per saved state, so by the next visit the chart shows at once. Starting it on first visibility needs a visibility signal Now doesn't otherwise use. |
| Nit | Rows entering the top three appear at full opacity | **No change.** The first value of anything appears still (ADR-0034 §19). A row that moves to a new rank slides. |

While the motion test was being written, a test-only issue turned up: it read a row's bounds outside
the animated placement. The test tag now sits inside it, so the test measures where the row draws.

## Result

No critical or major finding. One minor is fixed and three items are recorded with reasons.
