# Audit: App detail (Compose, `:feature:appdetail`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit`, native variant. One fresh read-only agent read the sources and
  checked 22 goldens: light, dark, black, 130% and 200% text, Turkish, Arabic, harden, medium,
  expanded, pane, and the shell's list-detail. No Gradle run, no device.
- Scores are the agent's, on the screen before the fixes below. Decisions are in ADR-0041, "Gate
  decisions".

**Contrast.** Tokens only: no `Color(0x…)` in the module, `DailyBarChart` or `Breakdown`, so the
Drain audit's ratios hold. Secondary text is 6.26:1 light, 7.70:1 dark, 8.47:1 black. Trend bars use
`text-secondary` and today's bar `text-primary`. **Targets.** "View as table" is at least 48 dp, the
App info row 56 dp, the term line extended to 48 dp by Compose.

## Health score: 16/20, Good

| # | Dimension | Score | Key finding |
|---|---|---|---|
| 1 | Accessibility | 3 | Stacked tables at 200% hide which column a value belongs to; the term row is not merged |
| 2 | Performance | 3 | Wake tables built eagerly inside one lazy item |
| 3 | Theming | 4 | Tokens only; light, dark and black all right |
| 4 | Platform conformance | 3 | RTL digit cells and App info misaligned; no pane title |
| 5 | Adaptivity | 3 | Both split panes pad the window's side insets |

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The trend caption misstates how days are filled | **Fixed.** See the critique; EN and TR. |
| Major | Jobs can say "No jobs recorded" above "31 runs, 22 min in all": the table holds only each window's longest holds | **Fixed.** Empty tables say "None among the longest runs Coulomb keeps for this window." and the total still shows (`appdetail-jobs-total`). Not collected has its own line. |
| Minor | Stacked tables lose their column names | **Fixed.** Labelled values (`page-200`). |
| Minor | RTL digit-only cells sit opposite their header | **Fixed.** End-aligned cell boxes (`harden-rtl`, `ar`). |
| Minor | RTL App info label against the icon; tags and notes on different sides | **Fixed.** `weight(1f).wrapContentWidth(Start)`; names and notes share the start. |
| Minor | RTL: the header's mark lands before the figure | **Accepted.** It sits at the paragraph's end in RTL, as on Drain's total, which passed its gate. The figure strip now has no per-figure marks. |
| Minor | The Cached row is not merged | **Fixed.** Every table row is one TalkBack item, the term's definition included. |
| Minor | No `paneTitle` | **Fixed.** The detail pane's title is the app's name. |
| Minor | Split panes pad both side insets | **Fixed.** The list consumes the End insets, the detail the Start insets. The 3-button landscape check waits for the emulator. |
| Minor | A window change keeps the old figures until the new report | **Fixed.** The skeleton shows on a window change (`flatMapLatest` with `onStart`). |
| Minor | Wake tables built eagerly | **Fixed.** Five rows, then "Show all N" / "Show fewer" with state (`appdetail-many-wakelocks`). |
| Minor | No golden of the long app name in the top bar | **Fixed.** `shell-compact-long-title`. |
| Nit | Under Jobs, the not-collected note says "Wakelocks" | **Fixed.** Its own string. |
| Nit | The chip copies Label's weight in code | **Accepted.** The same style as Drain's breakdown chips (`DrainBreakdown`). |
| Nit | `Sections` rebuilt each recomposition; the chart measures its unit twice | **Fixed** for `Sections` (remembered with its inputs). **Accepted** for the chart: it measures in the draw while the bars draw in, and does not draw while idle. |
| Nit | Mono tags break into three lines at medium width | **Fixed.** Break chances (`medium`, `medium-rail`). |

## Result

No critical finding. Both majors are fixed. The split-pane insets in 3-button landscape and a
TalkBack walk of the split wait for the emulator and a device with TalkBack.
