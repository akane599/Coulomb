# ADR-0039: Partial wakelock time is the actual time held

- Status: accepted (after a second opinion under ADR-0027, which recommended this option)
- Date: 2026-11-09
- Phase: P4

## Context

The independent review of Compare (ADR-0038) said that S5's partial wakelock time might be pooled.
AOSP confirms it at android-16.0.0_r4 and android-17.0.0_r1:

- An app's partial wakelock timer is a `DualTimer` in the global pool `mPartialTimers`, on
  `mOnBatteryScreenOffTimeBase` (`BatteryStatsImpl` 16 L10643-10645, 17 L10207-10209).
- Its total time is pooled. While N partial wakelocks run at once, across all apps, each one
  accrues 1/N of the elapsed time (`StopwatchTimer.computeRunTimeLocked`, 16 L3868-3876,
  17 L3419-3427). Checkin prints this as the partial block's total and proto as
  `TimerProto.duration_ms`. Both parsers stored it.
- `DurationTimer` also keeps the actual time held: not split, on the same time base, and since the
  same reset (16 L3491-3699, 17 L3042-3250). Checkin prints it as the partial block's last column
  (`printWakeLockCheckin`, `BatteryStats` 16 L3719-3750, 17 L3740-3771). Proto writes it as
  `TimerProto.total_duration_ms` and leaves it out only at -1 (16 L3837-3838, 17 L3858-3859).
- In the phone fixture, 162 of 171 partial wakelock rows differ. The actual time is at least the
  pooled time in every row. The sums are 2,500 s pooled and 3,067 s actual, and single rows differ
  by up to 60%. In 85 rows the longest single hold was longer than the pooled "time held".

Drain shows this time as "time held", marked Measured, with a bar of its share of the screen-off
time on battery. Compare shows minutes held per hour. Rules R01 and R02 and protocol E reason about
how long an app kept the CPU awake.

Changing what a stored counter means is a data-format decision, so it had a second opinion
(ADR-0027).

## Decision

1. **The parsers store the actual time.** The pooled time is dropped. `WakelockStat.partial.totalMs`
   is now the actual time in both parsers. The count, the longest hold and the background time
   were never pooled, and they stay as they are.
   - R: the pooled time is batterystats' share of blame between wakelocks, not a measurement of a
     hold. Shown as "time held", it understated each row by the overlap (SPEC 0.2, measurement
     honesty). Nothing reads the pooled time: per-app energy comes from Android's power model (S5
     now, S4 per ADR-0035), and the raw dumps still hold it for 30 days.
   - R: no schema change. A column for a value nothing reads would cost a migration that Room
     cannot take back, and old rows would still have no actual time (SPEC 0.3 rule 5).
2. **Only partial wakelocks change.** Kernel wakelocks, wakeup reasons and the other timers keep
   their own fields. Their timers are not pooled, and they do not track a separate duration.
3. **A broken row keeps its pooled time, with a parse issue.** A row whose actual time is missing,
   untracked (-1) or below its pooled time keeps the pooled time and records "partial wakelock
   without its actual time". The pooled time is rounded from microseconds, so 1 ms the other way
   is allowed (`actualCovers`).
   - R: a dropped row would make the next window diff count that row's whole total since the
     reset, which is worse than one window reading a pooled and an actual time. The review found
     the case unreachable in AOSP 16 and 17 (the actual time is never -1 and never below the pooled
     time), so the issue is a signal, not a path the data takes.
4. **Actual times overlap, so they are not added.** No screen sums partial rows into one figure.
   `CounterMapper` sums a tag listed twice under one UID, which makes that row an upper bound.
   Per-app totals, where a rule needs them, come from the `awl` line, which is also actual time.
   Each share bar is one row's time over the screen-off time on battery, clamped to 100%
   (`WakeGroup.share`).

## Consequences

- Snapshots taken before this change hold pooled times. On a device that ran an earlier build, a
  window that spans the upgrade diffs actual minus pooled, so its partial rows read high. The
  windows stay in `window_wake` for up to 30 days. Only development devices hold data and no build
  has been released, so there is no migration code. Clearing the app's data drops these windows. That is
  the owner's choice for the phone (SPEC 0.6), and it is noted in `docs/progress.md`.
- The parsers had an independent review before the Drain gate (AGENTS.md); see Review.
- Parser tests pin the actual time against crafted rows in both formats, and against every fixture
  row: a row's time covers its longest single hold.

## Review

An independent read-only review (code-audit-bugs, high) on 2026-11-09 of both parsers, the
downstream readers (`CounterMapper`, `WindowBuilder`, `Attribution`, `WindowDao.chainWakes`,
`WakeGroup`, `Comparison`) and AOSP 16 and 17 found no defect.

- Confirmed: the checkin column mapping, the proto field and its -1 guard, the same time base,
  `which` and reset for both counters, the pooled time never above the actual one (all clock
  inputs are whole milliseconds), and that nothing adds partial times across tags. The phone
  fixture has no row that `actualCovers` rejects.
- Fixed: two line ranges (`computeRunTimeLocked` 17 ends at L3427; `DurationTimer` ends at
  16 L3699, 17 L3250) and the share-bar sentence in decision 4.
- Taken: the reviewer suggested keeping a broken row rather than dropping it (decision 3).
- Tests added: the edges of `actualCovers` (1 ms below kept, 2 ms below not), an absent proto
  actual time with at most 1 ms pooled, a cut-off checkin row, and `CounterMapper` adding a tag
  listed twice.
- Not verified from source: `TimeBase.setRunning` and the proto writer's rule that a zero int64
  is not written; the copied AOSP tree lacks those files. The parser reads absent as zero either way.
