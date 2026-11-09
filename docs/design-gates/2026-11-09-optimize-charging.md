# Optimize: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `optimize`, native, in the same agent run as `animate` and `adapt`.

**What holds.** Sections that take only the actions skip the 1 Hz recompositions. Readings are
thinned to about 800 points. The curve table is formatted once per snapshot. The repository reads
incrementally: three short queries per sample.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | Each collector kept its own cache of closed charges' lines, though they never change | **Fixed.** One cache under a mutex for every collector. |
| Minor | The KDoc said two queries per sample; the latest sample is a third | **Fixed.** It says three, as ADR-0046 gate amendment 8 does. |
| Nit | The chart builds its paths in the draw, not `drawWithCache` | Accepted: O(n) at 1 Hz on thinned points is cheap. |
| Nit | Sampling stays at 1 Hz while a table sheet is open | Accepted: the sheet is part of the Charging screen (SPEC 7.3). |
| Nit | The target group has no `key` per choice; thinning re-buckets as readings arrive | Accepted: four choices; a re-bucket changes at most a pixel. |

## Result

No critical or major finding. Both minors are fixed.
