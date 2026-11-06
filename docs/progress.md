# Progress

One section per phase (SPEC 0.3.9, 17). Newest phase first.

## P2 Data engine (in progress)

### Shipped so far

- S5 proto parser (`BatteryStatsProtoParser`): Wire 7.1.2 classes from the AOSP protos, walked
  field by field so a cut stream keeps every complete UID (ADR-0013).
- Shared line layer: `LineReader` (Okio, byte offsets, 64 KiB line cap, CRLF) and quote-aware
  `Csv.split`.
- S6 checkin parser (`BatteryStatsCheckinParser`, `-c`) producing the same records as S5. It is
  checked against S5 on both fixture sets; the agreement and the known gaps are recorded in the
  ADR-0013 amendment.
- The zero rule in both parsers (ADR-0013 amendment): `ProtoOutputStream` drops empty nested
  messages, so an absent counter is 0, not "not reported".
- Independent review of S5 and S6 (ADR-0013 review amendment), all findings fixed:
  - checkin names with line breaks no longer split into records an app could forge;
  - a dropped UID or an overlong record marks the dump incomplete;
  - a bad column is null, not 0;
  - stray lines are issues without their text.
- S7 history parser (`BatteryHistoryParser`, `-c --history-start`). It streams `HistoryRecord`s to
  a sink in batches of 512, places them from `NEXT:`, flags gaps and marks the restated state after
  each read start, `START` and `RESET` (ADR-0014 amendment). Golden tests use three real emulator
  reads (from 0, from mid-buffer, from `NEXT:`) that agree on every event they share, and the phone's
  40 064 records. The `CHECKIN_HISTORY` worker mode runs the read, and the S7 probe checks for `NEXT:`.
- Independent review of S7 (ADR-0014 second amendment), parser findings fixed:
  - names with up to about 1000 line breaks stay inside their record (the 64-line join cap is gone);
  - after a record whose end is lost (past the 64 KB cap, or a wakelock tag the platform prints
    without doubling its quotes), nothing is read as a record and only a last-line `NEXT:` is kept;
    S6 stops at the same point;
  - a tag missing from the pool marks the read incomplete;
  - a test that could not fail now checks placement against the platform's wall times.
