# ADR-0020: The night report's validity rules, and the stress test's size projection by retention class

- Status: accepted (corrects ADR-0019; edits SPEC 16.3); the size projection (decision 4) is
  corrected by ADR-0021
- Date: 2026-11-07
- Phase: P2

## Context

ADR-0019 made protocol E one night and added a 30-day stress test. Two of its details were wrong.

**The night report could pass a broken night.** An independent review of `tools/validation/report.py`
found these false passes:
- A second stats reset during the night went unseen when the start count stayed the same, as after
  an unplug reset followed by another unplug at a high level. The counters then went down and the
  negative deltas passed.
- An unknown UID, a dump cut short, or a missing Coulomb line all read as 0.
- ADR-0019 counted the wakeups in Coulomb's own windows. No window covers the night: Coulomb takes no
  snapshot while the screen is off on battery, and the end checkpoint comes before the screen comes
  on. So that source always read 0.
- Charging during the night, screen-on time, or Coulomb not running at all went unnoticed.
- A failed `dumpsys alarm` read as "no alarms".

The same review found that B's rate could not work from the end checkpoint. Coulomb neither samples
nor snapshots while the screen is off on battery (SPEC 7.3), so the night's session gets its end
reading only from the screen-on snapshot.

**The size projection could not pass.** ADR-0019 extended the database's whole growth from day 15 to
day 30 to day 365. But history events and blobs are 30-day rings that are still filling between day
15 and day 30. At the phone's measured history rate (about 66,000 stored events a day, ADR-0019's
checkpoint), history alone grows about 1.8 MB a day. Extending that for 335 days adds about 600 MB
that never occurs: the ring stops growing at day 30.

## Decision

1. **A night is valid only when all of these hold; otherwise the report gives no result and says why.**
   - Both checkpoints record that `dumpsys batterystats -c` exited cleanly with output. `capture.sh`
     writes a `.status` file for each dump.
   - Coulomb's UID is known, and both checkpoints have its `pwi,uid` line. The foreground service
     always costs some CPU, so a missing line is a bad capture, not 0.
   - No reboot: the start count is unchanged and `/proc/uptime` did not go down.
   - No stats reset: battery realtime did not go down, and the stats start clock is not inside the
     night. A reset sets that clock to the time of the reset (`BatteryStatsImpl.initTimes`). A
     wall-clock change moves it with the clock but keeps it at that reset (`adjustStartClockTime`).
   - No counter went down.
   - Unplugged and screen off throughout: the night's battery time and its screen-off battery time are
     each within 2 minutes of the wall-clock time between the checkpoints.
   - Coulomb ran throughout in Full mode: the same process ID at both checkpoints, and the collector
     shown as a foreground service at both.
2. **Wakeups are the platform's wakeup alarms for the UID (`wua`) plus its mobile and Wi-Fi radio
   wakeups (`nt`).** Coulomb's own windows are dropped as a source. Alarms come from `dumpsys alarm`;
   when that dump failed, the alarm check reads n/a, never pass.
3. **B takes a third checkpoint after the screen comes on.** Coulomb's rate comes from the night's
   SCREEN_OFF session as that checkpoint's database closed it. The session must cover at least 90%
   of the night.
   - The level reference is known only to about ±1% over the night, because levels are whole percents
     at both ends. That is ±0.125 %/h over 8 h, more than the ±0.1 %/h pass mark.
   - So a gap above 0.1 %/h but within that rounding reads "inconclusive", not fail.
4. **The stress test projects the database by retention class (SPEC 16.3).**
   - **Capped tables** stop growing once full: raw samples (14 days), and history events, gaps and tags
     (30 days, SPEC 7.7). So do the detail rows of consecutive, reference and charge-session windows
     (30 days, ADR-0015). These count at their day-30 size.
   - **Every other table and index** grows for good or for 180 days. Its growth from day 15 to day 30
     is extended to day 365.
   - Projection = the day-30 database file + Σ over the growing parts of (bytes at day 30 − bytes at
     day 15) × 335 / 15. It must stay under 150 MB.
   - Per-table sizes come from SQLite's `dbstat` table. Android's SQLite build enables it
     (`external/sqlite` `dist/Android.bp` L60, both `android-16.0.0_r4` and `android-17.0.0_r1`).
     Detail rows split their table's bytes by row count.
   - The extension still errs high: 1-minute aggregates turn hourly after 180 days, and counters
     pruned after 2 h hover rather than grow.

## Consequences

- `report.py` takes `--after-screen-on <capture>`. The night plan is three captures:
  - at the start, with the screen off and unplugged;
  - at the end, before the screen comes on;
  - one after the screen comes on.
- Captures made before this change lack the `.status` files, so the report gives no night result for
  them.
- The stress test fails on unbounded growth in any table that SPEC 7.7 does not cap. A capped table
  that fails to cap (retention not running) shows in its day-30 size against the budget, and in the
  history-row count the test prints.
- SPEC 16.3's stress-test sentence names this projection. SPEC 18 already says "within the 7.7
  projection" and is unchanged.
