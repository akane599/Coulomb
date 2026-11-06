# ADR-0005: Use `dumpsys batterystats -c`, never `--checkin`

- Status: accepted
- Date: 2026-11-06
- Phase: P0

## Context

SPEC 5 (S6), 16.2 and 19.1 name `dumpsys batterystats --checkin`. In AOSP
`services/core/java/com/android/server/am/BatteryStatsService.java` (android-17.0.0_r1), `--checkin` sets
`isRealCheckin` (L3154-3156). For a real checkin, when the saved checkin file
(`batterystats-checkin.bin`) exists, the service dumps that file (the stats saved at the last reset,
not the current ones) and then calls `mStats.mCheckinFile.delete()` (text path L3419-3439, proto path
L3377-3397). android-16.0.0_r4 has the same code (`--checkin` L3102-3104, `-c` L3143,
`delete()` at L3349 and L3391).

So `--checkin` has two problems:

1. It is a destructive device action: it deletes a system file owned by the platform's checkin flow
   (SPEC 0.2 item 1, SPEC 0.3 rule 6).
2. It can return the previous cycle instead of the current one (SPEC 0.2 item 2, measurement honesty).

`-c` (L3193-3205) selects the same checkin text format for the current stats, adds the history section
(`DUMP_INCLUDE_HISTORY`), and touches no files.

## Decision

Use `dumpsys batterystats -c` wherever the spec says `--checkin`: the fixture capture (16.2), the S6
fallback, and the worker allowlist (19.1). `--checkin` must never be sent to a device.

## Consequences

- The checkin parser (P2) must accept history lines inside `-c` output.
- `SPEC.md` is unchanged; this ADR records the substitution. Listed under "Decisions to review".
