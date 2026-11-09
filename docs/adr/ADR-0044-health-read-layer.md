# ADR-0044: Health read layer: estimate, habits, cycles, dates, owner's design capacity

- Status: accepted
- Date: 2026-11-09
- Phase: P4

## Context

The Health screen (SPEC 10.8) shows the capacity estimate with health % and the cross-check,
cycles, manufacture and first-use dates, the capacity chart, charging habits, charge sessions and
chargers. ADR-0037, ADR-0042 and ADR-0043 store the rows. SPEC names the habits ("average depth of
discharge, time at ≥ 80%, average charge temperature, wear stress distribution") without defining
them, and leaves open how the cycle comparison and the dates are read. SPEC 15.2 keeps `:core:data`
away from `:core:privileged`, while S3 is read "daily and on Health screen open".

## Decisions

1. **The estimate is made again at each read**, from the stored windows, with SPEC 6.3's
   estimator, the read time for recency and today's design capacity. The day's summaries give the
   chart's line and band; each stored window is a cross. Change line: the estimate now against
   the first day that had one, once that day has passed, with half that day's interval width to
   say whether the change is inside it.
   - R: a design capacity the owner enters changes health % at once, and the figure is the one the
     collector would write now. Same estimator, so no new estimation logic.
2. **Habits, over the last 30 days**, from closed charge sessions that ended in the window:
   - depth of discharge: the mean of 100 minus each charge's start level;
   - time at 80% or more: from the stored level line, raw samples and the minute aggregates that
     replace them after 14 days (one SQL pass, `SampleDao.timeAtOrAbove`). Each level holds until
     the next sample of its boot, since a level change is always sampled (ADR-0037 §1). A gap over
     6 hours counts as neither, as when the process was not running. The figure is the share of
     watched time times 24 hours, shown once at least a day is watched;
   - charge temperature: the charges' mean temperatures weighted by duration (elapsed clock);
   - wear stress: the charges counted by their stored level (ADR-0037 §3); a charge without one is
     left out.
   - R: charge sessions carry everything except the high-level time, which also happens after an
     unplug at 100%, so it comes from the level line. Scaling by watched time keeps days the app
     did not run from reading as days spent low.
3. **Cycles**: the platform count is the latest stored `EXTRA_CYCLE_COUNT`. Once two days carry a
   count, Coulomb's sum and the platform's count are both differences over the same days, from the
   first such day. Before that, Coulomb's whole running sum is shown, from the day the first counted
   charge started, without a platform comparison.
   - R: the summaries are written after a session closes, so only differences between them cover
     the same charges. A comparison over unequal spans would mislead.
4. **Dates are read live when the screen opens** (S3's schedule), not stored. A public
   `HealthFacts` in `:core:collect` reads S3's manufacturing and first-usage dates (properties 7
   and 8, epoch seconds in UTC, BATTERY_STATS) and, only when S3 has no first-use date, the OEM's
   from S21 through the worker (ADR-0042 §3). A date before 2010 or after today is a placeholder.
   Each date keeps its source.
   - R: `:feature:*` may depend on `:core:collect` (SPEC 15.2); no schema change; one S21 read at
     most per screen opening, only on devices whose S3 lacks the date.
5. **The owner's design capacity** is written to the current build's profile with source `user`
   (500 to 20,000 mAh). Three changes keep it:
   - the collector reads the stored design capacity at each snapshot, not its copy from start-up;
   - calibration and the device read write only their own columns (`setCalibration`,
     `fillDesignCapacity`), so neither can write back an older design value;
   - a new build's profile starts with an owner-entered value from an earlier build. Read values
     are read again per build, as before.
   - R: the profile is per build (SPEC 8), but the battery does not change with an OS update; an
     entry lost at every update would be a silent regression.
6. **Charger rename** writes the name column alone (`renameChargerProfile`), trimmed to 40
   characters. A blank name means unnamed, and the screen names it by its plug type.

## Consequences

- `HealthRepository`, `HealthReader` (pure), `HealthModels`, `BuildFingerprint`; `HealthFacts`,
  `BatteryDates`; new DAO reads and targeted writes. No schema change.
- Tests: `HealthReaderTest`, `HealthRepositoryTest`, `SampleDaoTest` (time at a level),
  `CollectorEngineTest` (an owner's entry while running and across an update).
- The habits and the cycle comparison are estimators: they join the independent review before the
  P4 gate with ADR-0037, ADR-0042 and ADR-0043.
