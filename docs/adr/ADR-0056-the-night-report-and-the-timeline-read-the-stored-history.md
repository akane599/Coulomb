# ADR-0056: The Night Report and the Timeline read the stored history

- Status: accepted
- Date: 2026-11-21
- Phase: P5 (SPEC 10.6, 10.7)
- Builds on: ADR-0014 (history ingest), ADR-0018 (window chains), ADR-0023 (timeline marks), ADR-0054 §14

## Context

SPEC 10.7 asks for a list of screen-off sessions of an hour or more with their rate, deep-sleep
share and "Doze reached (yes/no and when)", and a detail page with a compact timeline and ranked
culprits. SPEC 10.6 asks for a timeline of eleven tracks. The spec does not say where the Night
Report lives, which stored events make a track, or what to draw where the history is missing. The
data pulled from the phone on 2026-11-21 showed four more things the spec does not cover:

- One boot ran with a wall clock about 40 days behind until the clock was corrected. Sessions of
  that boot are stored with the wrong dates, and a charge session of that boot spans weeks by wall
  time, so it "overlaps" nights it never touched.
- The collector never sets `session.deepIdleReached`.
- `WIFI_RUNNING` never occurs in the stored history; `WIFI_RADIO` does.
- A day holds 10,000 to 95,000 history events, most of kinds no track draws.

## Decisions

1. **Where the Night Report lives.** SPEC 15.2 fixes the feature modules and has none for nights.
   The Night Report is a view of the same history as the Timeline, so it is built in
   `:feature:timeline` and reached from the Timeline destination: the list, and one night beside
   it (`Route.Night`) in the list-detail scene. The Timeline shows the latest night under its
   chart, with "See all".
2. **A night's dates come from its boot's latest clock.** The session's start and end are stored
   as elapsed time in a boot. They are placed on the wall clock by that boot's latest snapshot, so a
   clock set right later in the boot dates the whole boot right. The stored wall times are used
   only when the boot has no snapshot.
3. **Charging is judged by elapsed time in the same boot.** A night was "on the charger" when the
   level rose or a charge session of the same boot overlaps it by elapsed time. Wall time is not
   compared.
4. **A rate needs evidence.** A night has a rate (Derived) when it lasted an hour, the level fell
   by 3 points or more and it was not charged. Below 3 points the level's one-point steps make the
   rate mostly rounding; the row then shows the level lost (Modeled, as the fuel gauge reports it).
   A charged night shows a dash and says why.
5. **Doze reached comes from the history.** Deep Doze was reached when a `DEVICE_IDLE` deep state
   occurs inside the session, and "when" is the time from the session's start to the first one.
   Unknown (no history for the session) is shown as nothing, not as "no".
6. **Tracks and their events.** Screen `SCREEN`; Charging `PLUGGED`; Doze `DEVICE_IDLE` (light,
   deep); Awake `CPU_RUNNING`, named by a `WAKE_REASON` within 2 s of its start; Top app `TOP_APP`;
   Wakelocks `WAKE_LOCK`; Mobile radio `MOBILE_RADIO`; Wi-Fi `WIFI_RADIO`; GPS `GPS`; Signal
   `PHONE_SIGNAL_STRENGTH`; Temperature `BATTERY_TEMPERATURE`; the level `BATTERY_LEVEL`. Only
   these kinds are read from the store.
7. **Unknown is not off.** A state is known from a record that restates it (ADR-0014) until the
   boot changes, the phone shuts down or the history has a gap. Outside those stretches a track has
   no ground and no marks. A read starts at the stored read at or before the span's start, so the
   state at the start is known. Open states end where the stored history ends, not at "now".
8. **The Timeline's time axis.** Each boot's events are placed on the wall clock by that boot's
   latest snapshot (as in 2). One night is read by its boot and elapsed time, not by wall time, so
   a boot whose clock was wrong still has its timeline.
