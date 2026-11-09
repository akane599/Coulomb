# Animate: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `animate`. One fresh read-only agent ran `animate`, `adapt` and
  `optimize` in turn, over the code and the goldens.

**What holds.** The springs match DESIGN.md (damping 0.95 / 1.0; stiffness 1400 / 700 / 300).
Reduced motion is read live and becomes a 150 ms crossfade. `FadeInOnce` is saved. The hero figure
rolls only when its value changes. The trend's bracket is revealed last. Nothing on Health animates
while idle (SPEC 7.2).

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | The charts draw in again when a session opens beside the list (the one-column list is new) | **Accepted.** The one-column list state is now held above both layouts, and the list scrolls to the chosen row (adapt). The charts that draw in again are then above the viewport, so the replay is not seen. Each layout keeps its own item state, as App detail does on a resize. |
| Minor | The range label shows before its bracket | **Fixed.** Drawn inside the reveal, as on Now's projection scale. |
| Minor | Session and charger rows don't animate a reorder or a new row (SPEC 11.6) | **Fixed.** Rows are lazy items of their own, keyed by id, with `rowMotion()`. It moved to `:core:ui` and Drain uses the same one. The full session list uses it too. |
| Minor | A session beside the list flashes its skeleton on every tap | **Fixed.** The skeleton waits 150 ms. A quicker read goes straight to the content. |
| Nit | The sweep crosses empty axis before the bracket | **Accepted.** One reveal for every chart (ADR-0026). A bracket-specific opening would be a second motion language for one chart. |

## Result

No critical or major finding.
