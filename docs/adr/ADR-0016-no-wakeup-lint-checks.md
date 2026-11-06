# ADR-0016: The no-wakeup lint checks live in a `:lint` tooling module

- Status: accepted (amends SPEC 15.2)
- Date: 2026-11-06
- Phase: P2

## Context

SPEC 7.4 requires a lint check that fails the build on any `AlarmManager` call, and on any
`WakeLock.acquire()` without a timeout, outside allowlisted classes. Android Lint loads custom
checks from a JVM jar. SPEC 15.2 has no module for build tooling.

Two facts shaped the wiring:

- Under the app's `checkDependencies`, AGP analyses each library module with that module's own
  `lintChecks`. Checks registered only on `:app` never saw library code, which was verified with
  a temporary violation in `:core:collect`.
- detekt could forbid the import, but it cannot tell `acquire()` from `acquire(timeout)` without
  type resolution, which the build does not run.

## Decision

- A `:lint` JVM module holds `NoWakeupDetector` and its registry, built against `lint-api`
  (`compileOnly`) and tested with `lint-tests`. Issues: `CoulombAlarmManager` (any call into
  `android.app.AlarmManager` or `androidx.core.app.AlarmManagerCompat`) and
  `CoulombUntimedWakeLock` (`acquire()` with no timeout, or a constant timeout over 2 s, the SPEC
  7.4 limit). A timeout that is not a constant is not checked.
- Every Android module loads the checks through the convention plugin (`lintChecks(":lint")`), so
  `:app:lintDebug`, which CI runs, covers all modules.
- The allowlist is lint's own suppression: an allowlisted class carries
  `@SuppressLint("CoulombAlarmManager")` or `@SuppressLint("CoulombUntimedWakeLock")` with the
  reason beside it, so `grep -r SuppressLint` lists it. It starts empty. The shared
  `config/lint/lint.xml` cannot name the issues: the JVM modules do not load the checks, and lint
  rejects an unknown issue id there.
- `checkModuleBoundaries` ignores the `lintChecks` configuration: it is tooling, not a code
  dependency. `:lint` itself may depend on no project module.

## Consequences

SPEC 15.2 lists `:lint` as build tooling. Removing the checks means deleting the module and one
line in the convention plugin. The built-in `Wakelock` and `WakelockTimeout` checks stay on as
well; the Coulomb check adds the 2 s limit and the alarm ban.
