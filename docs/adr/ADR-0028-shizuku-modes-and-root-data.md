# ADR-0028: Shell and root Shizuku are both full modes; root adds kernel data

- Status: accepted (edits SPEC 3.5, 4.1, 4.2, 4.5, 5, 7.2, 14.1, 16.3, 17, 18, 19.1; amends ADR-0027 §1)
- Date: 2026-11-08
- Phase: P4

## Context

On 2026-11-08 the owner replaced every earlier instruction about root with one decision, and asked
for it to be recorded. The same day the owner added two notes: Coulomb is not tested against official
Shizuku, and the thedjchi fork is the one to test with.

## Decisions

### 1. Two modes, one product

- Shizuku started over ADB (server uid 2000) is the full product. Most users have no root, so every
  screen and every feature works completely in this mode.
- Shizuku started as root (uid 0), or Sui, only adds kernel data. That data appears as extra detail
  inside existing screens. No screen or feature needs it.

### 2. Root data in scope now

Four kernel sources, each behind its own capability probe, because a Samsung kernel may close any
of them:

| Source | What it gives | Path (verified) |
| --- | --- | --- |
| S25 Suspend statistics | Real deep-sleep count, failed suspend attempts by step, the last failure's device, errno and step | `/sys/power/suspend_stats/*` |
| S26 Last wakeup reason | The IRQs or abort reason of the latest resume, and its sleep time | `/sys/kernel/wakeup_reasons/{last_resume_reason,last_suspend_time}` |
| S9 Wakeup sources (existing row) | Raw counters per kernel wakeup source | `/sys/class/wakeup/wakeupN/*` |
| S27 Kernel log wakeup records | Each resume's cause (`Resume caused by IRQ …`, `Abort: …`) and each failed suspend, in order | `/dev/kmsg`, filtered to those lines |

Verification:

- Kernel sources, android16-6.12 and android15-6.6 of `kernel/common`:
  - `kernel/power/main.c`: the `suspend_stats` attribute group;
  - `kernel/power/wakeup_reason.c`: the `wakeup_reasons` kobject and its log lines;
  - `drivers/base/power/wakeup_stats.c`: the wakeup class attributes;
  - `kernel/power/suspend.c`: `PM: suspend entry`, `PM: suspend exit` and the failure line;
  - `kernel/printk/printk.c` with `Documentation/ABI/testing/dev-kmsg`: record format, sequence
    numbers, and CAP_SYSLOG when `dmesg_restrict` is set.
- SELinux, `system/sepolicy` at android-16.0.0_r4 and android-17.0.0_r1:
  - `private/genfs_contexts` labels the three sysfs paths `sysfs_suspend_stats`,
    `sysfs_wakeup_reasons` and `sysfs_wakeup`;
  - `private/shell.te` grants shell no file read on any of them, and no `syslog_read`.
- The API 37 emulator (userdebug, kernel 6.12) agrees: as shell every path is denied with an avc
  record; as root every path reads.

A separate libsu layer for other root managers stays in `BACKLOG.md`. So do CPU frequency residency
and thermal zones, which the old T3 row listed and the owner's list does not. `/sys/class/power_supply`
(S23) needs no new work: it is already "T2 if readable, else T3", and the existing read succeeds at uid 0.

### 3. Switching modes needs no restart

- When Shizuku dies or changes mode, Coulomb notices, reconnects, and re-reads the uid on every
  connection.
- Root data turns on at uid 0 and off by itself at uid 2000. No screen crashes or goes blank.
- Every stored piece of data records the mode it was collected in. A span where root data was not
  collected shows as "not collected", never as zero.
- A read cut short by a switch can be retried: nothing is counted twice and nothing goes missing.
- In the first moments after boot, a Shizuku that is not ready yet is re-checked at intervals, with
  no error shown.

How the data is tagged and stored is a schema change, decided in ADR-0029 after a second opinion
(ADR-0027 §2).

### 4. Forks, not official Shizuku

- Official Shizuku 13.6.0 cannot authorize new apps on Android 17 (SPEC 3.5). Coulomb is not tested
  against it and gets no further work for it. The existing 5 s timeout and its explanation stay, since
  they cost nothing to keep.
- Onboarding and the Privileges screen say once, in a quiet line, that Android 17 needs a
  Shizuku fork or Sui.
- Tests use the thedjchi fork (v13.7.0 on the emulator), the plainest of the forks; the others add
  features Coulomb does not need. The phone keeps the fork it already runs.

### 5. Tested on the emulator

A mode-switch test runs on the API 37 emulator, with no phone needed:

1. Start Shizuku as root from a root shell (`su 0` on the userdebug image).
2. Kill it while Coulomb is collecting, then restart it from a shell `adb` session.
3. Then the reverse: from shell mode to root mode.

An automated check proves three things: no fake zeros, no double counting, and no crash. It joins
the P4 gate.

It runs as `tools/emulator/mode-switch.sh`, which ends with `verify_mode_switch.py`. Between
snapshots it writes test markers to the kernel log and holds a userspace wakelock, so the check can
count what each window saw.

Result (2026-11-08, API 37 emulator, thedjchi fork v13.7.0): two runs in a row passed every check,
89 and 85.
- Every snapshot carried the mode it was taken in.
- Windows that touch shell mode or a dead server say "not collected" and hold no kernel rows.
  Windows between two root snapshots cover S25, S9 and S27 whole.
- Each marker was stored once. Markers between root snapshots were counted once; markers written
  across shell mode were counted in no consecutive window.
- Coulomb kept one process throughout, with nothing in the crash buffer.
- The kill landed just after a snapshot in one run and just before one in the other. A kill inside
  the read, about 150 ms, is a race; the unit tests cover each such cut.

The runs found one defect, now fixed: the parser dropped a wakeup source with an empty name, so S9
was never stored on that kernel (ADR-0029 §4).

## SPEC edits

- 3.5: forks are what Coulomb tests with. Official Shizuku on Android 17 is untested. Onboarding
  says once that a fork or Sui is needed.
- 4.1:
  - The T3 row lists the four kernel sources and S23 where shell cannot read it.
  - "another root manager" moves to the backlog. A T3 capability does not survive Shizuku stopping.
  - The rule "T3 is optional. No core screen may require root." becomes the two-mode rule of §1.
- 4.2: the uid is re-read on every connection, and a mode switch needs no restart.
- 4.5: two new rows, Shizuku not ready after boot and mode changed.
- 5: new rows S25 to S27. The S9 row names its counters.
- 7.2: a heavy snapshot reads S9 and S25 to S27 while the connection is root.
- 14.1: the Shizuku step carries the fork line.
- 16.3: the mode-switch test.
- 17, P4 gate: the mode-switch test passes on the API 37 emulator.
- 18: the official-Shizuku item becomes forks, Sui and basic mode. A new item covers mode switching.
- 19.1: the read allowlist adds the two sysfs directories and the filtered kernel log.

## Consequences

- ADR-0027 §1's reading of "T3 is optional" no longer applies. `BACKLOG.md` keeps only the libsu
  layer, CPU frequency residency and thermal zones.
- The P4 gate gains the mode-switch test. The phone is not needed for it.
- `libsu` stays declared in `gradle/libs.versions.toml` and unused until the backlog item is picked.
