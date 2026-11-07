# ADR-0025: Reconciling `DESIGN.md` with the lab and the SPEC

- Status: accepted (edits SPEC 11.4 and 11.5)
- Date: 2026-11-07
- Phase: P3

## Context

`DESIGN.md` was derived from the finished design lab (SPEC 11.12 step 4). Writing it down found eleven
places where the lab disagrees with the SPEC or with ADR-0022 and ADR-0023. Two need a decision, because
the lab is right and the SPEC text is not. The other nine are lab deviations from rules that stand.

The lab passed its finish review with seven of eight fixes resolved
(`docs/design-gates/2026-11-07-finish-review-lab.md`), and that review is closed. The lab is a prototype; the
Compose port is the product. So the lab stays as reviewed, and `DESIGN.md` states the rules the port follows.

## Decisions

### 1. Section gap: 24 dp on compact widths (SPEC 11.5)

- SPEC 11.5 said "section gap 32 dp". The lab uses 24 dp on every screen. The finish review asked for all three of Now's top drains to clear the navigation bar at 412 × 915, with the hero still at least 30% of the viewport. At 32 dp the third row sits under the bar.
- The gap is now 24 dp on compact widths and 32 dp on medium and wider. The lab has no medium layout, so nothing argues against the SPEC's 32 there.

### 2. View tabs stay neutral (SPEC 11.4)

- SPEC 11.4's `accentFill` row lists "selected chip or segment". The same section allows the accent "at most once per region".
- Drain's lens (Apps, Components, Wakeups) switches between views of the same data, and the lab marks it up as tabs. The window above it is the region's one copper selection.
- `accentFill` marks a chosen value: a window, a target, a filter chip, the navigation pill. A view tab selects with `surfaceRaised` and a check, in ink.

### 3. Lab deviations the port does not copy

Each rule already holds. `DESIGN.md` states it and marks the lab as not meeting it.

| # | Lab | Rule |
| --- | --- | --- |
| 1 | Row names and mono text use `overflow-wrap: anywhere`, so "Unattributed" splits at 200% | Words break only between words (SPEC 11.3) |
| 2 | Drain's "+22" spills out of its 40 dp tile at 200% | Text fits its container (SPEC 11.3) |
| 3 | Chart labels in fixed SVGs do not scale | Chart text is sp and each chart re-lays out its labels (SPEC 11.8, ADR-0022 §4) |
| 4 | A solid dot marks the current point on Now, Health, Charging and Timeline | Markers use ticks or crosses; the line ends at the "now" rule, and a point estimate is a tick (SPEC 11.8, ADR-0023 §10) |
| 5 | Health's capacity band is a 14% copper fill without an edge | Every band has a solid edge at 3:1 or better (ADR-0022 §4) |
| 6 | Selected navigation labels, window choices, lens segments and the toolbar range go to weight 600 | Weights are fixed per role: selection shows by fill, check or indicator (ADR-0023 §3) |
| 7 | Now's "Open battery protection" is a tonal button, `surfaceRaised` on `surface` | UI parts reach 3:1, so it is outlined, as the onboarding critique decided (SPEC 11.4) |
| 8 | The window group's inner corners are 8 dp; App detail's icon has a 16 dp radius | Radii are 6, 12, 20 and 28: inner corners take 6. App icons are drawn as the system provides them, so no token applies (SPEC 11.5) |
| 9 | The app bar title truncates with an ellipsis | Titles wrap to a second line; layouts reflow, never clip (SPEC 11.3) |

## Consequences

- SPEC 11.5 reads "section gap 24 dp on compact widths, 32 dp on medium and up".
- SPEC 11.4's `accentFill` row names view tabs as neutral.
- P4's per-screen critique and screenshots at font scale 2.0 check items 1–9 in the port. The lab is not edited again.
- The owner's P3 direction review sees these decisions. A different answer there amends this ADR.