- S21 `dumpsys battery` (`BatteryServiceParser`): AOSP fields typed, OEM `key: value` lines kept
  as a raw map and counted by key, OEM log buffers skipped (they hold the battery's QR code) and
  counted by name. A "values frozen by `dumpsys battery set`" flag.
- S19 `dumpsys thermalservice` (`ThermalServiceParser`): cached and current temperatures, cooling
  devices, static thresholds, the status-override flag; Android 17's per-device lines skipped.
- S11 `dumpsys deviceidle` (`DeviceIdleParser`): the active Doze constants as printed, deep and
  light states, the idling history (oldest first) and the allowlists; Samsung's extra constants
  counted by name.
- S12 `dumpsys power` (`PowerParser`): power manager state, the wakelocks held now and battery
  saver. Wakelock tags are unescaped, so the list is kept only when its lines match
  `Wake Locks: size=N`, and each heading read must appear once (ADR-0013 text-parser amendment).
- S10 `dumpsys alarm` (`AlarmParser`): dump time, pending alarms (soonest first), the pending count
  per UID and per-app `Alarm Stats` with every tag. The service's `IndentingPrintWriter` indents
  each line of a tag, so a tag cannot forge another app's entry.
- S13 `dumpsys jobscheduler` (`JobSchedulerParser`): per-app statistics windows (counts, time
  shares, stop reasons including `timeout_abandoned`) and each registered job's unsatisfied
  constraints, restrictions, bucket and readiness. `Job history` is left to S7.
- Independent review of the six `dumpsys` text parsers (ADR-0013 third amendment), findings fixed:
  - S13: an app's job namespace, printed at column 0 in `Job history`, could add a statistics
    window for another app; no heading is read after `Current stats` now;
  - S12: two over-long wakelock tag lines could keep the count matching around a forged entry, and
    a bare `(uid=5)` line threw; a dropped line now distrusts the list (`TextDump.onLost`);
  - S11: a notification's intent action in the temp allowlist could set the Doze state and leak
    text into `unknown`; sections are read from the copy the print order makes real;
  - Android 17 `u<user>p<n>` UIDs are read; S21 requires no dropped line; `ParseContext` cuts names
    before counting them.
- SPEC 7.4 lint check (`:lint`, ADR-0016): any `AlarmManager` call and any `WakeLock.acquire()`
  without a timeout of at most 2 s fail `:app:lintDebug`. Every Android module loads it, since lint
  analyses library modules with their own checks.
- S8 `suspend_control_internal` is not registered on either fixture device ("Can't find service"),
  so it has no parser yet; the capability probe reports it unavailable (P0 finding).
- Room schema version 1 (`:core:database`, ADR-0015), reworked after an independent second opinion:
  - Coulomb's own boot number (`bootSeq`, `boot` table) keys every time, so an import or an
    unreadable boot count cannot reorder rows;
  - snapshots keep their battery values and each source's outcome;
  - windows have a kind, a session and a charge source, with per-window wakelock and counter
    records;
  - `RESTRICT` protects data kept for good; retention and compaction cut by key, never wall time;
  - the history ring stores codes and interned tags without a time index (31 bytes per event,
    from 65), with each snapshot marking the ids its read stored.
  - `SizeBudgetTest` projects about 131 MB after a year against SPEC 7.7's 150 MB, from the
    rates in ADR-0015. The 72 h run at the gate replaces those rates before the schema freezes.
- `:core:analytics` (ADR-0017), pure functions with unit tests: current calibration (sign, scale,
  charge counter trust), drain rate with provenance and quality, capacity as a weighted median
  with a bootstrap interval, wear, attribution windows split at resets, and the session rules.
- Independent review of the estimators and attribution diffs (ADR-0017 amendment): nine defects
  and five doubts, all nine fixed with tests. Among them: draining while plugged no longer counts
  as charging evidence, a counter derived from the level is coarse, and the shown %/h is always
  Estimated.
- The collector (`:core:collect`, ADR-0018):
  - history placement on Coulomb's clock from the `TIME` pairings, segments per boot, the
    restated-state check, and five noisy history kinds left out (41% of the phone's changes);
  - counter rows from S5, heavy reads in the app's process first (a worker bind costs 474 ms);
  - one actor with the SPEC 7.2 trigger policy, one Room transaction per snapshot: history,
    snapshot, sessions, consecutive, session and day windows over chains split at resets, the
    automatic references (with a hidden `BEFORE_RESET`), UID packages;
  - sessions closed with drain, currents, temperatures and time at 80% and 100%; a calibration
    tracker on the last hour of samples; retention cut by `(bootSeq, elapsedNs)` keys, run once a
    day while charging;
  - Full mode: a `specialUse` foreground service with runtime receivers, a boot and update
    receiver, the SPEC 7.3 samplers with no alarms, and 200 ms wakelocks only while plugged in;
  - one exception to SPEC 7.5, recorded in ADR-0018: a catch-up history read gets 15 s, at most
    once per 30 minutes, because the whole buffer may not fit in 2 s.
- Emulator run on API 37 (ADR-0018, Validation). Update and boot start the service. Heavy
  snapshots take 75–250 ms of wall time. History was stored without gaps across a reboot. Simulated
  unplug, screen and level steps gave the expected sessions, windows and references. A stats reset
  at a full unplug was confirmed. Two defects found there are fixed.

### Open findings

- Power estimates in the phone's checkin dump are above those in its proto dump taken 0.3 s
  earlier, while CPU times match. The two writers ask for different usage stats (`detailed`
  false vs true, ADR-0013 review amendment), so `powerMah` is never compared across sources;
  whether that explains the whole gap is not proven.
- `--history-start` makes system_server walk the whole history buffer and print the whole tag
  pool on every read, and it starts an asynchronous stats write. The collector keeps to one read
  per heavy snapshot; the on-device cost is measured in the P2 performance gate.
- Snapshot blobs: the heavy snapshot's dumps on the phone fixture compress to about 340 KB (S13
  180 KB, S5 85 KB, S10 45 KB). At about 100 heavy snapshots a day, 30 days of blobs (SPEC 7.7)
  would take about 1 GB of storage. The collector's ADR decides what a blob holds (default: the S5
  proto and any source whose parse was incomplete) and caps the folder.
- A state change within a second of a read's last record can be merged into that record after the
  read, and no later read prints it (ADR-0014 second amendment). The collector now compares each
  read's baseline with the stored state and records a `STATE_MISMATCH` gap (ADR-0018). An
  instrumented test on the emulator is still to come.
