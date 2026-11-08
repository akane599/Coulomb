# Optimize: Drain (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3).
- Method: impeccable 4.7.1 `optimize`. A fresh read-only subagent read Drain, Compare, their view
  models, `DrainReader`, `DrainRepository`, `AppIcons` and the DAOs they observe. Each finding was
  re-checked against the tree.
- Dispositions describe the code after the fixes.

**What holds.** Nothing polls: reports come from Room invalidation, collected with the lifecycle and
shared with `WhileSubscribed(5 s)`. Database reads and icon decoding run on IO. Lists are built once
per report, every lazy item has a stable key and a content type, nothing animates at rest, and the
bars draw without offscreen layers.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Light snapshots trigger full re-reads: two observed queries join `snapshot`, so Room re-runs them on every snapshot write, though ADR-0036 §6 reads again only after heavy snapshots | **Fixed.** `DrainInputs` is a data class that also carries the newest consecutive window's id, and `inputs()` ends in `distinctUntilChanged()`, so a write that changes no input reads nothing. `DrainReaderTest.aLightSnapshotReadsNothingAgain` fails without the dedupe and passes with it. |
| Minor | `compare()` runs on the main thread | **Fixed.** `flowOn(dispatchers.default)` inside `flatMapLatest`. |
| Minor | Wake groups are built 6 times per comparison instead of 2 | **Fixed.** Each period's groups are built once in `compare()`. |
| Minor | Compare flashes its skeleton and loses scroll on return | **Fixed.** The loading state is emitted only when the screen does not already show a comparison of the same periods. |
| Minor | Missing icons are never cached; cached icons still show the stand-in for a frame | **Fixed.** `AppIcons` remembers a miss for 60 s, then asks again, so an app installed again gets its icon without a link to the collector. `peek()` gives a cached icon synchronously, and `rememberAppIcon` starts from it (Drain and Compare). |
| Nit | `scale()` scans the whole list for every row | **Fixed.** `Comparison` and `ComparedWakeGroup` compute each list's scale once. |
| Nit | Kernel subsystems are recomputed in the list builder | **Fixed.** `WakeGroupView` works them out once; the view is remembered per report. |
| Nit | `zone` and `today` defaults are new objects on every recomposition | **Fixed.** Both are remembered and read again with each state, so the pickers still move on past midnight. |

## Result

No critical finding. The major is fixed and pinned by a test that fails without the fix.
