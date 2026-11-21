# Whole-app audit, run 1

- Date: 2026-11-21, phase P5 (SPEC 11.12, 17: "`/impeccable audit` and `critique` over the whole app,
  repeated until two consecutive runs return nothing above minor").
- Method: impeccable 4.7.1 `audit` (native), run as one independent read-only review split into five
  slices plus the shared code. The reviewer read PRODUCT.md, DESIGN.md, SPEC 10 to 14, the gate files
  and ADRs, about 29,000 lines of UI Kotlin, every `values` and `values-tr` file and 330 of the 350
  goldens. Nothing ran on a device.
- Result: **0 critical, 2 major.** This run does not count towards the two clean runs.

## Scores before the fixes

| Dimension | Score (of 4) |
|---|---|
| Accessibility | 3 |
| Performance | 3 |
| Appearance and theming | 4 |
| Platform conformance | 3 |
| Adaptivity | 3 |
| **Total** | **16 / 20** |

Per screen the Timeline's accessibility was 2 and the widgets' adaptivity 2 to 3.

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | Charging's target note said "There is no alert at the target yet" in every state, though the alarm ships in Settings; SPEC 10.9 asks the selector to link to it. | **Fixed.** The note says whether the alarm is on and "Alerts in Settings" opens Settings. Golden `charging-alarm-on`. |
| 2 | Major | `:core:collect` and `:core:designsystem` had no Turkish strings: the monitor notification, its channel and every "Back" label were English on a Turkish phone. | **Fixed.** Both have `values-tr`. `tools/ci/translations.py` now fails the static stage when a translatable name exists in only one language. |
| 3 | Minor | "View as table" on the Timeline and the night chart lists the bands only: no level, current, signal or temperature. | **Open, minor.** |
| 4 | Minor | Snooze and Dismiss on an insight card have no undo; the app has no snackbar. | **Open, minor.** A snackbar host in the shell is the fix; "Show N more" (critique run 1, #3) removes the need to dismiss a card to reach the next. |
| 5 | Minor | Partial states in Drain, App detail and Health name the fix without a link. | **Open, minor.** |
| 6 | Minor | Links did not share one look. | **Fixed** for the text buttons: a chevron for another screen of Coulomb ("See all" on Health and Timeline, "Open Privileges", "Open this stretch in Drain"), open-in-new for a page of the system's, none for a prompt in place ("Allow notifications"). **Open, minor:** Wakeups rows that open a page look like rows that do not. |
| 7 | Minor | The `WRITE_SECURE_SETTINGS` row says "Asked later" while the Action Center is on. | **Open, minor.** |
| 8 | Minor | A failed save in Settings was one caption at the top of a long page. | **Fixed.** The line is brought into view. |
| 9 | Minor | Turkish used several words for one thing (wakelock, insights, level, count, "Open Privileges"). | **Fixed.** "Uyanık tutma(lar)", "İçgörüler", "Seviye", "Sayı", "Yetkiler’i aç" everywhere. |
| 10 | Minor | `startedAt` exists twice (Health, Night Report); the wake page names a night in the short form. | **Open, minor.** |
| 11 | Minor | An evidence period a day long read "Sat 2:20 PM – 2:20 PM". | **Fixed.** Ends on different days each name their day. |
| 12 | Minor | Timeline at 200%: the chosen range is cut at the toolbar's edge; "9:09 / AM" breaks. | **Open, minor.** |
| 13 | Minor | The night chart's caption said "5.7 times an hour" where the Timeline says "about 6", and explained an outlined span for one track only. | **Fixed.** The night page uses the Timeline's sentence and its caption for every track. |
| 14 | Minor | No glossary terms on the night page and the wake page. | **Open, minor.** |
| 15 | Minor | Now's three app rows did nothing; nothing says "as of" when the collector has stopped. | **Fixed** for the rows: they open the app's detail under Drain, on the same window. **Open, minor:** the reading's age. |
| 16 | Minor | Pull to refresh is the only way to ask for a snapshot. | **Open, minor.** Needs a control a keyboard and TalkBack can reach; planned with P6's expanded and DeX work. |
| 17 | Minor | An insight or digest notification opened the app where it was left. | **Fixed.** Both open Now. |
| 18 | Minor | The rail is 80 dp wide at any text size. | **Open, minor.** Not rendered yet: a 200% and a Turkish rail golden come with P6's expanded work. |
| 19 | Minor | Screen titles stay Title on expanded widths (SPEC 11.3 gives Headline). | **Open, minor**, with P6's expanded work. |
| 20 | Minor | "38 wakeup / alarms" splits at 200% in the Wakeups lens. | **Open, minor.** |
| 21 | Minor | A group heading's `heading()` sits on a row whose child is the term button; `InlineTable` rows lose "Explain provenance". | **Open, minor.** |
| 22 | Minor | Widgets at their smallest sizes drop the line that says what the figure is. | **Open, minor.** |
| 23 | Minor | "Re-run probes" sat at the end of a long page. | **Fixed.** It is the matrix's own action, at its head. |
| 24 | Minor | Health's trend table wraps "95% interval" cells at 100% text. | **Open, minor.** |
| 25 | Minor | On expanded widths capped columns align differently per screen. | **Open, minor**, with P6's expanded work. |
| 26 | Minor | Right to left, the Timeline's chart mirrors time against ADR-0034 §12. | **Open, minor.** |
| 27 | Minor | Package names and Doze keys are not in Mono in previews; the chooser's label cannot wrap. | **Open, minor.** |
| 28 | Minor | Goldens missing for several states. | **Partly fixed**: `charging-alarm-on`, `charging-insight`, `charging-insight-tr-dark`, `now-insights-more`. The rail, the wake page as a pane, Compare on expanded and the dialogs are **open, minor**. |
| 29 | Nit | Health's dialogs used raw corner radii. | **Fixed.** Tokens. |
| 30 | Nit | The button under a log entry's undo command read "Copy command". | **Fixed.** "Copy undo command". |
| 31 | Nit | The applied profile's own row said "Return to stock first." | **Fixed.** It says "In force now." |
| 32 | Nit | Icon-only buttons had no tooltip. | **Fixed.** Every icon button shows its name on a long press or a resting pointer. |
| 33 | Nit | Clock ranges differ between Now and Charging; "Calibrating" in Headline. | **Open, nit.** |
| 34 | Nit | A short choice ("Off") was about 42 dp wide. | **Fixed.** 48 dp at least. |

Recorded decisions re-raised by the review: none.

## Not verifiable from source and goldens

TalkBack traversal, frame rate, predictive back, insets, widgets on the One UI launcher, the wheel on
DeX, the rail's labels at 200% and in Turkish, channel names after a language change, true black on
a screen. These stay "pending real device" or with P6.
