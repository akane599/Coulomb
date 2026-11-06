# ADR-0007: Rename to Coulomb; owner decisions on SPEC 19.2

- Status: accepted
- Date: 2026-11-06
- Phase: P1 (taken before any P1 code was committed)

## Context

SPEC 0.4 called the name "Joule" and the application id `io.github.akane599.joule` placeholders the
owner may change, and SPEC 19.2 listed three open questions. On 2026-11-06 the owner decided all of them.

## Decision

1. The app is **Coulomb**, application id and base namespace `io.github.akane599.coulomb`. Everything
   named after the old name moves with it: Gradle properties (`coulomb.appName`,
   `coulomb.applicationId`, `coulomb.versionCode`, `coulomb.versionName`), convention plugin ids
   (`coulomb.android.*`, `coulomb.jvm.library`), Kotlin packages, the theme, the application class, the
   worker names in SPEC 4.3 (`CoulombPrivilegedService`, `ICoulombPrivileged`), the GitHub repository
   and every document. The placeholder launcher icon (a gauge arc and needle) has nothing name-specific
   and stays until the P3 design lab replaces it.
2. Wear OS tile: optional in P6; it syncs on level or plug change, or periodically, and never streams
   live data (SPEC 12.6).
3. Root-only charge control: hidden and off, and out of v1 scope. The SPEC 4.1 T3 row, the 13.2 action
   and the 19.1 advanced allowlist entry are marked accordingly; no charge-control code is written.

SPEC 0.4, 4.1, 12.6, 13.2, 19.1 and 19.2 were edited under this ADR; every other "Joule" in SPEC.md was
replaced by "Coulomb" without other changes.

## Consequences

Nothing has been installed under the old application id on the owner's phone (the P0 session was
read-only shell capture), so no data migrates. The local checkout directory keeps its old name for now;
it is not part of the repository. ADRs 0001 to 0006 were updated to the new name in place.
