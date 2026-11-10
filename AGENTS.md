# Coulomb: agent guide

Coulomb is a Shizuku-powered battery intelligence app for Android 16/17 (`minSdk` 36, `targetSdk` 37).
`SPEC.md` is the single source of truth. Read it end to end before writing code; every MUST is a hard
gate. Never edit `SPEC.md` except through an ADR. When two rules conflict, the higher wins (SPEC 0.2):
data/device safety > measurement honesty > self-drain budget > functional spec > `/impeccable` > Material 3/Android > agent preference.

## Operating rules (SPEC 0.3)

1. Never invent an Android API, hidden method, `dumpsys` flag, settings key or sysfs path. Verify against
   AOSP for API 36 and 37 (tags `android-16.0.0_r4`, `android-17.0.0_r1`) or probe on the API 37 emulator.
   Cite the AOSP file and line range in a code comment.
2. Never trust memory for library versions. Resolve the newest stable at build time and pin it in
   `gradle/libs.versions.toml`. Any non-stable pin needs an ADR.
3. Work strictly in the phases of SPEC 17. A phase ends only when its exit gate passes.
4. Small Conventional Commits. Every commit builds and passes unit tests.
5. Spec silent or ambiguous: choose the more accurate, cheaper-in-battery, easier-to-revert option and write an ADR.
6. Never run destructive device actions without explicit owner confirmation: stats reset, uninstall,
   `settings put`, appops changes, charge control. Note: `dumpsys batterystats --checkin` deletes a system
   file (ADR-0005); use `-c`.
7. Design work is not finished until the `/impeccable` gates in SPEC 11.12 pass.
8. Machines and devices follow the environment section below. Use `adb -s <serial>` everywhere.
9. Report progress per phase in `docs/progress.md`: what shipped, gate results, open questions.

## Autonomy (SPEC 0.6)

Work autonomously; never wait for a "continue" and never ask a question the spec or a sensible default
answers. Ask only at the hard stops: connecting to the phone, destructive device actions, anything
irreversible or public (releases, public repo, force-push to `main`, deleting branches/data), secrets the
agent lacks, changing a non-negotiable (1.2) or the priority order, and the P3 design-direction review.
Keep working on independent tasks while a hard-stop answer is pending.

- Second opinion only for hard or costly-to-reverse decisions (ADR-0027): architecture, data format,
  database schema, external or public API, deleting or reverting something. Use a fresh read-only
  subagent with a neutral brief, then write an ADR. Decide everything else yourself, with a one-line
  reason in an ADR.
- Optional work waits (ADR-0027). Anything the spec calls "optional" or "if time allows" goes to
  `BACKLOG.md` as one line (what it is, where the spec has it) and is not built. Once the mandatory
  phases and SPEC 18 pass, the owner picks from it.
- Stuck (same failure after 3 attempts or ~45 min): debugging subagent with evidence only; then mark
  blocked in `docs/progress.md` and move on.
- Parsers, calibration/estimators and attribution diffs get one independent subagent review before their gate.
- Questions that can wait go to `docs/questions.md` with the default in use; re-read it at each phase start.
- Report once per phase gate. No other check-ins.

## Build and test environment (SPEC 0.5)

Host names, addresses and local paths live in `AGENTS.local.md` (untracked, imported by `CLAUDE.md`).
This file uses role names only.

