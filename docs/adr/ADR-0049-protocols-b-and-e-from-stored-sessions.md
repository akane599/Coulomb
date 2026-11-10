# ADR-0049: Protocols B and E from stored screen-off sessions

- Status: accepted. Decisions 1 and 2 are the owner's (2026-11-10). Decisions 3 to 6 fill in what
  the owner left open, under ADR-0027 without a second opinion: a validation rule and a read-layer
  estimator, with no schema, format or public API change.
- Date: 2026-11-10
- Phase: P4 (B is in the P2 gate through ADR-0019 and in the P4 gate)
- Amends: SPEC 1.3, 16.6 (B, E), 17 (P2 gate), 18; ADR-0019 and ADR-0020, whose one-night method
  this replaces

## Context

ADR-0019 measured B and E over one night of 8 h or more, screen off and unplugged, between
checkpoints taken at its start and its end. The phone has not had such a night: it charges
overnight, and it rebooted 55 times in 4 days of collection. B's old reference was the level delta
between the checkpoints, so whole-percent rounding at both ends put it within ±1 point over the
night.

The owner changed both protocols on 2026-11-10 and asked for them to be computed first from the 4
days the phone had already collected (2026-11-06 to 2026-11-10). Another night is measured only if
that data is not enough.

## Decision

1. **B (owner).** The reference is the moments the battery level changes, not the session's ends:
   the whole points between the first drop and the last, divided by the time between them. Screen-off
   sessions on battery of 2 h or more count; several may be combined, together at least 4 points.
   Pass: within ±0.1 %/h, as before.
2. **E (owner).** No single 8 h stretch is needed. Screen-off sessions on battery of 30 minutes or
   more count, together at least 8 h. While the screen is off: 0 wakeups and 0 alarms from Coulomb,
   and a cost of at most 0.3% of capacity a day (0.1% per 8 h, the old night's mark). Getting up in
   the night splits a session; it does not spoil the test. Sessions split by a reboot are left out.
3. **What counts as a session.**
   - A stored `SCREEN_OFF` session that closed on a state change, with both of its snapshots still
     stored and in the session's boot.
   - A session closed by a reboot is split by a reboot and left out. So is one opened by its boot's
     first snapshot, whatever that snapshot's trigger.
   - The session may open or close at a plug change, since the screen stays off.
   - The consecutive windows between its two snapshots must tile it with their detail kept.
   - Their device clocks must show it on battery with the screen off throughout, within 2 minutes
     for the snapshots themselves.
   - Their realtime must match the session's length. Android resets its statistics on unplugging at
     full charge, and a window across that reset says nothing.
   - E is Full mode, so Coulomb's collector must have been a foreground service throughout.
   - For B only, a session with a history gap inside is also left out, since the gap can hide its
     first or last drop.
4. **E's other marks.** Coulomb's partial wakelocks stay under 30 s per 8 h of counted time, the
   old night's mark scaled to the time. Capacity is the platform's (`pws`), as ADR-0019's report
   used. Wakeups are Coulomb's wakeup alarms and radio wakeups in those windows. Alarms are also
   checked in `dumpsys alarm` at the capture. The system battery graph, B's old third reference, is
   not machine-readable and comes from the same level changes, so it is not a pass condition.
5. **Coulomb's screen-off rate uses the platform's level changes.** SPEC 6.2 shows the level-based
   rate until calibration completes, and this phone's charge counter is coarse (4.9 mAh steps that
   follow the gauge's level), so the level rate is the one shown. From the session's ends it fails
   B on this data (see Results), for two reasons:
   - after a full charge the gauge holds 100% for an hour or more;
   - each end rounds to a whole point.

   The read layer therefore times a closed screen-off session by the first and last level drop that
   the platform's history recorded inside it (S7, stored in `history_event`):
   - **Reading.** It reads them through the session's snapshot id range (`HistoryDao.levelChanges`:
     the same range as `events`, with its two snapshot subqueries on their index, then a primary-key
     range).
   - **Which sessions.** Only the closed screen-off sessions of the open discharge are read, the ones
     Now's screen-off rate counts. The time estimate's older history keeps their ends.
   - **Caching.** A session's result is kept for the collection once a history read past its end is
     stored (`HistoryDao.readAfter`). Until then it counts from its ends and is read again.
   - **Fallbacks.** With fewer than two drops it keeps the ends. A span cut by a plug change keeps
     its drops only when both fall inside the part on battery.
   - **The rate and its hours.** The rate is the points counted over the time between the drops.
     The rate's hours stay the span's whole length, which readiness (SPEC 6.2's 15 minutes), the
     caption and the time estimate's trust weigh.
   - **Quality.** It keeps the `1 - 1 / points` resolution over the points counted, so a timed span
     earns no more than before. That is conservative, since the timed points carry no rounding.
6. **Screen-on spans keep their ends.** They are sampled, short and end at a sample. B and the
   100% hold concern the screen-off rate.

The tool is `tools/validation/report.py`, which now computes B and E from one capture:
- B's reference reads the history by boot and elapsed time.
- Coulomb's B rates do not come from the tool. `CaptureRatesTest` (`:core:data`, skipped without a
  capture) opens a copy of the capture through `CoulombDatabase.build`, so older schemas migrate.
  It rates every closed screen-off session with the app's read path (`HistoryDao`,
  `LevelDropsCache`, `ScreenRates`), and `report.py --coulomb-rates` reads its output.
