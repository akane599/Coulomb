# ADR-0032: The read layer, reference windows and app labels

- Status: accepted (amends SPEC 15.2)
- Date: 2026-11-08
- Phase: P4

## Context

P4's screens need the same reads in several places:
- the windows behind Now's top drains, Drain, App detail and later the widget;
- the battery state behind Now, Charging and the notification;
- app names and icons everywhere a UID appears.

SPEC 15.2 has no module for these reads. ADR-0031 §4 gives each destination its own ViewModels, so
two screens cannot share a ViewModel to agree on a window.

Facts from the repository:
- The collector writes CONSECUTIVE, SESSION and DAY windows (ADR-0018). REFERENCE windows ("since
  unplugged", "today") were left to be "computed on request in P4". A SESSION window is written
  only when its session closes, so nothing covers the open discharge session.
- `WindowBuilder`, which turns counters into a window, is internal to `:core:collect`. Counters are
  kept for 2 h, except at referenced snapshots and the start of open sessions (ADR-0015). Summing
  CONSECUTIVE windows loses detail: each keeps at most 30 UIDs and folds the rest into OTHER.
- The `uid_meta` table (label, `isSystem`) exists, but nothing writes it.

This is a hard-to-reverse architecture decision, so it took a second opinion (ADR-0027). A fresh
read-only agent compared four placements:
- `:core:insights`;
- a new `:core:data`;
- each feature on its own, with shared formulas in `:core:analytics`;
- `:core:database` depending on analytics.

It recommended a new `:core:data`, kept thin. The decisions below follow it.

## Decisions

1. **A new `:core:data` module holds the read models** (amends SPEC 15.2). It may depend on
   `:core:model`, `:core:common`, `:core:database`, `:core:analytics` and `:core:datastore`.
   - It joins rows, settings and labels into model types, and its flows are shared across screens.
   - It admits only reads with two or more consumers. Screen-only shaping stays in ViewModels, and
     formulas stay in `:core:analytics`.
   - `:core:database` is an `implementation` dependency, so Room entities do not reach features.
   - R: Now's top three, Drain's list and the widget then cannot disagree (SPEC 0.2, measurement
     honesty). Undoing it means inlining the code and deleting one module.
2. **`:core:common` holds the dispatchers.** Reads run on a limited-parallelism IO dispatcher
   (SPEC 15.5). R: SPEC 15.2 already lists dispatchers there.
3. **The collector writes REFERENCE windows on request.**
   - When: a screen posts `APP_OPENED` or `PULL_TO_REFRESH`. If the debounce turns that trigger into
     no snapshot, the windows are built to the latest heavy snapshot instead, unless they already
     end there.
   - Which: one window from `SINCE_UNPLUGGED` while unplugged, and one from `TODAY`. Each replaces
     the previous REFERENCE window, so only the newest is stored.
   - R: these windows come from exact counters with the full UID list. A reference window over a
     long span reads every heavy snapshot in between, so building one at every background snapshot
     would cost battery for a screen nobody is looking at (SPEC 0.2, self-drain budget). Storage
     stays one row per reference.
4. **Ranges no reference covers are sums of CONSECUTIVE windows:** last 24 h, last night and
   custom ranges. They show the OTHER row and say that they are lower bounds. R: their start
   snapshots have lost their counters (ADR-0015).
5. **The collector writes app labels.** When the UID-to-package map changes (`recordPackages`), a
   port in `:core:collect` resolves each changed UID's label and system flag through
   `PackageManager` and writes `uid_meta`. Readers take names from `uid_meta`. Icons come from
   `PackageManager` through SPEC 8.1's 64-entry in-memory cache in `:core:data`.
   - R: one writer, work only when packages change, and names that survive an uninstall
     (ADR-0015).
6. **How SPEC 15.5's "system or hidden API" rule is read.** Calls that need a privilege tier stay in
   `:core:privileged`: the Shizuku or root binder, hidden APIs and shell commands. Public SDK calls
   that any app may make sit in the module that uses them, behind a port, one file per service, with
   the AOSP citation. Examples are `BatteryManager`, `PackageManager` labels and `Settings` reads.
   - R: `:core:collect` already works this way (`AndroidLightSource`, `AndroidPorts`). This states
     that reading instead of moving those calls behind the privileged client, which they do not need.

## Consequences

- `ModuleGraph` and its test gain `:core:data`. `:core:insights` gains `:core:data` and
  `:core:common` when P5 builds the rule engine.
- SPEC 7.5 step 6 re-runs insights after each write, but `:core:collect` may not depend on
  `:core:insights`. In P5, `:app` connects the two.
- Before the first `APP_OPENED` snapshot, a screen shows the stored reference window if it starts at
  the current reference. Otherwise it shows the loading state (SPEC 10.11).
