# ADR-0055: A system restart is not a reset, and its step is not counted

- Status: accepted for the code; the repair of stored windows waits for the owner (§6)
- Date: 2026-11-21
- Phase: P5 (found while running the insight engine on the phone's stored data)
- Amends: ADR-0015 (counter retention), ADR-0018 (window chains)

## Context

Coulomb numbers boots from `Settings.Global.BOOT_COUNT`. The platform raises that count every time
the system server starts, not only at a reboot (`PowerManagerService.onBootPhase` calls
`incrementBootCount` at `PHASE_THIRD_PARTY_APPS_CAN_START`: android-17.0.0_r1 L1412-1417 and
L5790-5801, android-16.0.0_r4 L1371-1376 and L5567-5578). When the system server restarts without
a reboot, elapsed time keeps running and the battery statistics carry on from their last write.

The data pulled from the S25 Ultra on 2026-11-21 holds 58 changes of boot number between
neighbouring heavy snapshots. 36 are reboots. 22 are system restarts: the wall time of boot (wall
clock minus elapsed time) moved by 1.2 s at most, where a reboot moved it by 122 s at least.

Three things went wrong at a system restart.

1. **A false reset.** `Attribution.resetBetween` used the reboot rule: without a reset the counted
   realtime grows by at least the new boot's uptime. After a restart the "uptime" is the kernel's,
   so the rule saw a reset and the window took every counter's whole total. 13 consecutive windows
   hold about 47,000 mAh of modeled drain that never happened; one 18-minute window holds 4,727 mAh
   and 100 hours of counted time.
2. **A second count of CPU time.** Even without the false reset, the counters after a restart
   cannot be compared with those before it. The system server's CPU time reader starts with no
   earlier reading and takes each UID's time since the kernel booted as new
   (`KernelCpuUidTimeReader`, android-16.0.0_r4 and android-17.0.0_r1 L243-247). `BatteryStatsImpl`
   drops that first reading only off battery or right after a reset (android-16.0.0_r4 L12899-12903
   and L13046-13052, android-17.0.0_r1 L12494-12497 and L12641-12647). On battery every app is
   charged its CPU time since boot again. One stored step shows 296 mAh modeled in 65 s with no
   measured drain; the one restart that happened while plugged in shows none.
3. **Early pruning.** Retention counts back in elapsed time and took each boot number for a boot,
   so after a restart it counted the kernel boot's age twice. A two-hour counter cut then fell
   before every row of the earlier boot number, however young.

The independent review of the first fix (ADR-0027) found points 2 and 3, and a fourth:

4. **Two far-apart snapshots were judged as if nothing lay between them.** Counters are pruned
   after two hours, so the first step of a day or reference chain often joins snapshots hours
   apart. The reset rules hold only for neighbours. Judged directly, a reboot followed later by a
   restart, or a clock correction inside a boot, reads as a reset. Day window 576 took a whole
   counter this way; about 2,950 of its 6,341 mAh are false.

## Decision

1. **Telling a system restart from a reboot.** Two snapshots with different boot numbers lie in one
   kernel boot when elapsed time has not gone back and either
   - the wall time of boot agrees within 60 s, and within how long the boot had run at the earlier
     snapshot (a reboot moves it by more than that); or
   - the counted realtime carried on: it grew by no more than the elapsed time between the two,
     and by less than a boot of that age would have counted. This sign needs no wall clock, so a
     clock correction between the two snapshots cannot hide the restart. None of the 36 reboots
     shows it.
2. **A step across a system restart adds nothing.** `Attribution.step` judges a stretch of
   snapshots pair by pair and gives one of four answers: the counters continue, a reset seen
   closely, a reset with unseen use before it, or a system restart with no reset after it. The last
   adds nothing to any counter and marks the window partial. A reset in the same step does not
   rescue it: which came first is not known.
3. **A window's chain is judged over every heavy snapshot in between**, counters pruned or not
   (`WindowBuilder.links`). The chain keeps the snapshots around each reset and each restart that
   still have counters; each remaining step carries the answer for the whole stretch it spans.
4. **No consecutive window across a restart.** It would hold measured drain with no counted time,
   which bends every rate summed over it. Readers already take a missing window as a gap. A longer
   window made only of such a step stores no modeled total and no detail.
5. **Retention.**
   - The heavy snapshots on either side of a change of boot number keep their counters for three
     days instead of two hours, so a day window can count up to the restart and from it. Without
     this every day with a restart would lose its modeled total; this phone had 22 in 15 days.
   - The cut counts a kernel boot once: the boot number right after another, first seen no earlier
     in elapsed time than that one was last seen, counts only for the time since then.
6. **Stored windows (waits for the owner: it deletes data).** Recommended, and the reviewer's
   choice among leaving, deleting and nulling:
   - delete every consecutive window whose two ends are a system-restart pair, chosen by the rule
     and not by id, in a sweep that is safe to repeat and runs once after the update. On the pulled
     data that is 21 windows: about 47,800 mAh modeled against 900 mAh measured. Measured drain
     stays recoverable from the snapshot rows.
   - for the four day windows that span a restart, clear the modeled total and the detail rows.
   Until the owner agrees nothing is deleted; consecutive windows leave by retention after 30 days,
   day windows never.

## Why not

- **Keep one boot number across a restart.** Then nothing marks where the restart was, and the
  same-boot rule calls it a reset whenever the statistics lost more than a minute.
- **Count `next − prev` across a restart and flag it.** That was the first fix. It is not a lower
  bound (point 2).
- **Leave only the CPU-based counters out.** Which counters the platform re-reads from the kernel
  at start is not something Coulomb can list with certainty for every build.

## Consequences

- A window that spans a restart is a lower bound for the modeled drain and says so through its
  quality. The time across the restart is in no window.
- A real reset in a new boot, with statistics that were young at the earlier snapshot, can be read
  as a restart by the second sign. Its step is then left out: an undercount, where the opposite
  mistake adds whole counters.
- Still as before, and only refusing or degrading: sessions close as "reboot" at a restart, the
  "since boot" reference moves, and measured drain across it falls back to the level change
  although the charge counter runs on.
- Resets have the same weakness the restart had before §5: only the latest reset's snapshot keeps
  its counters, so a day with two resets is partial. Not changed here.
- The phone runs the old build until the deferred update (ADR-0053) and keeps writing inflated
  windows at each restart until then; the sweep in §6 is written to catch those too.