- Snapshot blobs are settled in ADR-0018: the raw S5 proto (about 340 KB on the phone) for 5 to 10
  heavy snapshots a day, plus the raw text of any source whose parse was incomplete, kept 30 days
  or up to 128 MB.

### Still to do before the P2 gate

- Independent review of the collector (history ingest, windows, engine), then fix its findings.
- Instrumented contract tests on the API 37 emulator.
- The 72 h headless run on the phone, for protocols A and E and the measured rates: per-source
  cost, heavy snapshot rate, blob count, history rows a day, and whether any counter moves
  between two plugged-in snapshots. The owner is asked before connecting.
- Freeze the schema with the measured rates.

## P1 Privileged core (2026-11-06)

### Shipped

- **`:core:model`**: privilege state, tiers, grants and capability types (SPEC 4.5, 4.6).
- **`:core:privileged`** (decisions in ADR-0009):
  - Shizuku client on `dev.rikka.shizuku:api` 13.1.5. `Sui.init` runs from the provider; the `Sui`
    class ships inside the API artifact, which closes the P0 question about a separate Sui artifact.
    Servers below API 13 fail closed.
  - Worker `UserService` over AIDL. Dumps use `Debug.dumpService` into a `ParcelFileDescriptor` pipe
    (no spawned `dumpsys`); small calls return status codes. Every method is on the SPEC 19.1 allowlist
    and checks its arguments in `Validation`, which includes settings key filtering and sysfs paths
    confined to their `/sys/class` entry.
  - Routing goes worker, then T1 in-app, then unavailable. In-app dumps run only when the grants can
    pass the service's own checks. A refusal line or a `SecurityException` in a dump is reported as
    `NOT_PERMITTED`, never as data (ADR-0010).
  - `StreamTransfer` streams to the parser and names the root cause of a failure: timeout, worker
    died, read error, consumer error, producer failed or truncated. It frees the caller 5 s after a
    stuck producer or consumer.
  - The SPEC 4.5 state machine, a grants ledger and re-verification after a fingerprint change. The
    5 s authorization hang flag is raised only if no manager dialog paused Coulomb.
  - Bootstrap of five grants (ADR-0010: PACKAGE_USAGE_STATS and INTERACT_ACROSS_USERS join the three
    in the SPEC). It runs through the worker, falls back to the Shizuku binder when the worker cannot
    bind, and ends with copy-ready ADB lines.
  - Capability prober and report (SPEC 4.6).
  - Debug-only transport drills.
- **`:core:ui`**: SPEC 4.5 issue copy in English and Turkish.
- **`:app`**: starts the privileged core. The debug-only `PrivilegeConsoleActivity` drives the drills
  over `adb shell am start`; the caller must hold DUMP and every argument is bounded.
- **Tests**: 51 host tests in `:core:privileged`:
  - 26 controller tests, which include each core grant revoked on its own, the refusal sniffer and
    the dialog-versus-hang rule;
  - 7 stream, 8 connector, 4 validation, 3 issue-copy and 3 `cmd` tests.
- **Docs**: ADR-0009, ADR-0010 (amends SPEC 4.1, 4.4, 15.4 and 19.1); in-app capability reports
  `docs/capabilities/avd-pixel-api37-16571990-inapp.md` and `avd-pixel-api36-13894323-inapp.md`.

### Gate

| Gate item | Result | Evidence |
| --- | --- | --- |
| Protocol F passes on the device | Pass | Phone drills below. Each grant revoked on its own: unit tests |
| A 20 MB dump streams without OOM | Pass | Phone: 20,971,520 bytes in 108 ms, peak heap 6.1 MB, byte-exact. API 36 AVD: 89 ms, 4.5 MB |
| Bind-timeout fallback demonstrated | Pass | Phone: `BIND_TIMEOUT` after 5.001 s, then an in-app proto dump of 130 KB |
| CI green | Pass | GitHub Actions run on the final P1 commit (manual dispatch) |

Phone run: `sm-s938b`, build S938BXXSDDZJ1 (Android 17), Shevery 14.6.3. It ran with the owner's
approval, limited to the steps the owner listed, and took about 45 minutes.

