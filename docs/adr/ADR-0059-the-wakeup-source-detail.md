# ADR-0059: The wakeup source detail

- Status: accepted
- Date: 2026-11-21
- Phase: P5 (SPEC 10.5)
- Builds on: ADR-0028 (wake coverage), ADR-0056 (the Timeline's tracks)

## Context

SPEC 10.5 asks, for a kernel wakelock or a wakeup reason, for the dictionary's description, the
subsystem, "which apps correlate in time (from history)", per-session counts and what the user can
do. It does not say what "correlate" means, which sessions, or where the page lives.

## Decisions

1. **Where.** A detail page of Drain's Wakeups lens (`Route.WakeSource`), beside the list on
   expanded widths. Kernel wakelocks, wakeup sources, wakeup reasons, resume causes and abandoned
   sleeps open it; a partial wakelock belongs to its app and does not.
2. **The window.** The page reads Drain's chosen window. A record the window lacks reads "Not
   seen" only when its source covered the whole window; otherwise "Not collected" (ADR-0028 §3).
3. **Per-session counts** are the record in each of the latest seven nights of the Night Report,
   with the same rule for a missing record. Each night's bar is against the busiest of them.
4. **Apps that followed.** Only for a wakeup reason, because the history records why the CPU began
   to run and the holder of the first wakelock taken while none was held, and nothing else ties an
   app to a wake. For each stretch the CPU ran for the reason, the app counted is the holder of the
   first wakelock that began in that stretch within 10 s of its start. A stretch with none is
   counted and said ("After 97 wakes nothing took a wakelock in that time"), and so is one whose
   first wakelock has no holder in the history; the next holder is not credited in its place. The
   page says this is what came next, not what caused the wake. For the other kinds the page says
   the history cannot tell.
5. **What counts as a wake.** A stretch whose beginning the history does not show is left out: one
   cut by the start of the read, and one that begins where a gap in the history ends. The page
   names how much history it read and up to when. No history is "not collected", and history
   with no stretch that begins with the reason says so; neither reads as "no wakes". A reason
   logged while the CPU was already running (many abandoned sleeps on some builds) begins no
   stretch, so it has a count above and no apps below.
6. **The stretch read** is the 24 hours of history that end where the window ends: a day reads in
   about 0.1 s, and a longer window would only make the page slower to open. The nights are read
   again only when the list of nights changes, not with every snapshot.
7. **What you can do** is a fixed sentence per part of the phone (`WakeSubsystem`), and one for an
   abandoned sleep. Most say "Nothing", plainly. No sentence promises an effect Coulomb has not
   measured.

## Consequences

- On the phone's data of 2026-11-21 the day held about 1,000 wakes with a reason; the real-time
  clock's alarm was followed by an app's wakelock almost every time, the modem's and Wi-Fi's
  interrupts in about a fifth of theirs.
- On the same data the 10 s limit changes almost nothing: of the six most frequent reasons, a
  limit of 1 s loses 9 of about 950 wakes and 5 s loses none. The limit is there for the stretch
  that lasts as long as the screen is on.
- Most wakelocks that follow a wake are Android's own (the system and phone UIDs), so the list
  often names Android, not an app.
- A reason whose name the history prints differently from the summary's would show no wakes. On
  that data 85 of 90 stored reasons had the same name in both.

## Reverting

Remove the route, the screen and `WakeSourceRepository`. Nothing is stored.