- `ScreenRatesPhoneSessionsTest` pins the estimator on the two sessions below, as numbers only.
- The report prints collection days and session ids, never a clock time (ADR-0004).

## Results on the 4-day data (capture 2026-11-10, build of 2026-11-06)

- **E: pass.**
  - Eight sessions count, 12.64 h together. Two of the ten sessions of 30 minutes or more were left
    out: one was plugged in, and one spans a statistics reset.
  - Coulomb: 0 wakeup alarms, 0 radio wakeups, 0 lines in `dumpsys alarm`, 0 s of partial wakelock.
  - Cost: 0.151 mAh, 0.0030% of 5,000 mAh, against 0.158% allowed for 12.64 h.
  - The device was in deep Doze for 93% of the counted time and the CPU slept for 29%.
- **B with the session's ends (before decision 5): fail.**
  - Two sessions count, 11 points together.
  - Reference 1.848 %/h, Coulomb 1.625 %/h: 0.223 apart.
  - The 3.05 h session (67% → 61%) is 0.126 apart from rounding alone.
  - The 4.95 h session (100% → 93%) is 0.439 apart: it held 100% for its first 1.15 h.
- **B with decision 5: pass.**
  - Coulomb's own rates, from `CaptureRatesTest` on the capture, are 1.842 and 1.852 %/h, together
    1.848. That is 0.000 %/h from the reference, against ±0.1 allowed, over 11 points.
- **Migration rehearsal.** The capture is schema 1, the build of 2026-11-06. On the API 37 emulator
  the current debug build opened it without a crash, and `CaptureRatesTest` ran the same migration
  on the JVM. The emulator's copy is schema 5 and passes `quick_check`. It keeps every phone row
  (history 272,353 of 272,353), and B and E read the same on both schemas.

## Review (2026-11-10)

One independent read-only review, of the estimator, the query, the read layer, the tool and this
ADR. It found nothing material in the estimator or the query. Fixed:
1. E had lost ADR-0019's Full-mode check: decision 3's foreground rule.
2. B's Coulomb column was computed by the tool, through the same Python as the reference, so it
   could hardly fail. It now comes from `CaptureRatesTest`.
3. The drops were read for every screen-off session in the 15-day lookback, again on each return to
   Now. Now only the open discharge's are read.
4. A session whose closing read had not stored its history yet kept a short result. It is now kept
   only after a read past its end.
5. The rate's hours had become the drop-to-drop time, which would have delayed readiness and
   shortened the caption. They are the span's length again.
6. Sessions whose end snapshot had been deleted vanished from the report. They are now listed with
   a reason.
7. A session opened by a boot's first snapshot under another trigger passed. It is now left out.
8. This ADR claimed that a device with a reliable counter shows the counter rate. The screen rates
   are level-based on every device for now (`StateRate` is Derived). SPEC 6.2's counter rate on
   Now is an open item, not this ADR's.

Caveat noted:
- Heavy snapshots read through Shizuku's worker, which runs as the shell UID in shell mode, and
  through system_server. The platform bills that work to those UIDs, not to Coulomb's, and SPEC
  16.6 E counts Coulomb's UID.
- Over the counted windows the shell UID drew 0.184 mAh, together with everything else that ran as
  shell. Even all of it on top of Coulomb's 0.151 mAh is 0.0067% of capacity.
- The system_server share cannot be told apart.

## Consequences

- No night with checkpoints is needed for B or E. `report.py` drops `--night-start` and
  `--after-screen-on`, and B and E come from any capture.
- E now also covers sessions with the phone in deep Doze (93% here), which ADR-0019's night had
  not reached.
- Coulomb's screen-off %/h on Now moves to the timed rate. After a full charge it no longer reads low
  while the gauge holds 100%. The time estimate's history keeps the ends.
- B now checks that the app reads the platform's level changes correctly, against a reference
  computed from the same platform record by a different path. It no longer compares two
  independent measurements.
