# ADR-0010: T1 batterystats dumps need two more grants

- Status: accepted (amends SPEC 4.1, 4.4, 15.4 and 19.1)
- Date: 2026-11-06
- Phase: P1

## Context

SPEC 4.4 bootstraps three grants: `pm grant` BATTERY_STATS and DUMP, and `appops set GET_USAGE_STATS
allow`. SPEC 4.5 says that when Shizuku cannot authorize apps (official Shizuku 13.6.0 on Android 17),
"T1 grants via ADB keep Coulomb working". On the API 37 emulator with exactly those three grants,
the in-app `batterystats` dump returned one line of text instead of data:

```
Permission Denial: can't dump BatteryStatsService from from pid=…, uid=… due to missing a…
```

The AOSP sources explain it (same code on `android-16.0.0_r4` and `android-17.0.0_r1`):

1. `DumpUtils.checkUsageStatsPermission` requires the PACKAGE_USAGE_STATS **permission** in addition
   to the op (`core/java/com/android/internal/util/DumpUtils.java` L119-162). `appops set` does not
   grant the permission. It is `signature|privileged|development|appop|retailDemo`, so `pm grant`
   works.
2. With that granted, `--proto` and `-c` failed next: `SecurityException: MATCH_ANY_USER flag
   requires INTERACT_ACROSS_USERS permission`. `BatteryStatsService.dumpUnmonitored` calls
   `getInstalledApplications(MATCH_ANY_USER | MATCH_ALL)` under the caller's identity in both
   branches (`services/core/java/com/android/server/am/BatteryStatsService.java`, 16: L3327-3328,
   L3369-3370; 17: L3375-3376, L3417-3418). INTERACT_ACROSS_USERS is
   `signature|privileged|development|role` (core `AndroidManifest.xml` 16: L3462-3463,
   17: L3821-3822), so `pm grant` works.

The worker runs as shell or root and is not affected. Without these grants, a user who can only use
ADB gets no S5/S6 data and no S7 history at all.

## Options

1. Keep the three grants and document that T1 has no batterystats dumps.
2. Add `pm grant PACKAGE_USAGE_STATS` only. This restores the text history (S7), but not proto or
   checkin (S5/S6).
3. Add both `pm grant PACKAGE_USAGE_STATS` and `pm grant INTERACT_ACROSS_USERS`, and declare
   INTERACT_ACROSS_USERS.

## Decision

Option 3. It is the only option that keeps the S5 path alive for ADB-only users, and it is the
accurate one: the app shows what the device can really give. INTERACT_ACROSS_USERS is used only by
the system service on Coulomb's behalf. It grants nothing on its own; it needs an explicit
`pm grant` over Shizuku or ADB. Coulomb has no network permission. Reverting means deleting one
manifest line and one bootstrap step.

The bootstrap (SPEC 4.4) becomes:

```
pm grant io.github.akane599.coulomb android.permission.BATTERY_STATS
pm grant io.github.akane599.coulomb android.permission.DUMP
pm grant io.github.akane599.coulomb android.permission.PACKAGE_USAGE_STATS
appops set io.github.akane599.coulomb GET_USAGE_STATS allow
pm grant io.github.akane599.coulomb android.permission.INTERACT_ACROSS_USERS
```

Each grant is still verified with `checkSelfPermission` / `AppOpsManager`. In-app dumps are
attempted only when the grants can pass the service's own checks: batterystats needs DUMP, the usage
permission and the op, plus INTERACT_ACROSS_USERS for `--proto` and `-c`; other services need DUMP.

The emulator also showed that a refused dump looks like a successful one: `Debug.dumpService`
returns true after the service writes its refusal line. So every dump stream, from the worker or
in-app, is checked for the two refusal forms before the parser sees it:

- `Permission Denial: can't dump` (DumpUtils);
- `Security exception:` (`Binder.doDump`, `core/java/android/os/Binder.java` 16: L1100-1107,
  17: L1005-1012).

A refused dump, or a `SecurityException` thrown by the dump call, is reported as
`Unavailable(NOT_PERMITTED)` and never as data.

## Consequences

The SPEC edits: 4.1 (the T1 row), 4.4 (the commands), 15.4 (INTERACT_ACROSS_USERS declared) and
19.1 (the bootstrap row). `app/permissions-allowlist.txt` lists the new permission. The owner can
veto the new permission (`docs/questions.md`); option 2 is the fallback.
