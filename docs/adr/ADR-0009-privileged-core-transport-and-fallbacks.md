# ADR-0009: Privileged core: transport, routing and fallbacks

- Status: accepted
- Date: 2026-11-06
- Phase: P1

## Context

SPEC 4 fixes the tiers (T0 unprivileged, T1 grants in Coulomb's own process, T2/T3 the Shizuku worker),
the worker allowlist (19.1) and the failure states (4.5), but leaves several implementation choices
open. The P1 code made each of them; they are recorded here so later phases do not undo them by accident.

## Decisions

1. **Dumps use `Debug.dumpService`, not a spawned `dumpsys`.** In the worker and in-app (T1) alike,
   `Debug.dumpService(name, fd, args)` (public; `core/java/android/os/Debug.java`, android-16.0.0_r4
   L2678-2692, android-17.0.0_r1 L2706-2720) makes the system service write straight into a pipe.
   No child process, no shell parsing, no argv injection surface, and the same code path serves T1
   and the worker. `ServiceManager.checkService` (hidden, worker only) tells "not registered" from
   "dump failed"; when the lookup is unavailable the dump is simply tried.
2. **Large outputs cross binder as `ParcelFileDescriptor` pipes; small calls return an `out int[]
   status`.** `ServiceSpecificException` is `@SystemApi` and not in the SDK, so status codes travel
   in an out array (`WorkerStatusCode`). The client streams and parses incrementally (`StreamTransfer`)
   and classifies every failure root cause first: timeout, worker died, read error, consumer error,
   producer failed, then content truncated (`isComplete`).
3. **Routing is worker -> T1 -> unavailable.** Streaming dumps fall back to T1 only when Coulomb's
   grants can pass the service's own checks (batterystats: ADR-0010; other services: DUMP). A
   refusal line in the stream is reported as `NOT_PERMITTED`, never as data (ADR-0010). `runQuery`,
   settings and sysfs are worker-only: T1 cannot run `cmd` and SELinux denies most sysfs nodes to
   apps. Sysfs paths must stay inside the `/sys/class` entry they start from; inner symlinks such as
   `device` are refused (SPEC 19.1).
4. **Settings: key names leave the worker, values only for allowlisted keys.** `listSettingKeys`
   filters to the battery-relevant allowlist inside the worker; `getSetting` refuses other keys.
5. **Shizuku server API 13 is the minimum; older servers fail closed** (`SERVER_TOO_OLD`, with the ADB
   commands as the way forward). The 13.x client API is the one the app is built against.
6. **The authorization hang (official Shizuku on Android 17, SPEC 3) is a soft flag after 5 s.** The
   request stays open and a late grant is still accepted; the UI shows the hang copy (Android 17
   wording only when `SDK_INT >= CINNAMON_BUN`) and the ADB commands. The flag is raised only if no
   Coulomb activity paused after the request: a manager dialog on top pauses Coulomb, and the user
   may still be reading it (the Shevery 14.6.3 dialog on the phone waits about 20 s). Official
   Shizuku 13.6.0 on the API 37 emulator shows no dialog, so its hang is still flagged.
7. **The bootstrap falls back to the Shizuku binder for grants only.** When the worker does not bind
   (the 5 s bind timeout), `pm grant` / appops are attempted through `ShizukuBinderWrapper` around
   `permissionmgr` and `appops` (reached with HiddenApiBypass). Nothing else uses that path. Every
   grant is re-read with `checkSelfPermission`/`checkOpNoThrow` before it counts as granted.
8. **A small ledger until Room exists.** `no_backup/privilege-ledger.properties` (AtomicFile) remembers
   whether the core grants were verified and under which `Build.FINGERPRINT`. It drives
   `GRANTS_REVOKED` and the fingerprint-change re-verification (SPEC 4.5). P2 moves it into Room.
9. **Transport drills are debug-only.** `streamTestPattern` (worker) and `PrivilegeDrills`
   (simulated fingerprint change, simulated bind hang, worker reset) refuse to run unless the build is
   debuggable; the worker checks its own flag too. The debug-only `PrivilegeConsoleActivity` drives
   them over `adb shell am start` and requires the caller to hold DUMP (shell does; an app only after
   a `pm grant` over ADB or Shizuku), and it bounds every drill argument.
10. **`moe.shizuku.manager.permission.API_V23` is on the release permission allowlist.** The Shizuku
    provider library declares it; the manager checks it before handing over its binder. The provider
    itself is declared in `:core:privileged`'s manifest, which owns every Shizuku dependency.
11. **Detekt is tuned, not silenced, for this code.** `MaxLineLength` matches ktlint (140);
    `ReturnCount` allows three returns and ignores leading guard clauses; `TooManyFunctions` ignores
    private and overriding functions; `MagicNumber` ignores enum arguments. Three broad catches at
    the binder and parser boundaries, and the single `PrivilegedAccess` facade, carry a commented
    `@Suppress`.

## Consequences

Parsers only ever see an `InputStream`, so the same parser serves the worker and T1. A device where
the worker never binds still gets its grants and T1 dumps. Moving the ledger into Room in P2 needs a
one-time migration from the properties file. Any new privileged method must be on the SPEC 19.1
allowlist, validates its arguments in `Validation`, and returns a status code rather than throwing.
