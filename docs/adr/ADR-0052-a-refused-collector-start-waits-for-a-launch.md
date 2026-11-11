# ADR-0052: A refused collector start waits for the next launch

- Status: accepted (decided under ADR-0027 without a second opinion: a caught exception and a log
  line, reverted by deleting the `try`)
- Date: 2026-11-11
- Phase: P4

## Context

The Macrobenchmark run on the phone (2026-11-11) crashed the benchmark build twice in
`BootReceiver`, with `ForegroundServiceStartNotAllowedException`.

What happened:
1. Macrobenchmark force-stops the app between starts.
2. Before the next start it sends an explicit broadcast to the app's profile-install receiver, and
   that starts the process.
3. A force-stopped app whose process starts again gets `LOCKED_BOOT_COMPLETED` and
   `BOOT_COMPLETED` from the system (`ActivityManagerService.maybeSendBootCompletedLocked`,
   android-17.0.0_r1 L5817-5901; android-16.0.0_r4 L5166-5251, behind the `stayStopped` flag).
4. That broadcast carries no temporary allowlist. The one a real boot sends does
   (`UserController`, android-17.0.0_r1 L1094-1153, android-16.0.0_r4 L1073-1132).
5. The receiver's `startForegroundService` is refused, and the exception ends the process.

The user's Coulomb would crash the same way after a force-stop if a broadcast, a provider call
or a widget update woke its process before a launch, for example Shizuku's server handing its
binder to the app's provider.

## Decision

`BootReceiver` catches `ForegroundServiceStartNotAllowedException` and logs it. Full mode then
starts the next time Coulomb is opened (`MainActivity.onCreate` starts the collector).

Not chosen:
- **A job or an alarm to retry.** It would undo a force-stop the user chose, and it would add a
  wakeup that SPEC 7.4's no-wakeup policy forbids.
- **Telling the two `BOOT_COMPLETED` broadcasts apart.** Nothing public separates them, and the
  catch covers both.

## Consequences

- No crash, and the platform's own force-stop is respected.
- A gap in collection after a force-stop lasts until the next launch. It is recorded like any
  other stretch without snapshots.
- `:core:collect`'s Robolectric tests ran at API 23, below the minimum SDK, because the module had
  no `robolectric.properties`. There `ContextCompat` takes its pre-API 26 path, so a receiver test
  could not see a refused start. The module now runs at SDK 36, like `:core:database` and
  `:core:data`.
