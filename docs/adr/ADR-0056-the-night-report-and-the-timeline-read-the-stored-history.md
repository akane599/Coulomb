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
   it (`Route.Night`) in the list-detail scene. Until the Timeline screen lands, the destination
   shows the list.
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
