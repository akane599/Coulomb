# ADR-0019: One night for protocol E; a time-compressed stress test instead of the 72 h soak

- Status: accepted (owner decision; edits SPEC 1.3, 16.3, 16.6, 17 and 18); the night's validity rules,
  its wakeup sources and the size projection are corrected by ADR-0020
- Date: 2026-11-07
- Phase: P2

## Context

SPEC 16.6 protocol E asked for 72 h in Full mode with normal use. The P2 gate asked for protocols A and
E to pass in a headless 72 h run. SPEC 1.3 and 18 tied stability to a 72 h soak. The run started on
the phone on 2026-11-06. On 2026-11-07 the owner removed the 72 h wait and asked the agent to apply the
change to `SPEC.md` and record it here.

The first 18 hours of that run were checked in one read-only session. They show why normal use is a weak
gate:
- the phone rebooted several times, none of them caused by Coulomb;
- no screen-off stretch reached 8 h (the longest unplugged one lasted 3 h);
- another app's wakelock kept the CPU awake for nearly all of the screen-off time, so deep sleep
  was never tested.

The run measures the owner's day, not Coulomb.

## Decision

1. **Protocol E is one night on the phone, the same night as protocol B.**
   - The phone runs in Full mode, with the screen off and unplugged, for at least 8 h.
   - At the start and the end of the night, `tools/validation/capture.sh` reads the system's records
     for Coulomb's UID: attributed mAh, partial wakelocks, alarms and wakeups (`dumpsys batterystats
     -c` and `dumpsys alarm`).
   - Pass, while the screen is off:
     - 0 wakeups and 0 alarms from Coulomb;
     - Coulomb's attributed mAh for the night at most 0.1% of capacity;
     - Coulomb's partial wakelocks under 30 s in total.
   - The night's values are the end read minus the start read of the platform's since-charged
     counters. A stats reset or a reboot between the two reads voids the night: a reset zeroes the
     counters, and an unclean reboot loses what was not yet written to disk.
   - Wakeups are the platform's wakeup alarms for the UID, plus the alarm, mobile and Wi-Fi wakeups
     in Coulomb's own windows. Alarms are any Coulomb entry in `dumpsys alarm`.
   - The report also states how long the CPU slept and how long the device spent in deep Doze. A
     night that another app kept awake is then visible, though it does not fail the protocol.
2. **A time-compressed stress test joins SPEC 16.3.**
   - What runs: a 30-day trigger sequence on a simulated event clock, with synthetic data, on the API
     37 emulator, in under 10 minutes.
   - Pass: no crash and no ANR, and heap growth after GC under 10%.
   - Pass: the database stays within the 7.7 projection. SPEC 7.7 gives only the one-year target
     (150 MB), so the projection is defined here: the day-30 size plus the day-15-to-30 growth,
     extended to day 365.
   - That extension errs high. By day 15 the 14-day raw samples have reached their cap. The 30-day
     blobs and history are still growing, and they stop growing after day 30.
   - Heap is compared after a forced GC at day 1, when caches are warm, and at day 30.
3. **The P2 gate no longer waits for the phone.**
   - Gate: 100% of fixtures parse, the stress test passes, and protocols A and E pass on the phone.
   - P3 starts now. The P2 gate closes when the night's report arrives.
   - Protocol A also needs the phone. It runs on the evening of that night, as 30 minutes of video
     before the screen goes off, so one phone visit covers A, B and E.
   - SPEC 17's rule that no phase starts while the previous gate is red gets this one exception.
4. **Long runs are optional and hold no gate.** The run started on 2026-11-06 is such a run.
5. **SPEC 1.3 and 18 follow.**
   - The self-cost criterion is the night's.
   - The stability criterion is the stress test's.
   - The 72 h items in 18 are replaced.
6. **Unchanged: the 0.3%-per-day budget in SPEC 6, 7.1 and rule R20.** It is the runtime budget behind
   the self-diagnostic insight and the Settings line, not a gate. The night's 0.1% over 8 h is the same
   rate. It is now demanded of a screen-off night, where Coulomb should cost least.

## Consequences

- `tools/validation/report.py` takes the night's start and end captures. It reports E with the new pass
  marks and B's level-delta rate. The system graph part of B stays a manual read.
- The collector takes its sampling wakelock only while plugged in (SPEC 7.3 and 7.4). So the night's
  expected partial wakelock time is 0 s, and anything above it is a finding even under 30 s.
- ADR-0015 and ADR-0018 left some measurements to the 72 h run. They now come from the run's
  2026-11-07 checkpoint and from the night's two checkpoints:
  - per-source cost, the heavy snapshot rate, blob count and history rows a day;
  - how often a screen-off read sleeps;
  - how long the catch-up read takes;
  - whether counters move while plugged in.

  The stress test feeds those rates to its synthetic sequence, so its database projection uses
  measured numbers.
- The stress test is instrumented and runs locally on the emulator host, like the other instrumented
  tests (AGENTS.md). CI does not run it.
- `docs/progress.md` marks the P2 gate as waiting for the phone night. Asking the owner before that
  night stays a hard stop (SPEC 0.5).