9. **The night's page.** Its head is the rate, or the level lost. Its story is the Drain Story of
   SPEC 9.3 in full; on the charger the first sentence is left out, because the level says nothing
   about the drain there. The compact timeline shows the level and the bands Charging, Doze, Awake,
   Wakelocks, Mobile radio, Wi-Fi and GPS; a band that stayed empty is left out, except Doze and
   Awake. It has no current panel: Coulomb takes no samples while the phone sleeps (SPEC 7.4). The
   culprits are the first five of each kind with "Show all"; the kernel tables use S5's records
   where the window has them and the root-only records otherwise, as Drain does. A text button
   opens the same window in Drain.

10. **The Timeline's window.** It is 15 minutes to 7 days long (SPEC 10.6), never past now and
    never before the 30 days history is kept. Two fingers zoom about the point between them and
    drag; a range preset keeps the window's end, and "Now" moves the end to now and clears the
    cursor. A window that ends at now moves along when a snapshot brings more history.
11. **What is read.** One read covers the window and a window's length on each side, so a drag
    has something to show. Another read follows when the window leaves it, when it is more than
    8 windows long (the current's 2,000 points would be too thin), or when a snapshot is stored;
    during a gesture it waits 150 ms for the fingers to settle. Reads run only while the screen
    is shown: a destination's model outlives its screen, and a read nobody sees is self-drain.
    The chart places only the marks that touch the window.
12. **The cursor and a tap.** One finger dragged sideways moves the cursor; the readout names
    that moment, or without a cursor the latest known moment in view. A haptic tick marks each
    stretch of a shown track that begins under the moving cursor. A tap on a band picks what lies
    within a sixtieth of the window to each side: one stretch that is longer than that under the
    finger, else every stretch in reach, which is what a density span stands for. Their details
    open in a bottom sheet, or in a side panel from 840 dp (SPEC 10.10), with a link to the app
    when the stretch belongs to one.
13. **Tracks.** All eleven are shown at first; the toolbar's tracks sheet turns each off. The
    choice lasts as long as the screen's model and is not stored: SPEC 10.6 asks only that a
    track can be toggled.

## Consequences

- Nights of a boot whose clock was wrong are dated right once the clock is corrected, and are
  never marked "on the charger" by a session they did not touch.
- A night whose history was pruned (retention, SPEC 7.7) keeps its row and figures but has no
  timeline and no Doze answer.
- The Wi-Fi band shows when the radio was active, not when Wi-Fi was switched on.
- Reading a day of tracks loads only the drawn kinds; on the pulled data a day reads in about
  0.1 s on the build server.

## Reverting

The read models are additive: `NightRepository`, `TimelineRepository` and `DrainSpan.Night` can be
removed without a schema change. Nothing is written to the database.

## Gate amendments (2026-11-21)

From the P5 per-screen gates (`docs/design-gates/2026-11-21-gates-timeline-night-report.md`).

1. **The Timeline's view model reads the privilege state** (`:feature:timeline` depends on
   `:core:privileged`, as Drain does). Access lost shows SPEC 4.5's block; Basic mode shows one
   sentence and "Open Privileges". Stored history is still drawn in either case.
2. **A read has three outcomes on the page**: running ("Reading this range…"), failed (a line and
   "Try again" above what was read before) and done. "No history" is said only for the last.
3. **The window moves without a gesture.** "Earlier" and "Later" beside the readout move it half
   its length; the chart exposes both as accessibility actions; a sideways wheel or trackpad
   scroll moves it and Ctrl with the wheel zooms about the pointer. A plain wheel stays the page's.
4. **The night's chart has no header mark.** It draws a Modeled level and Measured tracks; a line
   under it names both.
5. **Wakeups are counted as exactly as the data is**: a count under an hour of history, a whole
   "about N an hour" over more.
6. **Band tap targets stay 22 dp.** A tap picks by band and the details name the track; 48 dp
   bands would double the chart's height.
7. **The scrub haptic is `SegmentFrequentTick`**, the platform's constant for a control crossing
   a step, where SPEC 11.6 names `CLOCK_TICK`.
8. **A scrub clears the pick**, so the side panel never describes marks the cursor has left.
