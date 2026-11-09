# Optimize: App detail (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `optimize`, in the same subagent run as `animate`.

**What holds.** Nothing polls. Data comes from Room invalidation through Drain's shared inputs, with
`flatMapLatest`, a deduplicated scale and `WhileSubscribed(5 s)`; reads run on IO. `AppDetailView`
is built once and remembered. Every lazy item has a key and a content type. `FadeInOnce` uses
ModulateAlpha with no offscreen buffer.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Wakelock and job lists have no cap and compose inside one lazy item | **Fixed.** Five rows, then "Show all N" (SPEC 11.8); the full list composes only when asked (`appdetail-many-wakelocks`). |
| Minor | An icon or sheet change rebuilds `Sections`; `peekIcon` forces one extra recomposition | **Fixed** for `Sections` (remembered with its inputs). **Accepted** for the peek: one recomposition on open. |
| Minor | `DailyBarChart` measures and formats inside `Canvas` | **Accepted.** It draws during its draw-in and on scroll only; the Still Screen Rule holds. `drawWithCache` can come with the Macrobenchmark if frames show it. |
| Minor | With both panes, the Drain report is built twice per heavy snapshot | **Accepted.** One build per pane per heavy snapshot, read on IO; sharing it would couple the two view models. |
| Minor | The chart's bars and summary are rebuilt each recomposition | **Fixed.** Both remembered with the scale and formats. |
| Nit | Rows, the spoken string and the chip style rebuilt each composition; the shell paints its background twice | **Accepted.** Small, and inside items that recompose only on data changes. |

## Result

No critical finding; the major is fixed.
