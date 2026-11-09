# ADR-0042: Health sources: design capacity, system and OEM readings

- Status: accepted
- Date: 2026-11-09
- Phase: P4

## Context

The Health screen (SPEC 10.8) shows Coulomb's capacity estimate with health %, beside the system's
and the OEM's values with their sources (SPEC 6.3). ADR-0037 wrote the estimate at each session close
but left health %, the system's state of health and the OEM's value null until their sources came.
Health % needs the design capacity: `PowerProfile` `battery.capacity`, else the kernel's design
value, else the owner's entry (SPEC 6.3).

The source catalog marks S20, the power profile, as a worker read (T2). The worker's shell
allowlist (SPEC 19.1) has no operation for it: `dumpsys batterystats --power-profile` exists
(BatteryStatsService.java android-17.0.0_r1 L2995, L3274) but is not listed, and instantiating the
hidden class needs a new worker call.

## Decisions

1. **Design capacity.** Read once per build while none is stored, at the first boot the collector
   enters:
   - S20 in the app: the same framework resource `PowerProfile` reads,
     `com.android.internal.R.xml.power_profile`, through the app's own `Resources`
     (`getIdentifier` and `getXml`), walking `<item name="battery.capacity">`. Device overlays
     apply to the app's view of framework resources as they do to the system's. PowerProfile.java
     is identical at android-16.0.0_r4 and android-17.0.0_r1 (L246, L345-346, L384-388, L401-440).
   - Else S23 through the worker: `battery/charge_full_design` (µAh) under
     `/sys/class/power_supply`, already allowlisted.
   - Else the owner's entry, from the Health screen. A stored value is never read over, so an
     entry survives later reads.
   - A value outside 500 to 20,000 mAh is a placeholder and is dropped.
   - R: the more accurate option is a tie (one resource), and the in-app read costs no worker call,
     works at every tier and reverts by deleting one class. The S20 row now says so.
2. **The day's readings** are read at most once a day, at a heavy snapshot, and go into the day's
   health summary with the estimate:
   - S3 `BATTERY_PROPERTY_STATE_OF_HEALTH` (id 10, BATTERY_STATS) where it is 1 to 150, as the
     capability probe checks it;
   - the OEM's values from S21 (decision 3).
3. **OEM values** come from `dumpsys battery`'s extras, by keys seen on a device and kept in a
   redacted fixture. AOSP defines none. Today that is Samsung's (One UI 9):
   - `mSavedBatteryAsoc` and `mSavedBatteryBsoh`, both percentages (100 and 96 on a weeks-old
     battery). Which one Samsung's Settings shows is not known (`docs/questions.md`), so Coulomb
     keeps both, each named by its key, and lets neither stand for "the" health;
   - `battery FirstUseDate` (yyyyMMdd) for the first-use date when S3 has none.
   - Stored in `health_summary.oemHealth` as `key=percent` pairs joined by `;` (`OemHealthCodec`).
   - Values frozen by `dumpsys battery set` are ignored. Another OEM gets none until its keys are
     seen on a device.
   - R: showing a value with its key is honest about what is known; SPEC 6.3 forbids silently
     picking one.

## Consequences

- `HealthSource` (port), `AndroidHealthSource`, `OemBatteryReader`, `OemHealthCodec`,
  `DesignCapacitySource`. No schema change: the columns existed (ADR-0037).
- Tests: `OemBatteryReaderTest` on both fixtures; `CollectorEngineTest` follows the design
  capacity into health % and keeps a stored value.
- To verify on the API 37 AVD: the in-app power-profile read against `dumpsys batterystats
  --power-profile`. On the phone (ask first): which Samsung value its Settings shows.
- `OemBatteryReader` is a parser and gets its independent review with the Health estimator review
  before the P4 gate (AGENTS.md).
