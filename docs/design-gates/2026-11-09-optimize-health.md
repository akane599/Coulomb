# Optimize: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `optimize`, in the same agent run as `animate` and `adapt`.

**What holds.** Room invalidation drives every read; nothing polls. Flows use
`distinctUntilChanged`, run on IO and share with `WhileSubscribed(5 s)`. Lazy items have keys and
content types. `Sections` is remembered. `FadeInOnce` uses ModulateAlpha. Each chart has its own
layer, so scrolling does not redraw it. The table sheet is lazy.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | Each draw-in frame lays out every label again; the trend overflows the 8-entry text cache | **Fixed.** Every chart's measurer keeps 24 layouts (`LABEL_CACHE`). Moving axes into `drawWithCache` waits for the Macrobenchmark, if frames show a need. |
| Minor | The session curve keeps every 1 Hz reading (about 7,000 for 2 h) | **Fixed.** `thinned` keeps at most about 800: per run, the lowest and highest power and the first gap, with both ends. `ThinnedTest` covers spikes, gaps and order. |
| Minor | A selection rebuilds every section | **Fixed.** The selection is read through a state by the session rows only. |
| Nit | The session's readings and curve labels are rebuilt on recomposition | **Fixed.** Remembered. The table is built only while it is open. |
| Nit | The habits window's start is fixed once per process | **Fixed.** Each collection takes its own start time. The read still trims to 30 days. |

## Result

No critical or major finding.
