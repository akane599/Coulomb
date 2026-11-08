# Animate: Drain (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3).
- Method: impeccable 4.7.1 `animate`. A fresh read-only subagent read Drain and Compare's source,
  `CoulombMotion`, the reduced-motion path and the goldens. Each finding was re-checked against the tree.
- Dispositions describe the code after the fixes.

**What holds.** Nothing moves while idle. Springs match DESIGN.md. Reduced motion follows
`ANIMATOR_DURATION_SCALE` and is observed live. A row never slides from one window to another, and
no row keys are shared between lenses.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | On compact widths, leaving Apps removes "Scale to measured" from above the tabs, so the tabs jump 64 dp under the finger | **Fixed.** The switch sits in the Apps lens's head, under "Include system apps", so a lens switch changes only what is below the tabs. ADR-0036 §24 amended. `DrainMotionTest.theTabsHoldStillOnALensSwitch` pins it. |
| Minor | Inserted rows appear at full opacity while the rows below slide, so text draws over text; Compare's "-none" note jumps while its heading slides | **Fixed.** `rowMotion()` fades inserted rows in on the effects spring (none under reduced motion); the note takes `rowMotion()`. |
| Minor | The Components lens builds its rows in one item, so a reorder jumps | **Fixed.** A head item (caption, coarse note, stacked bar), then one keyed item per component with `rowMotion()`. |
| Minor | A new window or period cuts from the skeleton to content; a fast read flashes the skeleton | **Partly fixed.** Rows fade in, Compare's figures fade in for each pair of periods, and Compare no longer shows the skeleton when it comes back to periods it already shows (optimize gate). Not taken: delaying the skeleton by about 100 ms, because the previous window's figures may not stay under the new choice (SPEC 0.2), so the delay would show a blank instead. |
| Minor | The loading fade differs: Drain fades content up from a blank, Compare spends its fade on the skeleton | **Fixed for Compare** (`FadeInPerPeriods` fades the totals for each pair of periods). **Accepted for Drain:** SPEC 10.11 gives the screen one fade-in on first content, and the blank is that fade's start. |
| Minor | Figures never roll | **Fixed.** The Drain total and Compare's strip figures roll (`rolling = true`); a window change remounts them, so a first value appears still. |
| Minor | Under reduced motion the selection check pops in while the fill crossfades | **Fixed.** The check's alpha runs on the real clock with `crossfadeTo`, like the fill (`Selection.kt`). |
| Nit | Under reduced motion the More sheet and the table sheet snap | **Accepted.** Android's reduced-motion setting allows an instant cut, and Material's sheet has no crossfade. Recorded here. |
| Nit | No motion test in `feature/drain` | **Fixed.** `DrainMotionTest`: the tabs hold still on a lens switch, a reorder slides, and under reduced motion a reorder jumps. |

## Result

No critical finding. The major is fixed and pinned by a test.
