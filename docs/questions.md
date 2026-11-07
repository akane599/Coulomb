# Questions that can wait

The agent keeps working with the stated default until the owner answers (SPEC 0.6). Re-read at the
start of every phase. Answered questions move to the bottom with the answer and date.

## Open

- 2026-11-07. Protocol B's capacity: the night report turns the level change into mAh with the capacity
  the platform reports (`pws`, the summary's capacity). Coulomb's own estimate (SPEC 6.3) could replace
  it once it exists. Default: the platform's capacity, so B does not test Coulomb against itself.
- 2026-11-07. Protocol B's rounding: levels are whole percents, so a night's level-delta rate is known
  only to about 1/hours %/h. A gap above the 0.1 %/h pass mark but within that rounding reads
  "inconclusive" (ADR-0020). Default: inconclusive is neither pass nor fail; a second night decides.

## Answered

- 2026-11-06. License: GPL-3.0-or-later, with the Apache-2.0 `NOTICE` for the AOSP protos kept as planned.
  `LICENSE`, `LICENSES/`, SPDX headers and `REUSE.toml` were added as publish prep (ADR-0012).
- 2026-11-06. Host names in SPEC: replaced by role names (ADR-0011).
- 2026-11-06. Publishing: a new public repository from one clean commit of the audited tree. Before that,
  a secret scan of the tree is reported to the owner. Afterwards the private repository is archived and
  kept private, not deleted. Nothing is created or published until the owner confirms the audit.
- 2026-11-06. INTERACT_ACROSS_USERS: declared and bootstrapped (ADR-0010 default). The owner asked
  again on 2026-11-07: use a narrower path if one exists. None keeps the same data at the same cost, so
  it stays; the reasons are in ADR-0010's 2026-11-07 amendment.
- 2026-11-07. P3 direction review: the direction is approved, the lab closes as it is, ten critique
  defaults are accepted, and App detail leads with the open Act insight (ADR-0026).
- 2026-11-06. INTERNET: not needed and not declared. SPEC 1.2 forbids it, and no feature needs it:
  - manager links open in the browser through an intent;
  - fonts are bundled;
  - export goes through the system file picker;
  - updates come through Obtainium outside the app;
  - the optional Wear tile uses the Wearable Data Layer.

  The manifests of `play-services-wearable` 20.0.1 and its `base`, `basement` and `tasks`
  dependencies declare no permissions. The README states "no network access".
- 2026-11-06. Phone fixtures: pseudonymise third-party package names with one consistent mapping; the
  owner's own apps keep their names (ADR-0008). Replaces the earlier "keep package names" default.
- 2026-11-06. Shared Gradle volume: the owner frees it. Keep the separate Gradle home until the owner
  confirms; never delete anything on the shared volume.
- 2026-11-06. CI: GitHub-hosted runners only. While private, CI runs on pull requests and manual dispatch;
  once public, also on push to `main`. `*.md` and `docs/**` are ignored; concurrency cancellation and the
  Gradle cache stay. `tools/ci/check.sh all` runs before every push; instrumented tests stay local.
- 2026-11-06. SPEC 19.2: the app is Coulomb (`io.github.akane599.coulomb`); the Wear tile is optional in P6,
  syncs on change or periodically and is never live; charge control is hidden, off and out of v1 scope (ADR-0007).
- 2026-11-06. Phone fixture and capability session for P0: approved (read-only).
- 2026-11-06. The idle `pixel-api35` emulator left by another session on the emulator host could be shut
  down to run `pixel-api37`. It was shut down and not restarted; `pixel-api37` was shut down after the run.
