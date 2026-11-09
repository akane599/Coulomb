# Critique: App detail (Compose, `:feature:appdetail`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, design review only; the detector does not apply to a native
  screen. One fresh read-only agent read SPEC 1.2, 6.5–6.7, 10.1–10.4, 10.10–10.11 and 11,
  PRODUCT.md, ADR-0026, ADR-0041 with its amendment, the lab page, the sources and strings in both
  languages, every `appdetail-*` golden and `shell-expanded-list-detail`.
- Scores are the agent's, on the screen before the fixes below. Decisions are in ADR-0041, "Gate
  decisions".

## Score: 27/40 (68%), Acceptable

| # | Heuristic | Score | Issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | In list-detail the list does not show which app is open |
| 2 | Match with the real world | 2 | The trend caption describes the old daily method; "Screen time" heads foreground time |
| 3 | User control and freedom | 3 | "Change it on Drain" cannot be tapped |
| 4 | Consistency and standards | 2 | Unlike Drain: no glossary heads, a provenance mark per figure |
| 5 | Error prevention | 3 | At 200% the figures lose their column names ("31  22 min") |
| 6 | Recognition rather than recall | 3 | Stacked rows make you recall the column order |
| 7 | Flexibility and efficiency | 3 | The window can't be changed here (deliberate) |
| 8 | Aesthetic and minimalist design | 3 | Mono tags break mid-word; the expanded columns are unbalanced |
| 9 | Error recovery | 3 | Partial, no-use and empty states give no next step |
| 10 | Help and documentation | 2 | Only "Cached" is a glossary term |

**Design specificity.** Built for this product: the Modeled ring on the Display numeral, data hues
only in the component bar, neutral trend bars, Mono tags, tables set like a timetable. The figure is
the Drain row's, and the caption names its denominator. The gap until P5 brings the story: the page
goes from a number straight to evidence, with no sentence of meaning.

**Strengths.** "Not recorded" is never shown as 0. A lower bound gets a note. Days with no data are
gaps, counted in the caption and marked in the table. At 200% the page reflows without clipping.
Both charts have a semantics action and a visible "View as table".

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The trend caption says values go by the day each window ended; since d4141f3 a window is shared among its days | **Fixed.** "From Android’s model. A stretch that runs past midnight is split between its days." EN and TR. All trend goldens. |
| Major | Stacked rows (200% text) drop every column name but the first | **Fixed.** Each value is led by its column's name in `text-secondary` ("Time 9 min  Count 41"); TalkBack already read them (`page-200`, `harden-200`). |
| Major | In list-detail, Drain's list shows no selected row | **Fixed.** The open app's row sits on `surface-raised` with selected semantics (`AppNavigator.openApp`, `AppDrainRow(selected)`; `shell-expanded-list-detail`). DESIGN.md, Rows. |
| Major | Wakelocks and Jobs heads are not glossary terms | **Fixed.** "Partial wakelocks" and "Jobs" are terms with definitions, EN and TR. |
| Minor | Process-state, wakelock and job rows have no share bar | **Accepted.** Those rows are tables, not ranked share lists: SPEC 10.4 gives them figures and the header already gives the app's share. A bar per table row would repeat the figure. |
| Minor | RTL: figure cells misalign with their headers; App info and the share caption sit wrong | **Fixed.** A figure cell is an end-aligned box, so digits sit under their header in either direction; the App info label takes the start (`ar`, `harden-rtl`). The header's mark stays at the RTL end, as Drain's total does. |
| Minor | Mono tags break mid-word | **Fixed.** `breakable` moved to `:core:ui`: break chances after `.` `/` `_` `:` in long runs, then between camel humps in words of 16+ characters (`IdentifiersTest`; `page`, `medium`). |
| Minor | "Network and screen time" heads foreground-activity time | **Fixed.** "Network and foreground time" (SPEC 10.4 item 6). |
| Minor | Network marks each figure and not the heading | **Fixed.** One Measured mark on the heading once any figure was recorded; none when all read "Not recorded" (SPEC 10.1). |
| Minor | Partial and empty states name no next step | **Fixed in part.** Empty states are per window; no-use points to Small apps on Drain; a partly-off breakdown says so. **Accepted:** the Settings link waits for the minimal Settings screen in P4, and "couldn't split" has no single reason to give. |
| Nit | "Change it on Drain" has nothing to tap | **Accepted.** Drain is one back or one pane away; a link would duplicate the window's single home (ADR-0041). |
| Nit | Expanded: the left column ends early | **Accepted.** The order follows SPEC 10.4; moving Network breaks it. |
| Nit | The "mAh" header repeats in every cell | **Fixed.** Bare figures under the header. |
| Nit | The alarm note explains a table the user never sees | **Fixed.** "Counted from its wakeup alarms. Android keeps one total per app, so there’s no list of alarms." |
| Nit | "Couldn't split" and "off" use Body; sibling notes use Caption | **Accepted.** Drain's rule: a line that stands in for content is Body; a note that qualifies a figure is Caption. |
| Nit | "Other 7.0 mAh" beside whole numbers | **Accepted.** The Formats rule: one decimal under 10 mAh, the same on every screen. |
| Nit | Today's partial bar is the darkest | **Accepted.** It marks today, as on Now; the axis labels it "Today". |

**Deliberate, not flagged:** no alarms table, the window as a note, process states in mAh only, no
story, Act card or actions until P5.

## Result

No critical finding. All four majors are fixed and verified in the goldens. The accepted minors and
nits are recorded above with their reasons.