| Drill (SPEC 16.3) | Result |
| --- | --- |
| Bind and bootstrap | Worker bound in 474 ms as uid 2000, `u:r:shell:s0`; all five grants bootstrapped without a tap |
| Worker dumps | `--proto` 129 KB, `-c` 240 KB, `--history` 282 KB, all complete |
| Kill Shizuku mid-dump | Partial result: `WORKER_DIED` at 7.27 MB with no byte mismatch. Tier fell to T1 with `MANAGER_STOPPED`. The fork's watchdog restarted the server about 12 s later, and Coulomb resumed without a tap |
| Truncated stream | Partial result: `TRUNCATED` at 5 MB of 20 MB |
| Revoke grants | Four permissions revoked and the op reset together: `GRANTS_REVOKED`. One-tap repair worked through the Shizuku binder (worker not bound) and through the worker |
| Fingerprint change (simulated) | `fingerprintChanged=true`; grants re-verified |
| Bind hang (simulated) | Fallback after 5.001 s, as above |
| Worker idle | The worker exits after 60 s idle |

Left on the phone: the Coulomb debug APK and Coulomb's own grants (BATTERY_STATS, DUMP,
PACKAGE_USAGE_STATS, INTERACT_ACROSS_USERS). The GET_USAGE_STATS op is back at its default, as it
was before the run. No other app, setting or battery stats was touched.

Emulators: `pixel-api37` covers the T1 path and official Shizuku 13.6.0's authorization hang, with no
dialog and an empty authorized list. `pixel-api36` covers the full worker path and the same drills.
Both were shut down after the runs.

### Found and fixed during the gate

- An in-app dump that the service refused still looked successful (`Debug.dumpService` returns true
  after writing its refusal line). Every stream is now checked for refusals (ADR-0010).
- With only the three SPEC grants, T1 got no batterystats data at all. Two more grants fix this (ADR-0010).
- A ledger write was lost when a read raced it (`AtomicFile.openRead` deletes a pending `.new` file).
  Reads and writes are now serialised.
- The bind-hang drill needs `release` first; `resetworker` only clears the failure.
- The hang copy ("Shizuku can't authorize apps on Android 17") appeared while the user was still
  reading Shevery's dialog, which waits about 20 s. Fixed with the pause rule (ADR-0009 item 6).
  Covered by unit tests; not yet re-run on the phone.
- The security review (independent subagent) led to these changes:
  - sysfs confinement;
  - the pipe is closed for a refused caller;
  - the stream grace period;
  - masked Bluetooth device names in the probe report;
  - bounded console arguments.

### Pending

- The in-app capability probe on the phone was not in the approved step list and did not run.
  `docs/capabilities/sm-s938b-S938BXXSDDZJ1.md` still holds the P0 shell-level view.
- Protocol F expects "no crash". No crash showed up in the drill output, but the phone's crash log
  buffer was not read. Next approved phone session: read it (read-only, seconds).
- Sui and root (T3) have not been exercised; neither the phone setup nor the emulators run Sui.
- Instrumented `UserService` contract tests (SPEC 16.3) are not written yet; the drills covered the
  contract by hand. They are queued for the start of P2. Official Shizuku cannot authorize on the
  API 37 AVD, so they run on `pixel-api36`. This is an exception to SPEC 16.7 (API 36 only for
  Android 16 bugs) until a working manager runs on the API 37 AVD.

### Owner decisions at the gate

The owner answered all open questions on 2026-11-06 (`docs/questions.md`, Answered):
- INTERACT_ACROSS_USERS stays;
- the license is GPL-3.0-or-later (ADR-0012);
- SPEC uses role names (ADR-0011);
- the project is published from a clean new repository after the owner confirms the secret-scan audit;
- no INTERNET permission.

## Pre-publication audit (2026-11-06, owner request)

The owner planned to make the repository public. Findings and what was done are below.

Published on 2026-11-06 after the owner confirmed the audit:
- `akane599/Coulomb` is public and starts from one clean commit (`cfeef0c`) whose tree is byte-identical
  to the audited `84ac59a`;
- the private development repository was renamed `akane599/Coulomb-archive`, archived and kept private;
- CI now also runs on pushes to `main`.

