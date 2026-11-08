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
24. **"Scale to measured" sits under the reconcile bar and shows only with the Apps lens.**
    - R: the switch spreads the bar's unattributed gap over the apps, so it belongs with the bar
      it changes. Components and Wakeups do not scale.
