# ADR-0036: How Drain reads a span

- Status: accepted
- Date: 2026-11-08
- Phase: P4

## Context

Drain (SPEC 10.3) shows a span chosen by the window selector: Since unplugged, Today, Last 24 h,
and from its More sheet, Last night and a custom range of days. ADR-0032 §3 stores a REFERENCE
window only for the automatic references and SESSION windows only for closed sessions. Last 24 h,
Last night and custom ranges have no stored window of their own. The collector stores CONSECUTIVE
windows between every pair of heavy snapshots and keeps them 30 days (ADR-0021).

These decisions add a read layer and three queries over the existing schema; nothing new is
stored. Each has one line of reason (ADR-0027).

## Decisions

1. **A span is two heavy snapshots and, if one is stored, the window between them.** Sums over the
   span come from the chain of CONSECUTIVE windows that end after the first snapshot and no later
   than the second. The queries run as SQL aggregates (`WindowDao.chainTotals`, `chainUids`,
   `chainWakes` and `chainStat`).
   - R: consecutive windows already tile the timeline. Summing them is exact for counters, and
     the database does it without loading rows.
2. **The spans' ends:**
   - Since unplugged: the window Now already shows (ADR-0032 §3). Now and Drain read it through the
     same `DrainReader`, so they never disagree.
   - Today: the TODAY reference window. Before one is stored, from the reference's snapshot to the
     latest heavy one.
   - Last 24 h: from the first heavy snapshot at or after 24 h before the latest one, to the latest.
   - Last night: the longest closed screen-off session of at least an hour that ended within 24 h of
     the latest heavy snapshot. The hour is SPEC 10.7's threshold for screen-off sessions.
   - Days: from the first heavy snapshot at or after the first day's local midnight, to the first
     at or after the midnight that ends the last day, or the latest. A range starts at most 30 days
     back.
   - R: every end is a stored snapshot, so a span's counters are exact differences. Wall time only
     picks the snapshot; order stays by boot and elapsed time (SPEC 7.8).
3. **The measured total is the stored window's own when every window in its chain started on
   battery.** Otherwise it is the sum of the chain's discharge windows: those whose first snapshot
   reports `plugType = 0`.
   - The report says the span held a charge, and Drain says the total counts only the time on
     battery.
   - The total is Measured when every summed window was read by the charge counter, else
     Estimated (ADR-0018).
   - R: plug changes take heavy snapshots (SPEC 7.2), so no window straddles one. A charge's
     negative change would otherwise cancel out drain.
4. **The apps come from the stored window's detail rows when it has them.** Otherwise each app's
   figure is the sum of its consecutive windows' rows, and Drain marks the figures as lower bounds.
   - Screen sessions store no detail, so Last night always reads the chain.
   - R: each consecutive window folds UIDs under 0.1 mAh into the remainder (ADR-0018), so a sum
     over windows can miss an app's small parts but never adds any.
5. **A wakelock's screen-off share** is its time in wholly screen-off windows, divided by its time
   in wholly screen-off or wholly screen-on windows. Without either kind it has no share.
   - R: screen changes take heavy snapshots, so most windows are one or the other; a mixed window's
     split is unknown, and guessing it would be a model presented as a measurement.
6. **A report is read again after every heavy snapshot.** The signal is a new consecutive window.
   - R: every span ends at the latest heavy snapshot or before; nothing between two snapshots
     changes a report.

## The screen (added with the Drain UI)

7. **The chosen window lives in memory, shared by Now and Drain (`ChosenWindow`).** Now's "See all"
   chooses Since unplugged; the app starts there again after a restart.
   - R: a window is a question about the present, so a choice from last week should not greet the
     user; no stored setting means nothing to migrate.
8. **An app gets its own row at 3% of the total or more, and at least three apps get rows.** The rest
   fold into one remainder row ("5 more apps", "Each under 3%") that opens in place, with "Show
   fewer" to close it.
   - R: the lab's threshold; three rows keep the list useful in a window where no app stands out.
9. **The power no app was charged for is a row of its own, "The phone itself", ranked by size and
   never folded.** Its tile carries the phone glyph.
   - R: SPEC 6.5's friendly system rows; idle and standby are often the largest item, and an app
     initial would misname it.
