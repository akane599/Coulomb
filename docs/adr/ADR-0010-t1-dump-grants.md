# ADR-0010: T1 batterystats dumps need two more grants

- Status: accepted (amends SPEC 4.1, 4.4, 15.4 and 19.1); amended 2026-11-07
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

## Amendment 2026-11-07: no narrower path

At the P3 direction review the owner asked why Coulomb holds INTERACT_ACROSS_USERS, and to use a
narrower path if one exists. None keeps the same data at the same cost. The logic below is identical
on `android-16.0.0_r4` and `android-17.0.0_r1`.

**What the system checks.**

- The proto and checkin branches of batterystats call `getInstalledApplications(MATCH_ANY_USER |
  MATCH_ALL)` under Coulomb's identity (`BatteryStatsService.java`, 16: L3327-3328, L3369-3370;
  17: L3375-3376, L3417-3418).
- Package manager then demands the permission even though Coulomb asks from its own user.
  `updateFlagsForPackage` sets "require the permission when same user"
  (`services/core/java/com/android/server/pm/ComputerEngine.java`, 16: L2768-2778; 17: L2840-2850,
  reached from `getInstalledApplications`, 16: L4607-4614; 17: L4679-4686).
- `hasCrossUserPermission` accepts only three callers: system or root, INTERACT_ACROSS_USERS_FULL,
  or INTERACT_ACROSS_USERS (16: L2207-2227; 17: L2227-2247). INTERACT_ACROSS_USERS is the narrower of
  the two permissions, and no other grant passes.

**Why Coulomb dumps in its own process.**

- Heavy snapshots go in-app first (ADR-0018). A worker bind costs 474 ms on the phone, against a 300 ms
  CPU target per snapshot, and self-drain outranks the functional spec (SPEC 0.2).
- In-app is also the only path while Shizuku is stopped:
  - after every reboot on a phone without root, until the user restarts Shizuku;
  - on Android 17 with official Shizuku 13.6.0 (SPEC 4.5).

**Paths checked.**

| Path | Needs the permission | Why not |
| --- | --- | --- |
| Worker only for S5, S6 and S7 | No | A worker bind per heavy snapshot. While Shizuku is stopped there are no wakelocks, alarms, jobs or history |
| Text forms in-app: `--history-start` for S7, the plain dump for S5 | No: the text branch lists no apps (16: L3412; 17: L3460) | Text history stamps records with wall time without a year, which SPEC 7.8 forbids for ordering, and it is about 4× larger (ADR-0014). The plain dump is a human format with no schema: a third parser per SDK and OEM for data S5 already gives |
| `--usage --proto` in-app (`BatteryUsageStats`, S4) | No: `dumpUsageStats` lists no apps (16: L3004-3037, L3229-3242; 17: L3056-3089, L3277-3290) | Per-UID mAh only. No wakelocks, alarms, jobs, wakeup reasons or history, so it cannot replace S5 or S7 |
| INTERACT_ACROSS_USERS_FULL | Yes | Broader, not narrower |
| Grant before each dump, revoke after | Yes, briefly | Every grant needs the worker, which brings back the bind cost the in-app route avoids. Each grant and revoke is also a privileged write |

The rows that need no permission come from the source; they were not run on the emulator.

**What the permission allows.** It lets an app read across users and profiles. With it, an app could
list another user's or profile's packages, such as a work profile or Samsung's Secure Folder. Coulomb's
own code makes no cross-user call. Only batterystats checks the permission on Coulomb's behalf, so that
it can name the apps of every user in the report. Coulomb has no INTERNET permission (SPEC 1.2), so
nothing it reads leaves the phone except through the user's own export.

**Decision.** Keep it. If the owner vetoes it later:

- Coulomb drops the declaration and the grant;
- S5, S6 and S7 come from the worker only;
- while Shizuku is stopped, the capability state shows them as unavailable with the fix (SPEC 1.2).

**Onboarding copy (P4).** The lab's line, "Battery history in Coulomb's own process", says where but
not why. The P4 privileges step says what the grant buys, for example: "Read the full battery report
while Shizuku is stopped. Android asks for this because the report names apps from every user and
profile on the phone." The permission name stays below it in mono.
