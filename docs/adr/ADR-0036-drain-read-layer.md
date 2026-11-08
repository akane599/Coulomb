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