10. **Hiding system apps moves them into the remainder, as "N system apps hidden", so the rows still
    add up.**
    - R: SPEC 11.8: visible rows sum to the whole; hiding must not shrink the total.
11. **The unattributed row shows only above 10% of measured; the reconcile bar always shows the gap.**
    - R: SPEC 6.5 sets the row's threshold; the bar keeps a smaller gap visible.
12. **"Scale to measured" shows only when the window has a measured and a modeled total.** Scaled
    figures carry the Derived mark, and the unattributed row goes.
    - R: SPEC 6.5; without both totals there is nothing to scale by.
13. **When the model counts more than the battery measured, a sentence replaces the reconcile bar.**
    - R: there is no gap to draw, and a full bar would hide the disagreement.
14. **Components: every hued component in rank order, then the neutral ones as one segment at the
    bar's end.** Ambient display takes the screen hue and calls take the radio hue. The list keeps
    each component, and a note says when Android named only some of them.
    - R: DESIGN.md has eight hues; scattered grey segments read as one broken bar.
15. **Wakeups: four groups, the top five each, then "Show all".** Kernel wakelocks fall back to S9
    wakeup sources and wakeup reasons to S27 resume causes, never both. Names and counted facts wrap
    at any text size; only a wakelock's tag is cut at two lines.
    - R: the sources overlap (`WakeKind`); a tag is an identifier, the facts are the measurement.
16. **A custom range is whole days within the last 30, picked in Material's range picker; one day is
    a range of one.**
    - R: consecutive windows are kept 30 days (ADR-0021); Material's picker is the platform's.

## Critique and audit fixes (added 2026-11-08)

The Drain critique and audit (`docs/design-gates/2026-11-08-critique-drain.md`, `-audit-drain.md`)
found figures that claimed more than the data holds. These decisions answer them.

17. **An Estimated drain is a range.** Its half-width is one level step for each stretch of
    level-based windows (`LevelEstimate`). A stretch is a run of consecutive discharge windows read
    from the level; a counter window, a window on the charger or one without a value ends it. The
    step is the level windows' mAh per point. A stored level-based window gives way to its chain
    when the chain covers it within a minute and measured every window, so only the windows the
    counter could not read add to the range. When Estimated:
    - the header shows "low – high mAh" in Headline, with the Estimated mark;
    - shares divide by the modeled total, and a sentence replaces the reconcile bar;
    - the unattributed row and "Scale to measured" go.
    - R: levels are whole points, so each stretch is good to one point either way. Consecutive
      windows share their ends, so a stretch adds one error, not one per window. A share of a
      range would be a range too, and SPEC 1.2 forbids a precise-looking figure that isn't.
18. **"Show system apps" and "Scale to measured" are stored settings** (Proto DataStore in
    `:core:datastore`), Off by default as SPEC 14.4 says. ADR-0035's "Component breakdown" joins
    them there.
    - R: SPEC 14.4 lists them as settings; a setting survives a restart and the Settings screen
      reads the same store.
19. **While a newly chosen window loads, the body under the selector is the skeleton.**
    - R: the previous window's figures must never sit under the new choice (SPEC 0.2).
20. **The components caption names its share base, and each row has a share bar of that base.**
    - R: a percentage with no stated base reads as a share of the measured drain.
21. **A privilege issue shows under the selector, in Now's block (`PrivilegeIssueBlock`).** With an
    issue and no report, the empty text, which promises the next snapshot, does not show.
    - R: SPEC 10.11's partial state. No snapshot comes until the issue is fixed, so the promise
      would be false.
22. **Wake records carry their sources' coverage (`WakeCoverage`), and no missing source shows as
    zero** (ADR-0028 §3).
    - S5's records count as collected for a window that kept its detail and has a modeled total.
      The modeled total needs S5's device section at both ends. S9 and S27 take the window's
      `kernelCoverage`, and pruned records count as never collected.
    - Over a span, a source is whole when every window had it whole and none when no window had
      it; anything between is partial.
    - Every group is listed. An empty group says "None recorded" where a source covered the
      window, and "Not collected" where none did. A partly covered group says its counts may be
      higher. When nothing was collected at all, one line says so.
    - Kernel names and wakelock tags are set in Mono (DESIGN.md). The caption says that records
      with no time held rank by count.
    - R: S5's wake rows and its device section come from the same dump, so the modeled total
      stands in for "S5 read at both ends" without a schema change.
