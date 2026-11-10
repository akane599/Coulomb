# ADR-0050: The benchmark build installs beside the user's Coulomb

- Status: accepted (decided under ADR-0027 without a second opinion: a build-type package suffix,
  reverted by deleting two lines)
- Date: 2026-11-10
- Phase: P4

## Context

The Macrobenchmark target is the S25 Ultra (SPEC 1.3, 16.5). Coulomb's debug build has run on that
phone since 2026-11-06, and its database holds the collection that protocols B and E were measured
on (ADR-0049). The `benchmark` build type had the same package name and the same debug signing key,
so installing it would have replaced that app. It is not debuggable, so `run-as` reads would stop.
The run's onboarding in basic mode would also have reset the owner's setup.

## Decision

The `benchmark` build type adds `.bench` to the application id and is named "Coulomb
benchmark". `:benchmark` targets that package. The suffix is not `.benchmark`, which is the test
APK's own package. A run installs it beside the user's Coulomb. When the run ends, the benchmark
build and the test APK are uninstalled, and the user's Coulomb stays (owner approval, 2026-11-10).

## Consequences

- A benchmark run never touches the user's data or grants.
- Release and debug keep the id in `gradle.properties` (SPEC 0.4). The Shizuku provider's authority
  follows the id, so the two installs do not clash.
