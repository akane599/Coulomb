# ADR-0041: App detail's read layer

- Status: accepted (decided under ADR-0027 without a second opinion: a read model over the existing schema)
- Date: 2026-11-09
- Phase: P4

## Context

SPEC 10.4 lists App detail's parts:

1. a header with the app's mAh and share in the window;
2. the app's story;
3. its Act insight;
4. component and process-state breakdowns;
5. "Woke the phone N times", with wakelock, alarm and job tables;
6. network and foreground time;
7. a 14-day trend sparkline with update markers;
8. actions.

The story (9.3), insights (9) and actions (13) come in P5. Everything else reads data the
collector already stores. Drain's read layer (ADR-0036) already answers "who used the battery in
a span" from a stored window or a chain of consecutive windows.

## Decision

1. **App detail reads one app over the chosen window** (SPEC 10.1: the window persists across
   screens). It reuses Drain's report for that span, so the header figure and share are the ones
   on the Drain row the user tapped. On top of that report it adds the app's own parts:
   - its wake records: Drain's wake entries for its UID, with Drain's coverage;
   - its alarm wake-ups;
   - its counters over the same window, from the stored window's detail or the chain, as Drain
     reads its counters:
     - jobs: count and run time;
     - network: mobile and Wi-Fi bytes received and sent;
     - time with an activity in the foreground, and with a foreground service;
   - a counter with no row reads "not recorded", never 0.
2. **Component breakdown** is Drain's chip data for this app (`SpanBreakdown.of`, ADR-0035 §7),
   with "Not broken down" for the rest.
3. **Process-state breakdown** comes from the same S4 rows: foreground, background, foreground
   service and cached, in mAh. "Not split" is the app's total minus those four, shown when it is at
   least 0.05 mAh, as for components. Without S4, both breakdowns show the S4-off state and name the
   setting (ADR-0035 §2). S5 has no process states.
4. **The 14-day trend** has one value per local day: the app's mAh summed over the consecutive
   windows that end that day.
   - Consecutive windows keep their detail for 30 days, so all 14 days are there.
   - A window that ends just after midnight counts for the day it ends. Windows are minutes long,
     so a day loses or gains at most one window's worth.
   - A day with no consecutive window has no value: drawn as a gap, never as zero.
   - A day where the app was folded into small apps adds less than 0.1 mAh a window
     (`WindowBuilder`'s floor), so its value is a lower bound by at most that.
   - Today is the day so far.
5. **The update marker** is the app's last update from `PackageManager` (`lastUpdateTime` of its
   packages, the latest one), shown when it falls in the 14 days and is later than the first
   install. The platform keeps no history of earlier updates without privileges, so there is at
   most one marker. Recording every update would need a `PACKAGE_REPLACED` log; that waits for a
   reason.
6. **An app with no use in the window** still opens: the header says no use was recorded in this
   window, and the trend and the counters show what there is. Small apps, Android System's device
   rows and the unattributed remainder do not open App detail; their rows are not app rows.

## Consequences

- `DrainReader` gains `readApp(span, uid)`. It reads the span's bounds once and builds both
  reports, plus two `WindowDao` queries for the trend. The days with data count only windows that
  keep their records, so a pruned day is a gap, not zero.
- `DrainRepository.app(span, uid)` shares Drain's inputs, so it is read again after each heavy
  snapshot like Drain. `AppUpdates` adds the update marker off the main thread.
- Drain's app rows and Compare's rows open App detail (amended 2026-11-09, below).

## Amendment 2026-11-09: the screen as built

- **Which rows open it.** Drain's app rows and Compare's rows. Now's top drains and the
  Components lens's top apps do not, for now. SPEC 10.2 and 10.3 ask neither to. Also, App detail
  reads Drain's chosen window, and Now's window can differ, so a tap from Now would show other
  figures than the row it came from. "See all" already takes Now's window to Drain.
- **Window.** The header names the window and says it is changed on Drain. The lab's window
  button is left out: one window for Drain and App detail keeps the header equal to the Drain
  row (§1). Recorded in `docs/questions.md`.
- **Alarms.** S5's per-alarm names are not stored (only the app's `ALARM_WAKEUPS` total is), so
  there is no alarms table: the count heads the section, with a note that Android counts them per
  app. Storing names would need a schema change. Recorded in `docs/questions.md`.
- **Process states** show mAh only. S4's per-state times are not stored.
- **Jobs** are the app's partial wakelocks whose tag matches JobScheduler's format: `*job*`, an
  optional type letter, `/`, then the battery name (`JobStatus.getWakelockTag`,
  android-16.0.0_r4 `JobStatus.java` 1501-1513, android-17.0.0_r1 1733-1745). The name drops the
  optional `#trace#` and `@namespace@` and shortens the app's own package to a leading dot.
- **Tables** are inline: fixed-width figure columns. Where a name would get less than 7.5 em, as
  at 200% text, each row stacks.
- **Trend scale.** The top is the smallest round value (quarters at 1, 1.5, 2, 2.5, 3, 4, 5, 6 or
  8 times a power of ten) at or above the largest day, so the bars fill most of the plot.
- **Expanded widths** (SPEC 10.10). App detail opens beside Drain's or Compare's list where both
  panes fit at 360 dp times the font scale: the list takes 40% (360 to 480 dp), the detail the
  rest. Choosing another row swaps the detail instead of stacking it. The panes are a small
  Navigation3 `SceneStrategy` of our own (`ListDetailStrategy`), not Material's adaptive
  list-detail. Material's version would add a dependency and its own pane motion, while SPEC 11.6
  allows only the cross-fade.
- **Not yet:** the story, the Act insight card and the actions (P5, ADR-0026). The shared-element
  transition from a Drain row (SPEC 11.6) waits for the screen's animate gate.