23. **Layout: one list on compact widths, two columns once both fit.** Two columns need a window
    of Medium or wider with at least 2 × 280 dp of content between the gutters. Narrower Medium
    windows, for example 700 dp beside the rail, stay one column. The selector and the issue block
    span both columns and do not scroll. The window's note and total scroll in a column at 42%
    width, the lens list takes 58%, and content stops at 1200 dp.
    - Every list is lazy. Each item has a stable key, so rows keep their place and state as the
      choice changes. Rows move with `animateItem` placement, which reduced motion turns off.
    - The list–detail pane (`ListDetailPaneScaffold`, SPEC 10.1) arrives with App detail.
    - R: 280 dp is what a row's name, figure and share need without wrapping at 100% text. Below
      that, two columns would wrap every row, and one column reads better. A lazy list composes
      only what is on screen, so a long remainder costs nothing until it is opened (SPEC 16.4).
24. **"Scale to measured" sits at the top of the Apps lens, under "Include system apps".**
    - R: it changes the apps' figures, and Components and Wakeups do not scale. Amended
      2026-11-09 (animate gate): it first sat under the reconcile bar, above the tabs, so
      switching lenses moved the tabs 64 dp under the finger.
25. **The reconcile and component bars carry "View as table" as a semantics action, without a
    visible button.** The action opens a table sheet with the bar's rows (`DrainTable.kt`), with a
    provenance mark per row or for the whole table (`DataTable`).
    - R: ADR-0023 §5 asks for a visible button under every evidence chart. These two are summary
      bars, not evidence charts: their values are printed as text right under them (the key) or
      as the ranked list below. A button under each would repeat what the screen already shows,
      while a TalkBack user still needs the values as a table.
26. **The remainder opens and closes in place as an accordion.** The "N more apps" row is always
    present: its members follow it when open, and "Show fewer" ends the run. The row says Expanded
    or Collapsed and offers expand or collapse to TalkBack. When unnamed UIDs add to the remainder,
    its name reads "N more apps and small ones". Hidden system apps and small apps carry a plain
    group stand-in (`IconStandIn.Group`) instead of the tappable "+N" tile.
    - R: Compose focuses clickables reliably only outside touch mode, so moving TalkBack focus with
      a `FocusRequester` is unreliable. A row that stays where it is keeps the focus without it.
      The count named only the listed apps and so read low.
27. **The More sheet closes with its exit motion** before the chosen window or the range dialog
    takes over (`SheetState.hide()`, then the next step).
28. **The note ends with the time of its data** ("as of 14:05"), the end of the span's last
    window, and the screen refreshes on a pull, as Now does. A pull asks the collector for a
    snapshot and waits up to 5 s for the next report.
    - R: a window ends at the latest heavy snapshot, which can be well before now.
29. **Caption emphasis (12/16, 600) is a type role** for the emphasised figure in chart text and
    keys (DESIGN.md), instead of a weight copied onto Caption in code.
30. **An Estimated total with no level range says that the estimate is too rough to name the
    unattributed part,** instead of printing a gap whose error exceeds it.
31. **Every wake row has a share bar.** Rows that keep time show the time held as a share of the
    window. Rows that keep only counts, resume causes and alarms, show a share of their group's
    count. The caption says which.
    - R: SPEC 10.1. Time held over the window is a whole that times held never exceed. Times of
      different records overlap, so they are never shown as shares of their sum.
