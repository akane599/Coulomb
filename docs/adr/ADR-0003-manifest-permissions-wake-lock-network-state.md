# ADR-0003: WAKE_LOCK declared, ACCESS_NETWORK_STATE and legacy storage removed

- Status: accepted
- Date: 2026-11-06
- Phase: P0

## Context

SPEC 15.4 lists the permissions Coulomb declares, and SPEC 2 ("Privacy") says "no network permission
at all". The first merged-manifest check showed three gaps:

- SPEC 7.3 and 7.4 allow a short partial wakelock (≤ 200 ms per sample, 2 s timeout) for sampling while
  charging. That needs `android.permission.WAKE_LOCK` (normal; AOSP `core/res/AndroidManifest.xml`
  L2827-2830 at android-16.0.0_r4, L3014-3018 at android-17.0.0_r1), which 15.4 omits.
- WorkManager (Lite mode, SPEC 7.1; also pulled in by Glance) adds `WAKE_LOCK` and
  `ACCESS_NETWORK_STATE`. The latter (normal; L2212-2215 / L2336-2340) only reads connectivity state
  and is used for network constraints, which Coulomb never sets.
- LeakCanary (debug only) adds `READ_EXTERNAL_STORAGE` and `WRITE_EXTERNAL_STORAGE`, which grant nothing
  to an app targeting API 37 under scoped storage.

## Options

1. Allowlist everything libraries add.
2. Declare what Coulomb needs (WAKE_LOCK), strip what it does not use, and keep the check strict.

## Decision

Option 2.

- `WAKE_LOCK` is declared in `app/src/main/AndroidManifest.xml` and allowlisted. Its use stays bound by SPEC 7.4
  (plugged in only, released in `finally`, lint-enforced from P2).
- `ACCESS_NETWORK_STATE` is removed with `tools:node="remove"`. WorkManager only needs it for network
  constraints; any Coulomb work request with a network constraint is a bug.
- The legacy storage permissions are removed in `app/src/debug/AndroidManifest.xml`; LeakCanary falls
  back to app-private storage.
- The permission check also scans every library's own manifest, so a library that asks for INTERNET
  fails the build even if a manifest strips it.

## Consequences

Release permission set: the 15.4 list plus `WAKE_LOCK` and androidx.core's
`${applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature-level, guards non-exported dynamic
receivers). Reverting the network-state removal is one manifest line. Listed under "Decisions to review".
