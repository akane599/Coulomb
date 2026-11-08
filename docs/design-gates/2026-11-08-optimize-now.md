# Optimize: Now (Compose)

- Date: 2026-11-08, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `optimize`, by the same fresh read-only subagent, on the composables,
  the charts and the read-model flow.
- What holds: one flow emission per sample (ADR-0034 §25), lifecycle-aware collection, no stacked
  backgrounds, and one layer per row.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | Both charts rebuild their paths, labels and tick lists on every draw | **No change.** A chart redraws on a new sample, at most once a second, and on the frames of its one draw-in. Each sample changes the data the cache would be keyed on, so `drawWithCache` would rebuild just as often. The label measurer caches 24 layouts (audit). |
| Nit | The full-screen fade composites offscreen | **Fixed.** `CompositingStrategy.ModulateAlpha` on `FadeInOnce` (ADR-0034 §35). |

## Result

No critical or major finding.