| Role | Purpose | Access rule |
| --- | --- | --- |
| build server (VPS) | Agent runs here: Gradle, JVM unit tests, parser golden tests, screenshot tests | Always |
| emulator host (owner's PC, KVM) | Emulators and instrumented tests. AVDs `pixel-api37` (default), `pixel-api36` | Connect directly when Tailscale shows it online |
| phone (Galaxy S25 Ultra, `sm-s938b`) | Real-battery work only | Only when unavoidable, and always ask the owner first |

- Default target: the API 37 AVD. API 36 only to reproduce an Android 16 bug; API 35 is unused.
- Reach the emulator host over SSH through Tailscale; forward ADB instead of exposing it. Never assume AVD
  names (`emulator -list-avds`). One emulator at a time, headless (`-no-window -no-audio -no-boot-anim`);
  shut it down when the run ends and leave no background processes.
- Emulator host offline: do not retry in a loop; continue on the build server, queue device tests, note it.
- Phone: before connecting, ask the owner and state what will run, why the emulator cannot do it and how long.
  Declined or deferred: mark the gates "pending real device"; never skip silently.
- Never delete anything on the build server's shared Gradle/SDK volume; the owner manages it.

## CI and pushing

- GitHub-hosted runners only, never a self-hosted runner. The repository is public (since 2026-11-06):
  CI runs on push to `main`, pull requests and manual dispatch (`gh workflow run ci.yml`).
  `*.md` and `docs/**` changes do not trigger CI. The private development history is archived in a
  separate private repository; never push it to the public remote.
- Before every push, run `tools/ci/check.sh all` (the exact Gradle tasks CI runs, in CI's order). Do not push
  if it fails. The tracked pre-push hook enforces this: `git config core.hooksPath .githooks` once per clone.
- Instrumented tests run locally on the emulator host, not in CI.
- The repository is public: never commit host names, addresses, home paths, volume ids or personal data
  (ADR-0004, ADR-0008). License changes and repository settings are the owner's.

## Commands

```bash
tools/ci/check.sh all                                 # everything CI runs (static, unit, lint, manifest, assemble)
./gradlew spotlessApply                               # fix formatting
./gradlew spotlessCheck detekt checkModuleBoundaries  # static analysis
./gradlew test :build-logic:convention:test           # unit tests, incl. build-logic checks
./gradlew :app:lintDebug                              # lint over all modules (checkDependencies)
./gradlew :app:verifyReleaseManifestPermissions       # no INTERNET, allowlist in app/permissions-allowlist.txt
tools/fixtures/capture.sh <serial> <raw-dir>          # read-only SPEC 16.2 capture (raw dir outside repo)
python3 -I tools/fixtures/redact.py <raw-dir> <device> # writes fixtures/<device>/<build>/ (ADR-0004, ADR-0008)
python3 -I tools/capabilities/report.py fixtures/<device>/<build> docs/capabilities/<device>-<build>.md
tools/validation/capture.sh <serial> <raw-dir>        # read-only checkpoint of a validation run (raw dir outside repo)
python3 -I tools/validation/report.py <raw-dir> [--coulomb-rates <json>]  # rates, snapshot cost, protocols A, B, E (SPEC 16.6, ADR-0049)
./gradlew :core:data:testDebugUnitTest --tests '*CaptureRatesTest' -Pcoulomb.capture=<raw-dir> -Pcoulomb.captureOut=<json>  # Coulomb's B rates
tools/emulator/mode-switch.sh <avd-serial> <out-dir>  # root -> kill -> shell -> root run on the AVD, then verify (ADR-0028 §5)
```

## Repository map

- `build-logic/` convention plugins (`coulomb.android.*`, `coulomb.jvm.library`), `ModuleGraph` (SPEC 15.2 rules),
  `ManifestPermissions` (SPEC 1.2/15.4 check). `gradle/libs.versions.toml` holds every version.
- Modules per SPEC 15.2: `:app`, `:core:{model,common,database,datastore,privileged,parsing,analytics,collect,insights,designsystem,ui}`,
  `:feature:{now,drain,appdetail,timeline,health,charging,actions,onboarding,settings}`, `:widget`, `:benchmark`.
  Pure JVM: `:core:model`, `:core:common`, `:core:parsing`, `:core:analytics`.
- Name and application id: `coulomb.appName`, `coulomb.applicationId` in `gradle.properties` (SPEC 0.4).
- `fixtures/<device>/<build>/` redacted golden dumps; `docs/capabilities/` probe reports; `docs/adr/` decisions.

## SPEC.md index

| § | Topic | § | Topic |
| --- | --- | --- | --- |
| 0 | How to use, rules, environment, autonomy | 10 | Screens and information architecture |
| 1 | Mission, principles, non-negotiables, success criteria | 11 | Design system, `/impeccable` protocol and gates |
| 2 | Competitive parity matrix | 12 | Notifications, Live Updates, widgets, tile, automation |
| 3 | Platform targets, Android 16/17 and One UI 9 rules, Shizuku issue | 13 | Action Center (logged, revertible actions) |
| 4 | Privilege architecture: tiers, Shizuku client, worker, failures, capabilities | 14 | Onboarding, permissions, settings, export |
| 5 | Data source catalog S1–S24, parsing rules | 15 | Toolchain, modules, libraries, manifest, code standards, CI |
| 6 | Measurement science: calibration, drain, capacity, attribution, provenance | 16 | Testing, fixtures, performance, accuracy protocols A–G |
| 7 | Collection engine: modes, triggers, samplers, no-wakeup policy, retention | 17 | Delivery phases P0–P6 and exit gates |
| 8 | Domain model and Room schema | 18 | Final acceptance checklist |
| 9 | Insight engine: rules R01–R20, Drain Story | 19 | Worker allowlist, risks, glossary, sources |