| Item | Where | State |
| --- | --- | --- |
| Third-party package names (157 apps), their class names and brand words | `fixtures/sm-s938b/` | Pseudonymised with one private mapping (ADR-0008); UIDs and system names unchanged; proto still valid |
| Owner's own apps (`io.github.akane599.*`, `com.akane.*`) | `fixtures/sm-s938b/` | Kept by decision |
| Wi-Fi LAN address in a WifiInfo dump | `fixtures/sm-s938b/…/jobscheduler.txt` | Masked; `redact.py` now masks it on every capture |
| ADR-0004 categories (e-mail, accounts, SSID, battery serial, phone, MAC, device serial) | fixtures | Post-scan: 0 hits. No IMEI-, ICCID- or location-like values found |
| Tailscale host name, Tailscale IP, `/home/…` paths, volume id, ControlMaster notes | `AGENTS.md`, `docs/` | Moved to the untracked `AGENTS.local.md` |
| Tailscale host name and PC description | `SPEC.md` 0.5, 3.1, 16.7 | Replaced by role names (ADR-0011) |
| App names in ADR text (which accounts were found) | `ADR-0004` | Generalised |
| A compiled `.pyc` committed in P0 | `tools/fixtures/__pycache__/` | Removed; `__pycache__/` ignored |
| Commit author | git metadata | GitHub no-reply address only |
| Old commits | git history | Still hold the real package names and machine notes. Decision: publish a new repository from one clean commit of the audited tree, then archive the private one |
| License | repository | GPL-3.0-or-later, SPDX headers, REUSE 3.3 compliant (ADR-0012) |
| Secret scan, 2026-11-06 | `git archive HEAD` (197 files) and all commits | gitleaks 8.30.1: 0 findings in the tree, 0 in history |
| Identifier scan, 2026-11-06 | same tree | No host names, private-network or Tailscale addresses, home paths, keys, serials or IMEI-like numbers. Masked e-mail and SSIDs keep their length. `02:00:00:00:00:00` and the emulator's `10.0.2.16` are platform placeholders. A sample LAN address in a `redact.py` comment was replaced with an RFC 5737 example |

## P0 Foundations (2026-11-06)

### Shipped

- **Repository** on `main`, then a private GitHub remote (archived as `akane599/Coulomb-archive` when the project went public); Conventional Commits.
- **Build**: Gradle 9.8.2 wrapper (checksum pinned), AGP 9.5.1, Kotlin 2.4.21, KSP 2.3.13, JDK 21. Every
  version in `gradle/libs.versions.toml` was resolved from Maven metadata on 2026-11-06 as the newest
  stable (SPEC 0.3.2). Navigation 3 1.2.1 is stable, so it replaces Navigation Compose (SPEC 15.3).
- **Convention plugins** in `build-logic/`: Android application, library, compose, feature, hilt, test;
  JVM library. `explicitApi()` in core modules, Kotlin warnings as errors, Android Lint warnings as
  errors (`checkDependencies` from `:app`), detekt 1.23.8 and ktlint 1.8.0 (Spotless 8.11.0), both with
  Compose rules 0.6.8. Host unit tests run for the debug build type only.
- **Modules** per SPEC 15.2: `:app`, 11 core, 9 feature, `:widget`, `:benchmark` (`:wear` waits for P6).
  `checkModuleBoundaries` enforces the 15.2 dependency table in every module.
- **Manifest permission check** (`verify{Debug,Release,Benchmark}ManifestPermissions`): fails on INTERNET,
  READ_LOGS, location or phone state in the merged manifest, on any permission missing from
  `app/permissions-allowlist.txt`, and on any library whose own manifest requests a forbidden permission
  (91 library manifests scanned). Unit-tested in `build-logic` (including a library asking for INTERNET).
  Every declared permission was checked against AOSP `core/res/AndroidManifest.xml` at android-16.0.0_r4
  and android-17.0.0_r1, with line ranges in the manifest.
- **App shell**: Hilt application, edge-to-edge activity, placeholder adaptive icon with a monochrome
  layer, backup rules excluding the database and snapshots (SPEC 14.5). `coulomb.appName` and
  `coulomb.applicationId` live only in `gradle.properties` (SPEC 0.4).
- **CI** (`.github/workflows/ci.yml`, actions pinned by SHA): spotless, detekt, module boundaries, unit and
  build-logic tests, lint, the three manifest checks, debug/release/benchmark assemble.
- **Fixtures** (SPEC 16.2), captured read-only with `tools/fixtures/capture.sh` and redacted with
  `tools/fixtures/redact.py` (ADR-0004):
  - `fixtures/sm-s938b/S938BXXSDDZJ1/`: Galaxy S25 Ultra, One UI 9, Android 17, captured with the owner's permission.
  - `fixtures/avd-pixel-api37/16571990/`: API 37 emulator on the emulator host.
