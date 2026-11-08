# Audit: Drain (Compose, `:feature:drain`)

- Date: 2026-11-08, phase P4 (SPEC 11.12, 17). Dispositions recorded 2026-11-09.
- Method: impeccable 4.7.1 `audit`, native. One fresh read-only agent worked from the source and
  the 27 goldens of the time.
  - Contrast was computed from `ColorTokens.kt`. Secondary text is 6.26:1 on the light background,
    7.70:1 dark, 8.47:1 true black and 5.48:1 on `surfaceRaised`. The data hues are 3.3-3.7:1 on
    the light background. All pass.
  - Touch targets: window choices and tabs are 40 dp plus 2 × 4 dp of slack inside the
    selectable, text buttons at least 48 dp, switch rows 56 dp, rows at least 56 dp.
  - 200% text: no clipping in `apps-page-200`, `harden-200`, `wakeups-200`.
- Scores are the agent's, on the screen before the fixes below. Decisions are in ADR-0036 §17-33.

## Health score: 13/20, Acceptable

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | TalkBack focus lost on expand and "Show all" |
| 2 | Performance | 2 | The whole screen is built at once; "Show all" builds every row |
| 3 | Theming | 3 | Mono unused for identifiers |
| 4 | Platform conformance | 3 | The mark's gap flips in RTL; the sheet closes with no exit motion |
| 5 | Adaptivity | 2 | The SPEC 10.10 two-column layout is missing |

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The previous window's figures show under the new window | **Fixed.** Skeleton until the new report (§19). Golden `drain-stale`. |
| Major | No partial or "not collected" state (SPEC 10.11, ADR-0028 §3) | **Fixed.** Now's privilege issue block under the selector; wake groups carry their sources' coverage and say "Not collected" (§21, §22). Goldens `drain-issue`, `drain-issue-empty`, `drain-wakeups-partial`, `drain-wakeups-not-collected`. |
| Major | SPEC 10.10 layouts missing | **Fixed.** Two columns once both fit (§23). The list-detail pane comes with App detail. |
| Major | Long lists built at once | **Fixed.** One lazy list with stable keys and content types; each wake group has one mark on its heading instead of one per row (§23). |
| Major | Share bars missing | **Fixed** (§20, §31). |
| Not scored | Component chips and top apps per component | **With S4** (ADR-0035, §33). |
| Minor | TalkBack focus lost on expand and collapse | **Fixed in code.** The remainder is an accordion whose row stays put, with state and expand/collapse actions; "Show all" stays the same item (§26). The TalkBack walk on the API 37 AVD is still to run, batched with the inset check. |
| Minor | Mono unused for identifiers | **Fixed.** |
| Minor | No "View as table" on the bars | **Fixed.** Semantics action and table sheet (§25). |
| Minor | RTL: mark touches its figure; captions misaligned | **Fixed.** Inline marks take the paragraph's direction; prose and captions fill the width. Goldens `drain-apps-ar`, `drain-harden-rtl`. |
| Minor | Insets on rail widths (shared with Now) | **Fixed in code.** The shell pads `safeDrawing` horizontally and at the bottom. The check on the API 37 AVD with 3-button navigation in landscape is still to run. |
| Minor | Golden gaps | **Fixed.** New goldens: `drain-range`, `drain-reconcile-table`, `drain-components-table`, `drain-components-{tr,130,black,expanded}`, `drain-wakeups-{tr,130,black,expanded,tr-200}`, `drain-apps-tr-200`. |
| Minor | The sheet vanishes without its exit motion | **Fixed** (§27). |
| Nit | Recomputed views and drawing allocations | **Fixed.** Views and segments are remembered, the filter left the draw pass, `DrainActions` is remembered. |
| Nit | Duplicated bar sizes; private icon size | **Fixed.** Bar sizes live in `DrainBars.kt`; `rememberAppIcon` in `:core:ui` loads at the row's own size. |
| Nit | The hero reads "Measured" twice | **Fixed.** The hero mark is hidden from TalkBack; the caption says it. |
| Nit | "Ranked by time held" is wrong for counts | **Fixed.** |
| Nit | `caption.copy(fontWeight = SemiBold)` | **Fixed.** Caption emphasis is a type role (§29). |

**Not flagged, because ADRs decided them:** ADR-0036 §7-16; ADR-0034 §11, §12, §15 (for Now) and
§29; the fixed 12 dp glyphs.

## Result

Every finding is fixed in code. Two checks wait for the next emulator session: the TalkBack walk
and the insets in 3-button landscape.