32. **Kernel wakelocks sit under subsystem sub-headings, and known records carry a plain-language
    line** (`WakeDictionary` in `:core:analytics`).
    - A record's subsystem comes from words in its name that name one part unambiguously: IPA and
      rmnet for the mobile network, `wlan`, `bluetooth`, `gnss`, `nfc`, input devices (touch
      panels, pens, keys and every `/dev/input/eventN`), `battery`, Android's own suspend blockers,
      the kernel's alarm timer and `[timerfd]`. Everything else is Other.
    - The group still shows its top five records by rank. They sit under their subsystems, each
      subsystem ranked by its longest record, so opening the group adds rows to sections and new
      sections only at the end. No time is summed per subsystem, because holds overlap.
    - A line shows only for names whose source pins them down, each cited to AOSP or the Android
      common kernel in `WakeDictionary`. That covers Android's suspend blockers, the sensor
      service, the alarm timer, timerfd and input device sources, the alarm manager, job
      scheduler and sync manager wakelocks, abandoned suspends, and the interrupts batterystats
      records. Resume causes from the kernel log get none: their stored names do not say whether
      they are interrupts. Vendor names get no line rather than a guess.
    - The dictionary had an independent review before the gate (AGENTS.md). It corrected the
      Broadcasts blocker, which also covers the charging sound, and "Last active Wakeup Source",
      which names the last source active when none still is.
    - R: SPEC 10.3 asks for subsystem groups and a dictionary "when it knows" a name. A wrong line
      would mislead more than a missing one (SPEC 0.2).
33. **Component chips on app rows and top apps per component arrive with S4** (ADR-0035), the
    source that splits an app's power by component. Until then app rows do not expand.
    - R: S5 gives each app one total. Chips built from it would split nothing.
34. **Each wake group is read against the time its counter ran** (`WakeBasis`), which the group
    states under its heading ("Counted only on battery with the screen off: 3 h 20 min").
    - Batterystats counts partial wakelocks, kernel wakelocks and wakeup alarms only on battery
      with the screen off (`mOnBatteryScreenOffTimeBase`), and wakeup reasons whenever on battery
      (`mOnBatteryTimeBase`). The citations are in `WakeKind.basis`. S9 and S27 are the kernel's
      own counters and run all the time.
    - A timed row's bar is its time held as a share of that time: the screen-off time on battery,
      the time on battery, or the whole window. It replaces §31's "share of the window".
    - Records counted only with the screen off have no screen-off share. It would always be whole,
      and printing "100% held with the screen off" would suggest the counter could have said less.
    - The report gains the span's time on battery and its screen-off part, summed over the windows
      that started unplugged (ADR-0038 §3).
    - R: found while choosing Compare's per-hour base (ADR-0038). A window that spent hours on the
      charger or with the screen on made every S5 wake bar look short. SPEC 0.2 puts measurement
      honesty above layout.
35. **The time on battery and its screen-off part are batterystats' own clocks**, read from the same
    windows as the figures they divide.
    - S5's `bt` line carries the battery realtime and the screen-off realtime that its counters run
      on: `mOnBatteryTimeBase` and `mOnBatteryScreenOffTimeBase` (`BatteryStatsImpl`
      android-16.0.0_r4 L13946-13958, android-17.0.0_r1 L13543-13555; dumped by `BatteryStats`
      16 L4288, 17 L4275-4318). Both parsers keep them, and each window stores them as device rows
      (`DeviceKeys.BATTERY_REALTIME_MS`, `SCREEN_OFF_REALTIME_MS`).
    - A span read from one stored window's rows takes them from that window; a span read from the
      chain takes them from the chain. So the base always covers the windows its numerators came
      from. §34 summed the wall-clock length of the windows that started unplugged, which counted a
      window that was plugged in partway and missed the screen-on time inside a screen-off window.
    - Only records counted whenever on battery (wakeup reasons) keep a screen-off share. A root
      record's counter runs all the time, so the share of it held while on battery with the screen
      off has no matching base.
    - The small apps' wakeup alarms (the UIDs a window folds together, `OTHER_UID`) stay as one
      row, "Small apps", instead of being dropped. The group's rate is then the whole count.
    - `ChainTotals` drops its discharge sums, which §34 added and nothing reads now.
    - R: found by the independent review of Compare (ADR-0038). Dividing by a clock other than the
      counter's own makes every rate wrong by the gap between them (SPEC 0.2, measurement honesty).