- **Capability reports** (SPEC 4.6, shell level = T2 view): `docs/capabilities/sm-s938b-S938BXXSDDZJ1.md`,
  `docs/capabilities/avd-pixel-api37-16571990.md`, generated by `tools/capabilities/report.py`.
- **Docs**: `AGENTS.md` (rules, environment, commands, SPEC index; `CLAUDE.md` imports it), ADR-0001 to
  ADR-0006, `docs/questions.md`, `PRODUCT.md` from `/impeccable init`.

### Gate

| Gate item | Result | Evidence |
| --- | --- | --- |
| CI green | Pass | GitHub Actions run on the final P0 commit (see the commit list) |
| Fixtures committed | Pass | `fixtures/sm-s938b/S938BXXSDDZJ1/`, `fixtures/avd-pixel-api37/16571990/` |
| Capability report committed | Pass | `docs/capabilities/sm-s938b-S938BXXSDDZJ1.md` (+ emulator) |
| Manifest check proves no INTERNET | Pass | CI step "Manifest permission check"; `ManifestPermissionsTest`; `app/build/reports/permissions/release.txt` |

### Decisions to review

- ADR-0003: `WAKE_LOCK` declared (needed by SPEC 7.3, missing from 15.4); WorkManager's
  `ACCESS_NETWORK_STATE` and LeakCanary's legacy storage permissions removed.
- ADR-0004: fixtures are redacted with same-length masks; package names kept.
- ADR-0005: `dumpsys batterystats --checkin` deletes the system checkin file and can return the previous
  cycle (AOSP BatteryStatsService); `-c` is used in 16.2, S6 and 19.1 instead.
- ADR-0006: DESIGN.md arrives in P3; the installed `/impeccable init` writes PRODUCT.md only.
- Inside the spec's options, no ADR: Roborazzi 1.77.0 for screenshot tests (Compose Preview Screenshot Testing is
  still `0.0.1-alpha16` on Google Maven), Spotless as the ktlint runner, detekt 1.23.8 (2.0 is still alpha).

### Findings that shape later phases

- `suspend_control_internal` is not registered on the S25 Ultra or the emulator: S8 is unavailable;
  kernel wakelocks come from batterystats proto/checkin at T2, and `/sys/class/wakeup` (S9) needs root.
- One UI 9 denies the shell user `/sys/class/power_supply` (S23 is root-only). `dumpsys battery` (S21)
  carries Samsung extras: `FirstUseDate`, `current now` (positive while charging on AC), LLB dates.
- `powerstats` is registered on the S25 Ultra but its dump is empty: no rails through the shell. The
  `SystemHealthManager` probe (P1) decides S16.
- Samsung battery-protection settings keys seen: `battery_protection_threshold`,
  `battery_protection_recharge_level`, `battery_protection_default_value` and more (S22 candidates; the
  app still probes at runtime and never hardcodes them).
- Phone dumps are large (history 2.4 MB, jobscheduler 2.3 MB): parsers must stream (SPEC 3.3).

### Queued for later phases

- P1: verify the Sui API artifact (`dev.rikka.sui:api` is not on Maven Central; `Sui` may ship inside
  `dev.rikka.shizuku:api`); in-app capability probes replace the PENDING rows.
- P2: lint check for `AlarmManager` / un-timed `WakeLock.acquire()` (SPEC 7.4); Room schema.
- P3/P4: check whether Material 3 Expressive APIs need a pre-stable artifact (11.7); screenshot diff step in
  CI; baseline profile plugin; confirm bundled `.so` files are 16 KB aligned (SPEC 3.2).
- Before Gradle 10: detekt 1.23.8 uses two deprecated Gradle APIs (`ReportingExtension.file`,
  `Configuration.setVisible`); move to detekt 2.x once stable.

### Environment notes

- The build server's shared Gradle volume was full; builds use a separate `GRADLE_USER_HOME` on the root
  disk (machine details in the untracked `AGENTS.local.md`).
- SSH to the emulator host needs Tailscale SSH browser approval for new connections; an existing
  ControlMaster socket was reused, with the emulator's adb port forwarded to the build server.
- The emulator run is finished: `pixel-api37` was shut down, nothing left running on the emulator host.

### Open questions

See `docs/questions.md` (fixture package names, the full Gradle volume, CI minutes, SPEC 19.2 defaults).
