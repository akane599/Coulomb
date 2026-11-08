# Coulomb — Master Build Spec

Android 16/17 · Shizuku-powered battery intelligence app · Nov 6, 2026 · @Tuğberk Özgen

## 0. How to use this spec

This document is the single source of truth for building **Coulomb**, a Shizuku-powered battery intelligence app for Android 16 and 17. The building agent reads it end to end before writing code and treats every MUST as a hard gate.

### 0.1 Files the agent creates in Phase 0

| File | Content |
| --- | --- |
| `SPEC.md` | This document, exported verbatim. Never edited by the agent except through an ADR. |
| `CLAUDE.md` + `AGENTS.md` | The operating rules in 0.3, plus a section index pointing into `SPEC.md`. Max 150 lines. |
| `PRODUCT.md`, `DESIGN.md` | Created by `/impeccable init`, then aligned with sections 1 and 11. |
| `docs/adr/ADR-NNNN-*.md` | One Architecture Decision Record per deviation, ambiguity or version substitution. |
| `docs/capabilities/<device>-<build>.md` | Capability probe report per test device and OS build (section 4.6). |
| `fixtures/<device>/<build>/` | Raw dumps captured from real devices for parser golden tests (section 16.2). |

### 0.2 Keywords and priority

MUST, MUST NOT, SHOULD and MAY follow RFC 2119. When two rules conflict, the higher item wins:

1. User data safety and device safety.
2. Measurement honesty: never show a number with more confidence than the data supports.
3. Self-drain budget: Coulomb must not cause the drain it measures.
4. Functional requirements in this spec.
5. `/impeccable` design rules (section 11).
6. Material 3 and Android platform guidance.
7. The agent's own preferences.

### 0.3 Agent operating rules

1. Never invent an Android API, hidden method, `dumpsys` flag, settings key or sysfs path. Verify each against AOSP source for API 36 and 37, or probe it on the API 37 emulator (0.5). Cite the AOSP file and line range in a code comment.
2. Never trust memory for library versions. Resolve the newest stable version at build time and pin it in `gradle/libs.versions.toml`. Record any non-stable pin in an ADR.
3. Work strictly in the phases of section 17. A phase ends only when its exit gate passes.
4. Small commits with Conventional Commit messages. Every commit builds and passes unit tests.
5. When the spec is silent or ambiguous, choose the option that is more accurate, cheaper in battery and easier to revert. Write an ADR.
6. Never run destructive device actions on a test device without explicit human confirmation: stats reset, uninstall, `settings put`, appops changes, charge control.
7. Design work is not finished until the `/impeccable` gates in section 11.12 pass.
8. Machines, devices and access permissions follow 0.5. Use `adb -s <serial>` everywhere; never assume a USB cable or a local emulator.
9. Report progress per phase in `docs/progress.md`: what shipped, gate results, open questions.

### 0.4 Naming

Name **Coulomb**, application id `io.github.akane599.coulomb` (renamed from Joule, ADR-0007). Keep both in one Gradle property.

### 0.5 Build and test environment

Three machines share one private network, and each has its own access rule. This file uses role names only; host names, addresses and local paths live in the untracked `AGENTS.local.md` (ADR-0011), so hosts can change without touching `SPEC.md`.

| Node | Role | Access rule |
| --- | --- | --- |
| Build server (VPS) | Where the agent runs: Gradle builds, JVM unit tests, parser golden tests, screenshot tests | Always |
| Emulator host (owner's PC, KVM) | Emulators and all instrumented tests. AVDs for API 35, 36 and 37 and every needed tool are already installed | Connect directly whenever it shows online on the private network; no need to ask |
| Phone: Galaxy S25 Ultra (`sm-s938b`) | Real-battery work only | Only when unavoidable, and always ask the owner first |

- **Default target: the API 37 AVD.** The API 36 AVD is used only to reproduce an Android 16-specific bug; API 35 is below `minSdk` and unused.
- Reach the emulator host over SSH on the private network and forward its ADB server (`ssh -N -L 5037:localhost:5037 <emulator-host>`) instead of exposing ADB on the network. Find AVD names with `emulator -list-avds`; never assume them.
- Run one emulator at a time, headless (`-no-window -no-audio -no-boot-anim`). The emulator host is the owner's daily computer: shut the emulator down when a run ends and leave no background processes.
- If the emulator host shows offline, do not retry in a loop. Continue with work that runs on the build server, queue the device tests and note it in `docs/progress.md`.
- **The phone needs permission.** Before connecting, ask the owner and state what will run, why the emulator cannot do it and how long it takes. Group phone work into planned sessions so requests stay rare.
- Phone-only work: fixture capture on One UI 9 (16.2), Samsung-specific probes (3.4), accuracy protocols A–F (16.6), Live Update and Now Bar checks (12.2).
- If the owner declines or defers, continue on the emulator and mark the affected gates "pending real device"; never skip them silently.

### 0.6 Autonomy, decisions and when to stop

The agent works autonomously and never waits for a "continue". It asks the owner only at the hard stops below; everything else it decides, records and keeps moving.

**Start-up.** Read `SPEC.md` completely, in as many chunks as the file needs, then begin P0 at once. Asking "read more or start?", "which task next?" or any other question this spec already answers is a violation of this section.

**Hard stops.** Ask, and keep working on anything independent while waiting for the answer:

1. Connecting to the phone `sm-s938b` (0.5).
2. Destructive device actions (rule 6 in 0.3).
3. Anything irreversible or public: publishing a release, making a repository public, force-pushing to the main branch, deleting branches or data.
4. Secrets the agent does not have: signing keys, tokens.
5. Changing a non-negotiable (1.2) or the priority order (0.2).
6. The P3 design-direction review (section 17).

**Never stop for:** a mid-phase progress report, starting the next phase after a green gate, choosing between reasonable options, retrying a flaky test, a library version bump, or any question that has a sensible default.

**Decision ladder**

| Decision | Examples | What the agent does |
| --- | --- | --- |
| Cheap to reverse | Naming, internal structure, a UI detail within the `/impeccable` rules | Decide now; write an ADR only if it departs from the spec |
| Costly to reverse | Architecture, a data format, the database schema, an external or public API, deleting or reverting something (ADR-0027) | Get a second opinion from a subagent, then decide and write an ADR with both views |
| Worth recording | A library or version pin, a component choice, a test strategy | Decide; give the reason in one line in an ADR (ADR-0027) |
| Owner's call | The hard stops above | Ask |

**Second-opinion protocol**

- Spawn a fresh subagent with a self-contained brief: the question, the relevant `SPEC.md` sections, the options with their trade-offs, and the constraints. Present the options neutrally; never reveal which one the main agent prefers.
- The subagent is read-only: no device access, no commits, no writes outside one scratch file.
- If both agree, proceed. If they disagree, apply the priority order in 0.2; if still tied, choose the more reversible option. Record both views in the ADR and list it under "Decisions to review" in `docs/progress.md`. Do not stop.

**When stuck** (the same failure after 3 distinct attempts, or about 45 minutes without progress on one problem):

1. Spawn a debugging subagent with the evidence only (error, logs, minimal reproduction), not the main agent's theory.
2. Still stuck: mark the task blocked in `docs/progress.md` and move to the next independent task.
3. Ask the owner only when blocked tasks stop all remaining work.

**Independent review of accuracy-critical code.** Parsers (5.1), calibration and estimators (6.1–6.5) and attribution diffs each get one review by a subagent that did not write them, before their phase gate.

**Optional work waits** (ADR-0027). Anything this spec calls "optional" or "if time allows" is not built. It gets one line in `BACKLOG.md` (what it is, where the spec has it), and the owner picks from that list once the mandatory phases and section 18 pass. Backlog items count in no gate.

**Questions that can wait** go to `docs/questions.md` together with the default the agent uses meanwhile. The owner answers when convenient; the agent re-reads the file at the start of each phase.

**Reporting.** One short summary per phase gate: what shipped, gate results, decisions to review, open questions. No other check-ins.

## 1. Mission, principles and success criteria

Coulomb answers one question within three seconds of opening: **what is draining my battery, and what should I do about it?** Every answer is backed by measured or system-sourced data and carries an honest confidence label.

### 1.1 Product principles

1. **Answer first, detail on demand.** Four depth layers, always in this order: Verdict (one sentence) → Story (top causes in plain words) → Evidence (tables, charts, timeline) → Raw (parsed dumps). Each layer is one tap from the previous.
2. **Honest numbers.** Every metric carries a provenance: Measured, Modeled, Estimated or Derived (section 6.7). No decimals beyond what the data supports.
3. **Zero-cost observer.** Coulomb MUST NOT wake the device while the screen is off in its default mode. Its own share of battery stays under the budget in 1.3.
4. **Shizuku-native, never Shizuku-dependent.** Full power with Shizuku or Sui; graceful, clearly labelled degradation without it. Permissions bootstrapped once keep working after Shizuku stops (section 4).
5. **Explain, then act.** Every insight links to a reversible action or states why no action exists.
6. **Local only.** No network permission, no analytics, no ads. Export is always user-initiated.
7. **Calm, crafted, precise UI.** A precision instrument, not a "battery booster" (section 11).

### 1.2 Non-negotiables

- MUST NOT declare `android.permission.INTERNET` or any network-capable SDK.
- MUST run fully on Android 17 (API 37) and Android 16 (API 36). `minSdk = 36`.
- MUST NOT schedule wakeup alarms, hold wakelocks longer than 2 s, or poll while the screen is off, except while the charger is connected.
- MUST NOT reset system battery stats without a two-step confirmation. App-level references replace resets (section 7.6).
- Every privileged write MUST be logged with its previous value and be revertible in one tap (section 13).
- MUST show a capability state for every feature: available, degraded (with reason) or unavailable (with fix).

### 1.3 Success criteria

| Criterion | Target | Measured by |
| --- | --- | --- |
| Time to verdict, cold start with cached data | p50 < 800 ms, p90 < 1.2 s on Galaxy S25 Ultra | Macrobenchmark (16.5) |
| Capacity estimate accuracy | Within ±3% of reference after 3 qualifying charge sessions | Protocol D (16.6) |
| Per-app attribution vs system | Within ±1 percentage point of `BatteryUsageStats` for the same window | Protocol C |
| Screen-off drain rate | Within ±0.1 %/h of the level-delta method over ≥ 2 h | Protocol B |
| Coulomb's own battery cost, Full mode | Over one 8 h screen-off night: 0 wakeups and 0 alarms from Coulomb, ≤ 0.1% of capacity, partial wakelocks < 30 s | Protocol E (ADR-0019) |
| Feature parity | 100% of the matrix in section 2 | Checklist (18) |
| Design quality | All `/impeccable` gates pass (11.12) | Gate log |
| Stability | 0 crashes and 0 ANRs over 30 simulated days; heap growth after GC < 10% | Stress test (16.3, ADR-0019) |

## 2. Competitive parity matrix

Coulomb MUST match every capability below and beat the reference apps on the stated edge. Reference apps: GSam Battery Monitor, BetterBatteryStats (BBS), AccuBattery, Battery Guru, BatStats ([listing](https://alternativeto.net/software/batstats/about)) and Batt ([listing](https://alternativeto.net/software/batt/about)).

| Capability | Strongest today | Coulomb implementation and edge |
| --- | --- | --- |
| Per-app drain in mAh and % | GSam, BatStats (Shizuku) | `BatteryUsageStats` per UID, split by power component and process state, for any window via snapshot diffs. Edge: reconciled against measured coulombs, gap shown. |
| Partial wakelocks per app and tag | BBS, GSam, BatStats | Count, total, max hold, screen-off share per tag. Edge: flags apps above Google Play's excessive threshold (> 2 h non-exempt per 24 h). |
| Kernel wakelocks / wakeup sources | BBS, BatStats (root) | Shell-level dump without root where available, sysfs with root. Edge: grouped by subsystem with a plain-language dictionary. |
| Wakeup reasons (IRQs) | BBS | Ranked per screen-off session. Edge: "Who woke the phone" timeline track. |
| Alarms (wakeup vs non-wakeup) | BBS, BatStats | Per app, per hour, screen-off only view. |
| Jobs and syncs | BatStats | Counts, runtime, stop reasons including Android 16 abandoned jobs. |
| Network per app | BBS, BatStats | Mobile/Wi-Fi bytes plus radio active time. Edge: "weak signal tax" insight. |
| CPU time and frequency residency | BBS, BatStats (root) | Per UID CPU time; time-in-state with root. |
| Deep sleep and awake ratio | BBS, GSam | Per screen-off session, not just since charge. |
| Doze states | BatStats | Light/deep idle timeline, maintenance windows, time to first deep idle. |
| Signal strength distribution | GSam, BBS | Time per signal bin and scanning time. |
| Screen-on / screen-off drain rate | AccuBattery, Battery Guru | Coulomb-based mA and %/h per session. Edge: exact screen-off average with zero wakeups. |
| Charging speed and curve | AccuBattery, Battery Guru | Live W, mA, V, °C, CC/CV phase. Edge: per-charger baselines and cable-fault detection. |
| Capacity estimate and wear | AccuBattery, Battery Guru | Coulomb integration over qualifying windows, robust estimator with confidence interval. Edge: cross-checked with system state of health. |
| Cycle count, health, manufacture and first-use date | Batt, BatStats (root) | Platform properties gated by `BATTERY_STATS`, root sysfs fallback. |
| Charge-limit and temperature alarms | AccuBattery, Battery Guru, BatStats | Android 16 Live Update with a target marker. Edge: aware of Samsung battery protection state. |
| Wear per charge session | AccuBattery | Cycle-equivalents plus a stress index; labelled Estimated, no pseudo-precise mAh wear. |
| Session history | All | Unlimited, compact; charge and discharge sessions searchable. |
| Custom references | BBS | Named snapshots; compare any two. |
| Event timeline | None on device (Battery Historian is desktop) | On-device multi-track timeline with scrubbing. |
| Widgets, overlays, tile | Battery Guru, BatStats | Glance widgets, Quick Settings tile, Live Update chip. |
| Doze tuning | Battery Guru (DUMP + secure settings) | Reversible profiles; detects external managers (Tasker, dozectl) and does not fight them. |
| Per-app restrictions | Battery Guru (partial) | Background run, standby bucket, wakelock appop, Doze whitelist; all logged and revertible. |
| On-device power rails (ODPM) | None | `SystemHealthManager` power monitors (API 35+) where the device exposes them. |
| Export | BBS (text), BatStats (settings) | CSV and JSON per table, plus a raw bundle for Battery Historian. |
| Privacy | BatStats (no internet) | No network permission at all. |
| Plain-language explanation | Partial in all | Deterministic Drain Story with evidence links, Turkish and English. |

## 3. Platform targets and compatibility

Coulomb targets API 37 (Android 17) with `minSdk = 36` (Android 16), and its primary device is a Galaxy S25 Ultra on One UI 9. Samsung began shipping stable One UI 9 (Android 17) to the S25 series in late September 2026 ([Android Authority](https://androidauthority.com/samsung-one-ui-9-galaxy-s25-fold-7-flip-7-stable-3716021)).

### 3.1 SDK levels

| Setting | Value |
| --- | --- |
| `compileSdk` | Newest stable, at least 37; minor releases (37.1, 37.2) allowed once stable |
| `targetSdk` | 37 |
| `minSdk` | 36 |
| Minor-release checks | Use `Build.VERSION.SDK_INT_FULL` against `VERSION_CODES_FULL` for QPR-only APIs (verify availability per API level) |
| Test devices | API 37 emulator on the emulator host (default for every run); Galaxy S25 Ultra sm-s938b (One UI 9) for real-battery work, with the owner's permission (0.5); API 36 emulator only to reproduce Android 16 bugs |

### 3.2 Android 16 behavior changes Coulomb MUST honor

- **JobScheduler quotas.** Jobs started while the app is visible, or running alongside a foreground service, now count against runtime quota ([Android 16 changes](https://developer.android.com/about/versions/16/behavior-changes-all)). Core collection MUST NOT depend on jobs. Use `JobScheduler#getPendingJobReasonsHistory` only for diagnostics, and handle the new `STOP_REASON_TIMEOUT_ABANDONED` stop reason.
- **Broadcast priority is per process.** Do not rely on ordered-broadcast priority across processes.
- **Accessibility announcements deprecated.** No `announceForAccessibility`; use pane titles and live regions (`Modifier.semantics { paneTitle = … }`, `liveRegion`).
- **Edge-to-edge and predictive back** are mandatory. Insets handled per screen; back gestures use seekable transitions.
- **16 KB page size.** Ship no native code, or align it to 16 KB.
- **Live Updates.** Promotion requires `ProgressStyle`, an ongoing notification, `POST_PROMOTED_NOTIFICATIONS` and `requestPromotedOngoing` ([Android Authority](https://www.androidauthority.com/android-16-qpr1-live-updates-3573399)). See section 12.

### 3.3 Android 17 changes Coulomb MUST honor

Source: [Android 17 release notes](https://developer.android.google.cn/about/versions/17/release-notes?hl=en).

- **Large-screen adaptivity is mandatory** for apps targeting API 37: orientation and resizability opt-outs are ignored at ≥ 600 dp. Coulomb MUST be fully adaptive, including Samsung DeX and external displays.
- **Fewer activity restarts.** Keyboard, navigation, touchscreen, color-mode and some UI-mode changes no longer recreate activities. State and resources MUST update without relying on recreation.
- **App memory limits.** Kills carry "MemoryLimiter" in `ApplicationExitInfo.getDescription()`. Parsers MUST stream; peak heap stays under 64 MB while parsing a 20 MB dump.
- **Lock-free `MessageQueue`.** Never reflect into `MessageQueue` internals.
- **Callback alarms.** If Coulomb ever needs an exact idle alarm, use the new `setExactAndAllowWhileIdle` variant that takes an `OnAlarmListener`. Default design needs none.
- **Background audio hardening.** Alert sounds play through notification channels, never through app-side audio from the background.
- **Profiling triggers.** Debug builds register `ProfilingManager` triggers for cold start, OOM and `KILL_EXCESSIVE_CPU_USAGE`.
- **Widgets.** `RemoteViews.setViewPadding` accepts dp/sp units; widgets read `OPTION_APPWIDGET_DISPLAY_ID` for per-display metrics.

### 3.4 Samsung One UI 9 specifics

- `CURRENT_NOW` units and sign may differ from AOSP; auto-calibrate (section 6.1). Never hardcode per-model constants.
- Battery protection (Basic, Adaptive, Maximum) changes charging behavior. Detect its state by probing settings keys at runtime; never hardcode a key name.
- One UI can put apps to sleep. Onboarding asks the user to add Coulomb to "Never sleeping apps" so the monitor survives (section 14).
- DeX and multi-window are first-class layouts (section 10.10).
- Verify whether promoted Live Updates appear in the Now Bar on One UI 9 and record the result in the capability report.

### 3.5 Known ecosystem issue: Shizuku on Android 17

Official Shizuku 13.6.0 fails to authorize new apps on Android 17, and `bindUserService` can hang without a callback; apps authorized on Android 16 keep working. Community forks such as Shizuku+, thedjchi/Shizuku, Shevery and Nightzuku work ([issue #2180](https://github.com/RikkaApps/Shizuku/issues/2180)). Coulomb MUST work with any manager that implements the Shizuku API, detect the hang with a timeout and explain the fix in onboarding (section 4.5). Coulomb is tested with forks (thedjchi/Shizuku on the emulator) and Sui, never against official Shizuku on Android 17; onboarding and the Privileges screen say once, in a quiet line, that Android 17 needs a fork or Sui (ADR-0028).

### 3.6 Hidden APIs

Android 16 changed ART internals, so reflection-based tricks are fragile. Coulomb runs every system or hidden API call inside the Shizuku UserService process. In-app hidden API access (via `HiddenApiBypass`) is a feature-flagged fallback, off unless a probe proves it works on the current build.

## 4. Privilege architecture

Coulomb uses Shizuku in two ways: once, to grant itself durable development permissions, and continuously, as a privileged worker process. If Shizuku stops, Coulomb keeps working from the durable grants and labels what it can no longer see.

&#91;embedded content: Privilege architecture · 4 tiers, one worker, one fallback path\]

Shell- and root-level sources reach Coulomb only through the worker; if the worker cannot bind within 5 s, the client falls back to public APIs and durable grants.

### 4.1 Access tiers

| Tier | How it is obtained | What it unlocks | Survives reboot | Survives Shizuku stopping |
| --- | --- | --- | --- | --- |
| T0 Unprivileged | Normal and runtime permissions; Usage access toggled by the user | `ACTION_BATTERY_CHANGED`, `BatteryManager` counters, `UsageStatsManager`, `NetworkStatsManager`, `PowerManager` states, thermal status, own-UID `HealthStats`, power monitors where exposed | Yes | Yes |
| T1 Durable grants | `pm grant` / `appops set` run once through Shizuku, or by the user over ADB | `BATTERY_STATS`: health, cycle, manufacture and first-use properties, all-UID `HealthStats`. `DUMP`: service dumps. `PACKAGE_USAGE_STATS` permission and `GET_USAGE_STATS` appop: batterystats dumps. `INTERACT_ACROSS_USERS`: batterystats `--proto` and `-c` in Coulomb's own process (ADR-0010). `WRITE_SECURE_SETTINGS` only when the Action Center is enabled | Yes (re-verified at every start) | Yes |
| T2 Privileged worker | Shizuku or Sui UserService running as shell (uid 2000) or root | `dumpsys` with structured flags, `IBatteryStats` binder calls, kernel wakelock dumps, alarm, Doze, power and job dumps, shell-readable sysfs, reversible `cmd`/`appops`/`settings` writes | No: needs Shizuku running | No |
| T3 Root | Shizuku started as root, or Sui (ADR-0028) | Kernel data only: suspend statistics (S25), last wakeup reason (S26), raw wakeup source counters (S9), kernel log wakeup records (S27), and `/sys/class/power_supply` where shell cannot read it (S23). Charge control is out of v1 scope (ADR-0007) | No | No |

Rules:

- Every feature declares its minimum tier. The UI reads the Capability Matrix (4.6) to show, degrade or hide it.
- T1 grants are re-verified at each app start and after every OS update (fingerprint change). A missing grant opens a one-tap repair through Shizuku.
- Two modes, one product (ADR-0028). Shizuku started over ADB (uid 2000) is the full product: every screen and feature works completely there. Root (uid 0) only adds kernel data, shown as extra detail inside existing screens; no screen or feature may need it.

### 4.2 Shizuku client

- Dependencies: `dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider`, newest stable (verify at build). Declare `rikka.shizuku.ShizukuProvider` in the manifest.
- Call `Sui.init(packageName)` first so Sui-based root setups work without the Shizuku app (verify the current Sui API artifact).
- Listen to `addBinderReceivedListenerSticky`, `addBinderDeadListener` and `addRequestPermissionResultListener`. All Shizuku state flows into one `PrivilegeState` `StateFlow`.
- Detect the server: `Shizuku.getVersion()`, `getUid()` (0 = root, 2000 = shell) and the manager package. Store them for diagnostics; never branch on a fork's name.
- Re-read the uid on every binder received and every worker bind; that uid is the mode. A dead binder, a restart or a switch between shell and root needs no app restart, and root-only sources open and close with the mode (ADR-0028).

### 4.3 Privileged worker (UserService)

- Implement `CoulombPrivilegedService` behind an AIDL interface `ICoulombPrivileged`. Bind with `Shizuku.UserServiceArgs(ComponentName(…))` using `.daemon(false)`, `.processNameSuffix("priv")`, `.version(BuildConfig.VERSION_CODE)` and `.debuggable(BuildConfig.DEBUG)`.
- **Bind timeout: 5 s.** No `onServiceConnected` within 5 s marks the worker unavailable for the session. Fall back to `ShizukuBinderWrapper` around `SystemServiceHelper.getSystemService(…)` for direct binder calls, then to T1 paths. This covers the Android 17 official-Shizuku hang.
- **Large outputs stream through `ParcelFileDescriptor` pipes**, never as binder return values, to stay under the 1 MB transaction limit. The app side parses the stream incrementally.
- **Typed allowlist, no free-form shell.** The AIDL exposes typed methods such as `dumpBatteryStats(flags: Int, out: ParcelFileDescriptor)`, `getBatteryUsageStats(sinceMs: Long)`, `setAppOp(pkg, op, mode)`. Command builders never interpolate untrusted strings; package names are validated against `PackageManager`.
- **Caller check.** The worker verifies `Binder.getCallingUid()` equals Coulomb's uid on every call.
- **Version handshake.** `getVersion()` mismatch → unbind with `remove = true`, rebind.
- **Lifecycle.** Bound while the screen is on and Coulomb is visible, or during a snapshot. Unbind 60 s after the last call so no shell process lingers. Measure bind cost and record it in the capability report.
- Hidden and system APIs run inside the worker (for example `BatteryStatsManager`, `IBatteryStats`, `com.android.internal.os.PowerProfile`). Results cross the boundary as Parcelables or serialized bytes.

### 4.4 One-time bootstrap

On first Shizuku authorization Coulomb runs, through the worker, and verifies each result with `checkSelfPermission` or `AppOpsManager`:

```
pm grant io.github.akane599.coulomb android.permission.BATTERY_STATS
pm grant io.github.akane599.coulomb android.permission.DUMP
pm grant io.github.akane599.coulomb android.permission.PACKAGE_USAGE_STATS
appops set io.github.akane599.coulomb GET_USAGE_STATS allow
pm grant io.github.akane599.coulomb android.permission.INTERACT_ACROSS_USERS
```

The two extra grants (ADR-0010) are what `DumpUtils` and the batterystats proto/checkin dumps check
when Coulomb dumps in its own process.

`WRITE_SECURE_SETTINGS` is granted only when the user turns on the Action Center. Users without Shizuku get the same commands as copyable `adb shell` lines.

### 4.5 Failure modes

| State | Detection | User sees | Recovery |
| --- | --- | --- | --- |
| Manager not installed | No binder, package query empty | "Basic mode" banner with what deeper access adds | Links to Shizuku-compatible managers and Sui |
| Installed, not running | No binder within 2 s | "Shizuku is stopped" | Deep link to the manager; auto-resume on binder received |
| Permission denied | Request result denied | Inline explanation | Retry button; ADB fallback |
| Request or bind hangs (Android 17, official 13.6.0) | No callback within 5 s | "Your Shizuku version can't authorize apps on Android 17" | Suggest a compatible fork or Sui; T1 grants via ADB keep Coulomb working |
| Binder died mid-dump | `DeadObjectException`, truncated stream | Nothing; snapshot marked partial | Retry once on next trigger |
| Shizuku not ready after boot | No binder yet, inside the boot grace (ADR-0029) | Nothing | Re-check at growing intervals; the "stopped" row only after the grace |
| Mode changed (root and shell) | New binder with a different uid | Root-only detail shows "not collected" from the switch on, never zero | Re-probe the kernel capabilities; nothing for the user to do (ADR-0028) |
| SELinux denial on sysfs | `EACCES` in worker | Feature shows "needs root" | None at T2 |
| Grants revoked after OS update | Fingerprint changed, `checkSelfPermission` fails | "Re-enable deep stats" card | One-tap repair |

### 4.6 Capability Matrix

A probe suite runs at first start, after an OS update and on demand. Each capability stores `state`, `tier`, `reason`, `probedAt`. Probes include: supported `dumpsys batterystats` flags (parse `-h`), proto output, kernel wakelock source, power monitor list, health properties returning valid values, `CURRENT_NOW` sanity, readable sysfs files, Samsung settings keys, Live Update promotion (`NotificationManager.canPostPromotedNotifications()`). The report renders on the Privileges screen and is exported to `docs/capabilities/`.

## 5. Data source catalog

Every number in Coulomb comes from one of the sources below; the agent MUST NOT add a source without adding a row here and a probe in 4.6. "Light" and "heavy" snapshot triggers are defined in 7.2.

| # | Source | Tier | When read | Feeds |
| --- | --- | --- | --- | --- |
| S1 | `ACTION_BATTERY_CHANGED` (sticky) | T0 | Every delivery while the process lives | Level, status, plug type, health, voltage (mV), temperature (0.1 °C), `EXTRA_CYCLE_COUNT` |
| S2 | `BatteryManager` properties: `CURRENT_NOW`, `CURRENT_AVERAGE`, `CHARGE_COUNTER`, `CAPACITY`, `ENERGY_COUNTER` | T0 | Samplers (7.3) and every snapshot | Current, coulomb counter; unsupported values (`Long.MIN_VALUE`, 0) mark the capability unavailable |
| S3 | `BatteryManager` health properties: state of health, manufacturing date, first-usage date, charging policy | T1 | Daily and on Health screen open | Health card; constants verified against API 36/37 sources |
| S4 | `BatteryUsageStats` via `BatteryStatsManager` / `IBatteryStats` | T2 (T1 fallback) | Heavy snapshots | Per-UID mAh by power component and process state; device totals; stats start timestamp |
| S5 | `dumpsys batterystats --proto` | T2 | Heavy snapshots | Partial wakelocks, kernel wakelocks, wakeup reasons, alarms, jobs, syncs, sensors, network, CPU, radio activity, signal and brightness bins, Doze times |
| S6 | `dumpsys batterystats --checkin` | T2 | Fallback when proto is missing | Same fields as S5, CSV form |
| S7 | `dumpsys batterystats -c --history-start <cursor>` (checkin form, ADR-0014) | T2 | After each heavy snapshot, incremental from the last `NEXT:` cursor | Event timeline: screen, plug, top app, wakelock, job, sync, alarm, GPS, radios, Doze, wakeup reasons |
| S8 | Kernel wakelock dump (`dumpsys suspend_control_internal`, probe) | T2 | Heavy snapshots | Kernel wakelocks and suspend stats without root |
| S9 | `/sys/class/wakeup/wakeupN/*`, wakeup sources | T3 | Heavy snapshots | Raw counters per kernel wakeup source: active, event, wakeup and expire counts; active, total, max and prevent-suspend times (ADR-0028) |
| S10 | `dumpsys alarm` | T2 | Heavy snapshots | Per-app wakeup alarm counts, pending alarms, top alarms |
| S11 | `dumpsys deviceidle` | T2 | Screen off/on and Doze screen | Doze state, whitelist, active `device_idle_constants` |
| S12 | `dumpsys power` | T2 | On demand (live wakelocks view) | Wakelocks held now, battery saver state |
| S13 | `dumpsys jobscheduler` | T2 | Heavy snapshots | Job history, pending reasons, stop reasons |
| S14 | `UsageStatsManager` events | T0 with usage access | Snapshots | Foreground intervals, app launches; standby bucket of other apps via worker (`am get-standby-bucket`) |
| S15 | `NetworkStatsManager` | T0 with usage access | Snapshots | Per-UID mobile and Wi-Fi bytes |
| S16 | `SystemHealthManager.getSupportedPowerMonitors` / `getPowerMonitorReadings` | T0 (API 35+) | Snapshots when monitors exist | On-device power rail energy ([reference](https://developer.android.com/reference/android/os/health/SystemHealthManager)); empty list = unsupported |
| S17 | `SystemHealthManager.takeUidSnapshots` | T1 | Heavy snapshots | `HealthStats` per UID: wakelocks, jobs, CPU time (cross-check for S4/S5) |
| S18 | `PowerManager` states and thermal API (`getCurrentThermalStatus`, headroom, listener) | T0 | Events | Doze flags, battery saver, thermal status |
| S19 | `dumpsys thermalservice` | T2 | Snapshots, Charging screen | Skin, CPU, battery temperatures and throttling status |
| S20 | `PowerProfile` (`com.android.internal.os.PowerProfile`) | T2 (worker) | Once per build | Design capacity (`battery.capacity`), model coefficients for explanations |
| S21 | `dumpsys battery` | T2 | Snapshots | AOSP fields plus OEM extras, parsed generically into a key-value map |
| S22 | Samsung settings keys (battery protection and related) | T2 | Snapshots | Protection mode; keys discovered by probe, never hardcoded |
| S23 | `/sys/class/power_supply/battery/*` | T2 if readable, else T3 | Daily and Health screen | Cycle count, full-charge and design capacity, OEM health fields where present |
| S24 | `DisplayManager` refresh rate, brightness setting | T0 | Screen-on samples | Context for screen drain explanations |
| S25 | `/sys/power/suspend_stats/*` | T3 | Heavy snapshots | Real deep-sleep count, failed suspend attempts by step, the last failure's device, errno and step (ADR-0028) |
| S26 | `/sys/kernel/wakeup_reasons/last_resume_reason`, `last_suspend_time` | T3 | Heavy snapshots | The IRQs or abort reason of the latest resume and its sleep time (ADR-0028) |
| S27 | `/dev/kmsg`, filtered in the worker to suspend and resume lines | T3 | Heavy snapshots | Each resume's cause and each failed suspend, in order, with kernel sequence numbers (ADR-0028) |

### 5.1 Parsing rules

- All parsers live in `:core:parsing`, are pure Kotlin, streaming and side-effect free.
- Proto parsing uses Wire-generated classes from the AOSP `batterystats` protos, pinned to the AOSP tags for API 36 and 37 (Apache-2.0, attribution in `NOTICE`).
- Text parsers (`--checkin`, `dumpsys` outputs) are versioned by SDK level and OEM. Unknown fields go into a raw map and are counted; the count appears in the capability report.
- No parser may throw on malformed input. Bad sections become `ParseIssue` records with byte offsets.
- Every parser has golden tests against real fixtures (16.2).

## 6. Measurement science

Coulomb's accuracy rests on the fuel gauge's coulomb counter, calibrated per device, with the system power model used for attribution and always reconciled against measured charge. All estimators live in `:core:analytics` as pure functions with unit tests.

### 6.1 Current and charge calibration

Raw `CURRENT_NOW` varies by OEM in unit (µA or mA) and sign. Coulomb derives a per-device calibration and stores it with `Build.FINGERPRINT`.

- **Sign.** Android convention: positive = charging into the battery. Choose sign `s ∈ {+1, −1}` so readings are negative while unplugged with the screen on, and positive while plugged with the level rising. Require 20 consistent samples.
- **Scale.** Choose `k ∈ {1, 1000}` (µA per raw unit) that minimizes the error between integrated current and the change in `CHARGE_COUNTER` over a window of at least 10 minutes. Sanity check: `|I| × V` must fall between 0.05 W and 25 W.
- **Charge counter reliability.** Mark `CHARGE_COUNTER` unreliable if it is unsupported, stuck for more than 15 minutes while the level changes, or quantized coarser than 1 mAh. Then fall back to integrated current during sampling and level deltas otherwise, and lower the provenance to Estimated.
- Re-validate after every OS update. Until calibration completes, current values show as "Calibrating" rather than numbers.

```latex
I_{true} = s \cdot k \cdot I_{raw}
```

### 6.2 Drain rate

- The primary measurement is the coulomb delta between two snapshots: average current = ΔQ / Δt.
- Screen-off sessions get exact averages from two snapshots (screen off, screen on) with nothing in between. No wakeups are needed.
- Report both %/h against estimated real capacity and the level-based %/h, and show the level-based one when calibration is incomplete.
- Show a rate only when the window lasts at least 15 minutes or ΔQ is at least 20 mAh; otherwise show "Collecting".

```latex
\bar{I} = \frac{Q_{b} - Q_{a}}{t_{b} - t_{a}}, \qquad r_{\%/h} = \frac{100 \cdot |\bar{I}|}{C_{est}}
```

### 6.3 Capacity and state of health

- **Qualifying window:** one continuous charge or discharge with ΔSoC ≥ 40 points (charge) or ≥ 30 points (discharge), battery temperature 15–40 °C throughout, no reboot, reliable charge counter.
- Per-window estimate: `C_i = ΔQ / (ΔSoC / 100)`.
- **Estimator:** weighted median of the last 10 qualifying windows. Weight = ΔSoC × temperature factor (1.0 at 20–30 °C, linearly down to 0.5 at 15 or 40 °C) × recency (half-life 60 days). The 95% interval comes from 1,000 bootstrap resamples.
- Display as "4,780 mAh ± 70" and health = estimate / design capacity. Round capacity to 10 mAh; never show an interval narrower than ±1% of design.
- **Design capacity:** `PowerProfile` `battery.capacity`, else sysfs design value, else user entry. Show the source.
- **Cross-check:** compare with the system state-of-health property and OEM sysfs health. A gap above 5 points shows both values with sources and a one-line explanation; Coulomb never silently picks one.

### 6.4 Wear per session

- Cycle equivalents = |ΔQ| / C\_est per session; the running sum cross-checks the platform cycle count.
- Stress index (Low, Moderate, High) from time above 80% SoC, peak temperature and C-rate (current / capacity). Thresholds live in a config file, not in code.
- No "mAh of wear" figure: it implies precision nobody has. Label everything here Estimated.

### 6.5 Attribution

- Unit: mAh per UID per window, from `BatteryUsageStats` deltas. Components include screen, CPU, mobile radio, Wi-Fi, Bluetooth, GNSS, sensors, camera, audio, video, flashlight, wakelock, memory, phone and idle. Process states: foreground, background, foreground service, cached.
- **Windows:** stats are cumulative since the last reset. Window \[a, b\] = S\_b − S\_a per UID and component. A changed stats start timestamp means a reset happened inside the window; split the window there and sum the parts.
- **Reconciliation:** unattributed = measured ΔQ − Σ modeled. Show it as its own row whenever it exceeds 10% of measured, with the likely reasons (weak signal, kernel activity, model gaps). A "scale to measured" toggle exists, off by default, and labels scaled values Derived.
- **System UIDs** get friendly names: Screen, Android System, Phone idle, Mobile network standby, Kernel. Shared UIDs are split by process where wakelock or process data allows.
- **Screen-off causes** rank by wakelock hold time, wakeup count and alarm count, because the power model under-counts short wakeups.

### 6.6 Power rails

When power monitors exist (S16), Coulomb computes energy per rail per window and shows those component shares as Measured, replacing modeled shares for the same components.

### 6.7 Provenance and rounding

| Provenance | Meaning | Example |
| --- | --- | --- |
| Measured | Direct counter from the hardware, the kernel or the framework | Coulomb delta, power rail energy, cycle count, wakelock time, network bytes |
| Modeled | Output of a model Coulomb does not own | Per-app mAh from `BatteryUsageStats`; the fuel gauge's level, state of health and capacity |
| Estimated | Coulomb's statistical estimate | Capacity, time remaining, wear stress |
| Derived | Arithmetic on the above, with any scaling | Scaled-to-measured app share |

A platform value names its source beside the figure ("Android", "Battery fuel gauge"). The four glyph shapes (solid dot, ring, bracket, half dot) are reserved for provenance (ADR-0023).

Each metric also has a quality score from 0 to 1 (sample count, window length, calibration state). Below 0.5 the number shows with a "low confidence" mark. Rounding: current to 10 mA below 1 A and 0.01 A above; rates to 0.1 %/h; capacity to 10 mAh; temperature to 0.1 °C; times as h and min.

### 6.8 Time remaining

Time to empty uses a blend of the current session's rate and the personal median for the same context (screen on vs off, hour of day). Always shown as a range ("5 h 10 min – 6 h 40 min"), never a single number.

### 6.9 Self-cost

Coulomb records its own UID's mAh, wakelocks and alarms from the same sources and shows them in Settings ("Coulomb used 0.12% today"). Exceeding the 0.3% per day budget raises a self-diagnostic insight.

## 7. Collection engine and runtime

Coulomb collects on events the system already wakes for, never on its own timers while the screen is off; the system's own battery history fills the gaps. This is what keeps the self-cost near zero.

### 7.1 Monitoring modes

| Mode | Process model | Gains | Cost |
| --- | --- | --- | --- |
| Full (default) | One foreground service, type `specialUse` with subtype `battery_monitoring` | Live current curves, exact screen-off sessions, charge alarms, Live Updates | ≤ 0.3% per day |
| Lite | No foreground service; snapshots on app open plus one periodic WorkManager job every 6 h | All attribution and wakelock data (system history keeps it) | Near zero; no live curves, no exact session boundaries |

Do not use the `dataSync` service type: Android 15+ caps it at 6 hours per 24 hours for apps targeting it. Declare `FOREGROUND_SERVICE_SPECIAL_USE` and the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property.

### 7.2 Triggers and snapshot types

| Trigger | Snapshot | Notes |
| --- | --- | --- |
| `SCREEN_OFF` | Heavy | Opens a screen-off session |
| `SCREEN_ON` / `USER_PRESENT` | Heavy | Closes it; computes the Night Report when the session lasted ≥ 1 h |
| `POWER_CONNECTED` / `POWER_DISCONNECTED` | Heavy | Opens or closes a charge session |
| `BATTERY_CHANGED` with a level change | Light | No new wakeups: the system is already awake to deliver it |
| Screen on, every 30 min | Heavy | Keeps windows fresh during long screen-on use |
| App opened, pull to refresh | Heavy | Debounced to one per 60 s |
| `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` | Light, then heavy once Shizuku binds | Boot count from `Settings.Global.BOOT_COUNT` |
| Package added, updated, removed | Light | Labels, drain-regression baseline reset |
| OS fingerprint change | None | Recalibration and capability probe |

- **Light snapshot:** S1, S2 and screen/Doze/thermal state. Cost under 5 ms.
- **Heavy snapshot:** light data plus S4–S13, S15, S16, S17, S19, S21 and S22 as capabilities allow, then the incremental history read (S7). S9 and S25–S27 only while the connection is root (ADR-0028). Target under 1.5 s wall time and under 300 ms CPU on the S25 Ultra.

### 7.3 Samplers

- Screen on, unplugged: S2 every 5 s while a Coulomb screen is visible, every 30 s otherwise.
- Plugged in: every 1 s on the Charging screen, every 5 s with the screen on, every 30 s with the screen off. While charging, a short partial wakelock for sampling is acceptable and capped at 200 ms per sample.
- Screen off, unplugged: no sampling. Never.

### 7.4 No-wakeup policy

- No exact or inexact wakeup alarms in Full mode.
- Wakelocks only while plugged, each released in a `finally` block with a 2 s timeout.
- Charge-limit alarms rely on `BATTERY_CHANGED`, which the system delivers on each level change while charging.
- A debug-only lint check fails the build on any `AlarmManager` call or `WakeLock.acquire()` without a timeout outside the allowlisted classes.

### 7.5 Pipeline

1. Triggers post events into a single `CollectorActor` (a coroutine `Channel`, one consumer).
2. The actor acquires data through `:core:privileged` with per-source timeouts (2 s each; the whole heavy snapshot capped at 4 s).
3. Parsers stream into compact domain records on `Dispatchers.Default` with bounded memory.
4. Analytics compute window diffs, sessions and estimates.
5. Room writes happen in one transaction per snapshot.
6. The insight engine re-evaluates only the rules whose inputs changed.
7. UI, widgets and notifications observe `Flow`s; nothing pushes into the UI directly.

### 7.6 References instead of resets

A reference is a named snapshot. Automatic references: since boot, since unplugged, since full charge, since screen off, today at 00:00. Users can create named references ("Before Instagram update") and compare any two. A system stats reset stays available only under Advanced, behind two confirmations.

### 7.7 Storage and retention

| Data | Kept for | Compaction |
| --- | --- | --- |
| Raw samples | 14 days | Then 1-minute aggregates (min, mean, max) |
| 1-minute aggregates | 180 days | Then hourly |
| Sessions, daily summaries, capacity estimates | Forever | — |
| Snapshot blobs (compressed) | 30 days | Then only the derived per-window records |
| History events | 30 days | Ring buffer |
| Action log | Forever | — |

Target database size under 150 MB after one year. Compaction and `VACUUM` run only while charging and idle.

### 7.8 Clocks and reboots

Order by `elapsedRealtimeNanos` plus boot count; display wall-clock time. Wall-clock or time-zone changes never reorder data. A reboot closes every open session with the reason "reboot".

## 8. Domain model and database schema

The schema below is the minimum; the agent MAY add columns but MUST NOT merge entities or drop provenance fields. Room with KSP, exported schemas committed, every migration tested. ADR-0015 records the keys, foreign-key actions, retention and size budget of version 1; ADR-0021 records version 2.

```kotlin
// Units are in field names. Times: elapsed = elapsedRealtimeNanos, wall = epoch ms.
// bootSeq = Coulomb's own boot number (Boot); rows order by (bootSeq, elapsed) (ADR-0015).

entity DeviceProfile(fingerprint PK, model, oem, sdkInt, sdkIntFull,
  designCapacityMah?, designCapacitySource?, currentScale? /*1|1000*/, currentSign? /*+1|-1*/,
  chargeCounterReliable?, calibratedAtWall?, calibrationQuality? /*0..1*/)

entity Boot(bootSeq PK, rawBootCount?, fingerprint, firstWallMs, firstElapsedNs,
  historyOffsetMs?, imported)

entity Capability(id PK, state /*AVAILABLE|DEGRADED|UNAVAILABLE*/, tier /*T0..T3*/,
  reason?, fixAction?, probedAtWall, fingerprint, detailJson)

entity BatterySample((bootSeq, elapsedNs) PK, wallMs, levelPct, chargeUah?, currentUa?, currentRaw?,
  voltageMv, tempDeciC, plugType, status, screenOn, dozeState, thermalStatus)
entity SampleAggregate((resolutionS, bootSeq, startElapsedNs) PK, startWallMs, sampleCount,
  levelFirst/LastPct, chargeFirst/LastUah?, currentMin/Mean/MaxUa?, voltageMeanMv,
  tempMin/Mean/MaxDeciC, screenOnShare, plugType)

entity Session(id PK, kind /*DISCHARGE|CHARGE|SCREEN_ON|SCREEN_OFF*/, bootSeq, startElapsedNs,
  endElapsedNs?, startWall, endWall?, startSnapshotId?, endSnapshotId?, startLevel, endLevel?,
  startChargeUah?, endChargeUah?, chargeSource?, avgCurrentMa?, peakCurrentMa?, minTempDeciC?,
  meanTempDeciC?, maxTempDeciC, awakeRatio?, deepIdleReached?, deepIdleReachedMs?,
  minutesAtOrAbove80?, minutesAt100?, stressIndex?, cycleEquivalents?, closeReason?, quality)

entity ChargeDetail(sessionId PK/FK, plugType, chargerProfileId?, peakW?, avgW?,
  ccToCvLevel?, minutesAbove80, minutesAbove38C, targetLevel?, protectionMode?)

entity Snapshot(id PK, bootSeq, elapsedNs, wallMs, trigger, kind /*LIGHT|HEAVY*/,
  levelPct?, chargeUah?, currentRaw?, voltageMv?, tempDeciC?, plugType?, status?,
  statsStartWall?, statsRealtimeMs? /*ADR-0018*/, historyCursorMs? /*NEXT: value, ADR-0014*/, historyEventId?,
  sourceStatusJson, blobPath?, partial, countersPruned, durationMs)

entity UidPower(snapshotId FK, uid, component, processState, mAh, provenance)
entity UidMeta(uid PK, packages /*json*/, label, isSystem, iconCacheKey, updatedWall)
entity UidPackage(uid, packageName, firstSeenWall, lastSeenWall)

entity WakeStat(snapshotId FK, uid?, tag, kind /*PARTIAL|KERNEL|WAKEUP_REASON*/,
  totalMs, count, maxMs?, screenOffMs?)
entity AlarmStat(snapshotId FK, uid, wakeups, total?)
entity JobStat(snapshotId FK, uid, jobs, runtimeMs?, abandoned?, topStopReason?)
entity NetStat(snapshotId FK, uid, mobileRxB?, mobileTxB?, wifiRxB?, wifiTxB?, radioActiveMs?)
entity RailEnergy(snapshotId FK, rail, energyUws, readElapsedNs?)
entity UidStat(snapshotId FK, uid, key, value)
entity DeviceStat(snapshotId FK, key, value)

entity WindowResult(id PK, kind /*CONSECUTIVE|SESSION|DAY|REFERENCE*/, sessionId?,
  fromSnapshotId, toSnapshotId, measuredMah?, chargeSource?, modeledMah?, unattributedMah?,
  screenOnMs, screenOffMs, awakeMsScreenOff?, quality, detailPruned)
entity WindowUid(windowId FK, uid, component, processState, process, mAh, provenance)
entity WindowWake(windowId FK, uid?, tagId, kind, totalMs, count, screenOffMs?)
entity WindowStat(windowId FK, uid, keyId, value, provenance)
entity WakeTag(id PK, tag)
entity StatKey(id PK, name)
  // interned tags and keys; detail provenance as codes (ADR-0021)

entity HistoryEvent(id PK, bootSeq, elapsedMs, type, change, baseline, uid?, tagId?, value?)
  // compact 30-day ring: codes, interned tags, no time index (ADR-0015); ms (ADR-0021)
entity HistoryTag(id PK, tag)
entity HistoryGap(id PK, startMonotonicMs, endMonotonicMs, startBootSeq, startElapsedNs,
  endBootSeq, endElapsedNs, reason)

entity Reference(id PK, name?, snapshotId FK, origin /*AUTO|USER*/, autoKind?, createdWall)

entity CapacityEstimate(id PK, sessionId FK, capacityMah, weight, deltaSocPct, deltaChargeMah,
  tempMeanDeciC, createdWall)
entity HealthSummary(epochDay PK, estimateMah?, ciLowMah?, ciHighMah?, healthPct?,
  systemSohPct?, oemHealth?, cycleCount?, cycleEquivalentsSum)

entity ChargerProfile(id PK, name, plugType, baselineW, firstSeenWall, lastSeenWall, notes?)

entity Insight(id PK, ruleId, windowId?, severity /*INFO|WATCH|ACT*/, subjectKey?, subjectUid?,
  evidenceJson, state /*NEW|SEEN|SNOOZED|DISMISSED|ACTED*/, createdWall, stateChangedWall,
  snoozeUntilWall?)

entity ActionLog(id PK, actionType, target, beforeValue?, afterValue, tier,
  executedWall, revertedWall?, origin /*USER|INSIGHT*/, insightId?, externalDriftWall?,
  externalDriftValue?)
```

### 8.1 Rules

- Indexes on every foreign key and on `(kind, bootSeq, startElapsedNs)`, `(uid, snapshotId)`, `(ruleId, state)` (ADR-0015).
- Settings live in Proto DataStore, not Room.
- Icons are never stored in the database; an in-memory LRU cache (64 entries) loads them from `PackageManager`.
- DAOs expose `Flow` for UI and `suspend` functions for writes. No main-thread queries; StrictMode fails debug builds on violations.
- Export (section 14.4) reads through the same DAOs; there is no second data path.

## 9. Insight engine

Insights are deterministic rules over stored windows, compared against the user's own baselines; no machine learning and no network. Each insight states what happened, how sure Coulomb is, and what the user can do.

### 9.1 Rule contract

- A rule = `id`, input window type, `evaluate(ctx): InsightCandidate?`, severity, evidence builder, copy keys (Turkish and English), optional action ids.
- Thresholds live in `insights.json` bundled with the app, so tuning needs no code change.
- **Baselines are personal:** 14-day rolling medians per context (screen off at night, screen on, per app). Fixed thresholds are used only where an external standard exists.
- One open insight per rule and subject. Re-trigger only after dismissal plus 24 h, or when severity rises.

### 9.2 Initial rules

| ID | Fires when | Severity | Action offered |
| --- | --- | --- | --- |
| R01 Excessive wakelock | An app holds > 2 h of partial wakelocks in 24 h (Google Play's core-vitals definition, [Android Developers](https://developer.android.com/blog/posts/raising-the-bar-on-battery-performance-excessive-partial-wake-locks-metric-is-now-out-of-beta?hl=en)) | Act | Restrict background, standby bucket, wakelock appop |
| R02 Long wakelock tag | A single tag's hold exceeds 60 min in a session | Watch | Same as R01 |
| R03 Poor deep sleep | Screen-off session ≥ 1 h with awake ratio > 15% | Act | Opens the session with ranked culprits |
| R04 High screen-off drain | Screen-off rate > 2× personal median and > 0.8 %/h | Watch | Night Report |
| R05 Alarm storm | > 12 wakeup alarms per hour from one app while screen off | Act | Restrict background, standby bucket |
| R06 Background location | GNSS active > 10 min while screen off for one app | Act | Open location permission page |
| R07 Weak signal tax | > 30% of time in the two lowest signal bins and modem share > 15% | Info | Explains; suggests Wi-Fi calling or LTE lock where relevant |
| R08 Scan heavy | Wi-Fi or Bluetooth scanning > 20 min per day | Info | Opens scanning settings |
| R09 Sensor holder | Wakeup sensor held > 30 min screen off | Watch | App detail |
| R10 Job churn | > 50 jobs per day or any abandoned jobs for one app | Watch | Standby bucket |
| R11 Hot charging | Battery > 40 °C for > 10 min while charging | Act | Tip: remove case, slower charger |
| R12 Hot battery | Battery > 45 °C at any time | Act | Shows the top consumer at that moment |
| R13 Slow charging | Average W < 70% of this charger's baseline | Watch | Suggests cable or port check |
| R14 Capacity drop | Estimate falls > 3 points within 30 days | Info | Health screen |
| R15 Long at 100% | > 2 h at 100% while plugged, battery protection off | Watch | Deep link to Samsung battery protection |
| R16 Unused active app | App in the Active standby bucket, not opened for 7 days | Info | Standby bucket |
| R17 Doze never deep | No deep idle in 3 consecutive screen-off sessions ≥ 2 h | Watch | Doze screen; lists whitelist and motion causes |
| R18 Update regression | App's daily mAh > 2× its 14-day median within 3 days of an update | Act | App detail with before/after |
| R19 Unattributed gap | Unattributed > 20% of measured for a window ≥ 2 h | Info | Explains the limits of the power model |
| R20 Coulomb self-cost | Coulomb's own share > 0.3% per day | Act | Self-diagnostic and switch to Lite mode |

### 9.3 Drain Story

The Drain Story is the Verdict and Story layers in words. It is generated by templates, not an LLM.

- **Structure:** one verdict sentence, then up to three cause sentences, then at most one anomaly sentence. Each sentence ≤ 22 words.
- **Selection:** causes are the top contributors by mAh in the window, merged when they share a root cause (an app and its wakelocks). The anomaly is the highest-severity open insight for that window.
- **Numbers:** always with units, locale-formatted (Turkish: "0,8 %/sa", "1.240 mAh"). Never more precision than 6.7 allows.
- **Tone:** specific, neutral, no blame words, no exclamation marks, no "optimize" or "boost". Technical terms appear only in the Evidence layer, with a glossary tooltip.
- Every noun in the story (an app, a wakelock, a session) is a link to its evidence.

Examples the copy MUST be able to produce:

> **TR:** Gece ekran 7 sa 12 dk kapalıydı ve pil %6 azaldı (0,8 %/sa). Bunun yarısı Instagram'ın telefonu 41 kez uyandırmasından geldi. Cihaz zamanın %91'inde derin uykudaydı.

> **EN:** Your screen was off for 7 h 12 min overnight and the battery fell 6% (0.8 %/h). Half of that came from Instagram waking the phone 41 times. The phone was in deep sleep 91% of the time.

### 9.4 Lifecycle

NEW → SEEN (shown on screen) → SNOOZED (1 day, 1 week) or DISMISSED or ACTED. "Why am I seeing this?" opens the evidence: the rule, its threshold, the measured value and the window. Notifications for insights are batched into one daily digest at most; only Act-level thermal insights (R11, R12) notify immediately.

## 10. Information architecture and screens

Four destinations carry the app: **Now, Drain, Timeline, Health**. Settings, Privileges and the Action Center open from the top app bar; the Charging screen takes over Now while plugged in. Content order on every screen follows the depth layers of 1.1.

### 10.1 Global rules

- Navigation via `NavigationSuiteScaffold`: bottom bar on compact width, rail on medium, rail plus list-detail panes on expanded.
- One primary number per screen, set in the Display numeral style (11.3). Everything else is secondary.
- Every metric shows its provenance glyph (6.7). Long-press explains it, and so does a TalkBack custom action, "Explain provenance". A table or chart with a single source carries one glyph on its header. Onboarding teaches the four glyphs once (14.1; ADR-0023).
- Every list row with a number shows a share bar, so magnitude reads without reading digits.
- A window selector (10.3) appears wherever data is windowed, and the chosen window persists across screens.
- No screen nests more than one level of containment. No cards inside cards.

### 10.2 Now

Purpose: the three-second answer.

1. **Hero:** level as a Display numeral; beside it, the time-remaining range (6.8) and the current state ("Discharging · 410 mA").
2. **Verdict:** one Drain Story sentence for "since unplugged".
3. **Session strip:** three figures with labels: screen-on rate, screen-off rate, deep sleep share.
4. **Top drains:** three rows (icon, name, mAh, share bar). "See all" opens Drain with the same window.
5. **Insights:** at most two, highest severity first.
6. **Live current:** a small chart of the last 10 minutes, updated at most once per second, only while the screen is visible.

### 10.3 Drain

Purpose: who and what used the battery in a chosen window.

- **Window selector:** Since unplugged · Today · Last 24 h as a connected button group, plus **More**, which opens a sheet with Last night (screen off), Custom range and Compare two periods (ADR-0023).
- **Lenses** (segmented): Apps · Components · Wakeups.
- Header: measured total for the window, modeled total, unattributed row when > 10% (6.5).
- **Apps lens:** ranked rows; each row expands inline to component chips; tap opens App detail. Toggle to include system UIDs.
- **Components lens:** horizontal stacked bar for the whole window, then ranked components; each component lists its top apps.
- **Wakeups lens:** three groups: Partial wakelocks (by app and tag), Kernel wakelocks (grouped by subsystem), Wakeup reasons; plus Alarms. Each entry: total time, count, screen-off share, plain-language description when the dictionary knows it.

### 10.4 App detail

1. Header: icon, name, mAh and share in the window, provenance.
2. Story for this app (9.3). Its anomaly sentence is the app's highest-severity open insight.
3. When that insight is rated Act: its card, the same as on Now, with its action button (ADR-0026).
4. Component breakdown (stacked bar) and process-state breakdown (foreground, background, foreground service, cached).
5. "Woke the phone N times" with wakelocks, alarms and jobs tables.
6. Network (mobile, Wi-Fi) and foreground time.
7. 14-day trend sparkline with update markers.
8. Actions (section 13) with current state of each restriction. App info deep link.

### 10.5 Wakeup source detail

For a kernel wakelock or wakeup reason: description from the dictionary, subsystem, which apps correlate in time (from history), per-session counts, and "what you can do" (often nothing; say so plainly).

### 10.6 Timeline

Purpose: an on-device Battery Historian.

- **Top track:** level as a line, current as an area (discharge below the axis, charge above).
- **Tracks below** (each a band, toggleable): Screen · Charging · Doze (light, deep) · Awake vs suspended · Top app · Wakelocks · Mobile radio · Wi-Fi · GPS · Signal strength · Temperature.
- Events closer than 3 dp at the current zoom merge into a density span: a 20% tint with a 1 dp stroke in the track's colour. The caption or readout gives the rate. Zooming in splits a span back into ticks (ADR-0023).
- Pinch to zoom from 15 minutes to 7 days; drag to scrub with a haptic tick per event; tap an event to open a bottom sheet with its details and a link to the related screen.
- Default range: last 24 h. A floating toolbar holds range presets, track visibility and "jump to now".
- Rendering: custom `Canvas`, virtualized by visible time range, 60 fps while panning on the S25 Ultra.

### 10.7 Night Report (screen-off sessions)

List of screen-off sessions ≥ 1 h: date, duration, %/h, deep-sleep share, Doze reached (yes/no and when). The detail page shows a compact timeline of that session and the ranked culprits: wakeup reasons, kernel wakelocks, partial wakelocks, alarms.

### 10.8 Health

1. Capacity estimate with interval and health %, with the cross-check row (system and OEM values with sources).
2. Cycle count (platform) next to Coulomb's cycle-equivalents sum.
3. Manufacture and first-use date when available.
4. Capacity over time (line with interval band).
5. Charging habits: average depth of discharge, time at ≥ 80%, average charge temperature, wear stress distribution.
6. Charge sessions list; tap opens a session with its curve.
7. Chargers: profiles with baseline W, last seen, rename.

### 10.9 Charging (live)

Shown in place of Now while plugged. Primary number: power in W. Secondary: mA, V, battery °C, time to target and to full (ranges), current phase in plain words ("full speed", "slowing down near full"; constant current and constant voltage stay in the glossary; ADR-0023), charger profile and comparison to its baseline. Below: the session's curve (level and power), updating at 1 Hz while visible. Target selector (e.g. 80%) links to the charge alarm.

### 10.10 Adaptive layouts

- Compact (< 600 dp): single pane.
- Medium (600–839 dp): rail plus single pane; Drain and Health use two-column content.
- Expanded (≥ 840 dp, DeX, external display): list-detail panes for Drain → App detail, Night Report → session, Health → session; Timeline gets a side panel for event details.
- Must work in split screen, freeform windows and with any aspect ratio (Android 17 requirement).

### 10.11 Screen states

Every screen defines: loading (static skeleton shaped like the final layout, one fade-in, no shimmer loop), collecting ("First result after 15 minutes of screen-off time"), partial (a capability is missing: inline reason and fix), empty, error (what happened and what to do), and locked (needs a higher tier, with the benefit stated).

## 11. Design system and the /impeccable protocol

Coulomb looks like a precision instrument: quiet tinted surfaces, data as the hero, one warm accent, crafted type and calm motion. `/impeccable` (Paul Bakaus, one skill with 23 commands, 7 reference domains and deterministic anti-pattern rules, [README](https://github.com/ourslyio/impeccable)) is the design authority; the agent iterates until every gate in 11.12 passes.

### 11.1 Setup

1. Install into the repo: `npx impeccable skills install` (or the Claude Code plugin: `/plugin marketplace add pbakaus/impeccable`). Then `npx impeccable skills update` to pull the newest rules.
2. Run `/impeccable init`. Answer its questions from sections 1 and 11.2 so `PRODUCT.md` and `DESIGN.md` describe Coulomb. Register: **product**, not brand.
3. Read all seven reference domains (typography, color-and-contrast, spatial-design, motion-design, interaction-design, responsive-design, ux-writing) before any design work. In impeccable 4.7.1 they live in the `typeset`, `colorize`, `layout`, `animate`, `operate` and `harden`, `adapt` and `clarify` references, with `craft-floor` read before every UI edit (ADR-0022). Where this spec and the installed reference disagree, the installed reference wins unless 11.11 lists the conflict.

### 11.2 Personality and anti-references

- Three words: **precise, calm, candid.**
- Users: Android power users who read wakelock names, and people who just want the verdict. The top layer serves the second group; the evidence layer serves the first.
- Anti-references: neon "battery booster" apps, cleaner apps with fake scores, gamified badges, purple-to-blue gradients, glassmorphism, glowing dark UIs, emoji in UI copy, a rounded icon tile above every heading.
- References for feel (not to copy): lab instruments, well-set financial tables, railway timetables.

### 11.3 Typography

- Default pick: **Hanken Grotesk** (variable) for UI and numerals; **Spline Sans Mono** for raw identifiers (wakelock tags, kernel names, commands). Both are OFL and bundled as font resources: Coulomb has no network, so no downloadable fonts.
- Hard requirements for any face: tabular and lining figures (`tnum`, `lnum`), full Turkish coverage (ğ Ğ ı İ ş Ş ç Ç ö Ö ü Ü), a variable weight axis. If a default fails a requirement, or the installed `/impeccable` typography reference flags it as overused, replace it via `/impeccable typeset` and record the choice in `DESIGN.md` and an ADR.
- All numbers use tabular figures so values do not jitter as they update.
- Scale (sp, ratio about 1.25):

| Role | Size / line height | Weight | Use |
| --- | --- | --- | --- |
| Display numeral | 56 / 60, tracking −2% | 600 | One hero number per screen; its unit at Headline size, weight 400 |
| Headline | 28 / 34 | 600 | Screen titles on expanded layouts |
| Title | 20 / 26 | 600 | Section titles, app bar titles, the onboarding title on compact widths |
| Body | 16 / 24 | 400; 500 row names; 600 subheads and card titles | Story sentences, descriptions, row names, subheads, switch labels |
| Data | 15 / 20, tabular | 500; 600 for a bold lead word | Table values |
| Label | 13 / 18, tracking +1% | 500; 600 buttons | Column headers, chips, selection controls, buttons |
| Caption | 12 / 16 | 400; 500 navigation bar labels | Units, provenance, footnotes, secondary notes |
| Mono | 13 / 18 | 400 | Tags, identifiers |

- There are no other sizes; 14 sp is not a role (ADR-0023).

- Must stay legible at 200% font scale using Android's non-linear scaling; layouts reflow, never clip.
- Line length for prose: 40–70 characters.

### 11.4 Color

All tokens are authored in OKLCH (as `/impeccable` requires), converted to sRGB by a build-time script that writes `Color.kt`. Neutrals are tinted toward hue 255 (cool slate); nothing is pure gray.

| Token | Light | Dark | Notes |
| --- | --- | --- | --- |
| `bg` | oklch(0.985 0.004 255) | oklch(0.17 0.012 255) |  |
| `surface` | oklch(0.965 0.006 255) | oklch(0.21 0.014 255) |  |
| `surfaceRaised` | oklch(0.94 0.008 255) | oklch(0.25 0.015 255) | Sheets, menus |
| `hairline` | oklch(0.88 0.010 255) | oklch(0.32 0.015 255) | Dividers instead of cards |
| `textPrimary` | oklch(0.22 0.015 255) | oklch(0.94 0.006 255) |  |
| `textSecondary` | oklch(0.48 0.015 255) | oklch(0.72 0.012 255) | Never on colored fills |
| `accent` (Copper) | oklch(0.60 0.14 50) | oklch(0.74 0.13 55) | Primary action, hero state, selection |
| `good` | oklch(0.62 0.13 160) | oklch(0.76 0.12 160) |  |
| `caution` | oklch(0.70 0.15 80) | oklch(0.82 0.14 85) |  |
| `critical` | oklch(0.57 0.20 27) | oklch(0.70 0.17 25) |  |
| `accentFill` | oklch(0.88 0.06 55) | oklch(0.38 0.08 55) | Fills that carry text or an icon: filled button, the selected value in a chip or segment group, navigation pill. View tabs, such as Drain's lens, select with `surfaceRaised` and a check (ADR-0022, ADR-0023, ADR-0025) |
| `onAccentFill` | `textPrimary` | `textPrimary` | Text on `accentFill` |
| `onGood`, `onCaution` | `textPrimary` | `bg` | Text on status fills |
| `onCritical` | `bg` | `bg` | Text on status fills |

- **Data palette** for power components: 8 hues at equal lightness (L 0.62 light, 0.74 dark, C 0.11; ADR-0022): screen 245, CPU 30, mobile radio 150, Wi-Fi 200, GNSS 95, Bluetooth 275, sensors 340, other = neutral. Validate with deuteranopia, protanopia and tritanopia simulation; every color-coded item also carries a direct text label.
- A hue names a power component and nothing else; non-component states (Doze, top app, signal, temperature, wear stress) use neutral ink (ADR-0022).
- Accent appears at most once per region as emphasis (primary action, hero state, selection). Charging uses the accent; discharging uses neutral ink. Charging drawn in a chart is a data encoding and counts once per chart. `accent` itself never carries text; text-bearing fills use `accentFill`.
- Switches use neutral ink: a checked switch has a `textPrimary` track and a `bg` thumb, and an unchecked one a `textSecondary` outline. The Info severity chip is `textPrimary` on `surfaceRaised` (ADR-0023).
- Contrast: WCAG 2.2 AA minimum (4.5:1 text, 3:1 large text and UI parts). Checked by `/impeccable audit` and by the screenshot test suite.
- **Wallpaper color** mode (optional setting): the Material You seed replaces only the accent; neutrals stay Coulomb's.
- **True black** mode (opt-in): `bg` = #000000 for OLED savings, surfaces stay tinted. Default dark is tinted near-black (see 11.11).

### 11.5 Space, layout and shape

- 4 dp grid. Spacing tokens: 4, 8, 12, 16, 24, 32, 48, 64. Screen gutter 16 dp compact, 24 dp medium and up.
- Group with space and hairlines, not containers. Cards only for content that moves as a unit (an insight, a session); never nested.
- Corner radii: 6 (chips), 12 (rows on press, inputs), 20 (sheets), 28 (large surfaces). Touch targets ≥ 48 dp.
- Vertical rhythm: section gap 24 dp on compact widths and 32 dp on medium and up (ADR-0025), row height 56–72 dp, hero block ≥ 30% of the first viewport on Now.

### 11.6 Motion

- Use `MaterialExpressiveTheme` with a **custom `MotionScheme`**: spatial springs with damping ratio 0.92–1.0 and effect springs at 1.0, so nothing visibly bounces. Starting stiffness: fast 1400, default 700, slow 300 (tune by eye; overshoot must stay invisible).
- Tweens, where used, ease out (quart or expo curves). No bounce or elastic easing anywhere.
- Motion explains change only: numbers roll digit by digit with tabular figures; list reorders animate; charts draw in once on first appearance; shared-element transition from an app row to App detail; predictive back is seekable.
- **Battery rule:** nothing animates while idle. No infinite shimmer, pulsing or looping. The live current chart redraws at most once per second with no per-frame animation. The wavy progress indicator is allowed only on the Charging screen while plugged in.
- Reduced motion: when animator scale is 0 or "Remove animations" is on, all transitions become 150 ms cross-fades.
- Haptics: `CLOCK_TICK` per event while scrubbing the Timeline; `CONFIRM` and `REJECT` for action results. Nothing else vibrates.

### 11.7 Components

- Use Material 3 components where they fit, restyled by tokens. If the Expressive APIs need a pre-stable `material3` artifact, pin the newest one, isolate it in `:core:designsystem` and record an ADR.
- Expressive pieces in scope: connected button group (window selector), floating toolbar (Timeline), `LoadingIndicator` only for privileged waits > 400 ms, shape morph only for the charging state glyph.
- Coulomb's own components (in `:core:designsystem`): `MetricValue` (number + unit + provenance), `ShareBar`, `AppRow`, `StorySentence`, `ProvenanceGlyph`, `CapabilityNotice`, `WindowSelector`, `TrackBand`, `SessionRow`, `ActionRow` (with revert affordance).

### 11.8 Data visualization

- Custom `Canvas` drawing, not a chart library's default look.
- Direct labels instead of legends; units on every axis; hairline gridlines at quarter intervals, tinted.
- Discharge plots below the zero axis, charging above, so direction reads without color.
- Scrubbing shows a crosshair and a value readout in the Data style; values snap to samples.
- Accessibility: each chart has a semantics summary ("Battery fell from 82% to 61% between 23:10 and 07:05") and a "View as table" alternative. That is a visible text button under every evidence chart, and a semantics custom action on every chart, hero scales included (ADR-0023).
- Chart text is drawn in sp at native size; the plot width follows the container and labels never scale with it (ADR-0022).
- A translucent fill never carries meaning alone: every area and band has a solid edge or outline at 3:1 or more. Lighter states are an outline with a 20% tint, stronger states solid.
- A truncated list of shares ends with a remainder row ("22 more apps", with its mAh and share), so the visible rows sum to the whole.
- Event marks closer than 3 dp become a density span (10.6).
- The provenance shapes (solid dot, ring, bracket, half dot) never serve as chart markers; markers use crosses or ticks (ADR-0023).

### 11.9 Iconography and app icon

One icon family (Material Symbols Rounded, one weight and optical size throughout). The app icon is adaptive and supports themed monochrome icons; it is produced in the design lab with `/impeccable craft`. No lightning-bolt-in-a-battery cliché.

### 11.10 Copy

- Turkish and English from day one; Turkish follows the device locale. Sentence case everywhere.
- Numbers formatted with `NumberFormat` for the locale (Turkish: decimal comma, dot thousands separator).
- Errors say what happened and what to do next. Buttons name the action ("Restrict background", not "OK").
- Every string passes `/impeccable clarify`.

### 11.11 Resolved conflicts

| `/impeccable` default | Android or product pressure | Decision |
| --- | --- | --- |
| Avoid system default fonts | Android defaults to the system face | Bundle the chosen faces (11.3) |
| No bounce or elastic easing | Expressive motion overshoots | Custom critically damped `MotionScheme` (11.6) |
| No pure black, always tint | OLED users want true black | Tinted near-black by default; true black is an explicit opt-in setting |
| Don't wrap everything in cards | Material layouts lean on cards | Space and hairlines; cards only for movable units |
| No gray text on colored backgrounds | Status chips | Text on colored fills uses `textPrimary` or white at 4.5:1 |
| Web-oriented responsive rules | Compose has no CSS | Map to `WindowSizeClass`, panes and `BoxWithConstraints` |
| Touch targets | Android minimum is 48 dp | 48 dp everywhere |

### 11.12 Workflow and gates

**Design lab first.** `/impeccable`'s deterministic detector runs on HTML, so key screens are designed in HTML before Compose:

1. Create `design-lab/` with static HTML and CSS prototypes at 412 × 915 CSS px for: Now, Drain (Apps lens), App detail, Timeline, Health, Charging, plus one onboarding step.
2. Per screen: `/impeccable shape` → `/impeccable craft` → `/impeccable critique` → `/impeccable audit`; use `/impeccable live` for variants.
3. `npx impeccable detect design-lab/ --json` MUST return zero findings, with the version pinned (`npx -y impeccable@<version>`, ADR-0022), and so MUST a rendered scan of each page at 412 × 915 (`detect <url> --viewport 412x915`).
4. Lock tokens with `/impeccable extract` and `/impeccable document` into `DESIGN.md`.
5. Port tokens 1:1 to `:core:designsystem`; a unit test asserts every token in `DESIGN.md` exists in code with the same value.

**Compose phase.** For every screen:

1. Screenshot tests render it in light, dark and true black; font scale 1.0, 1.3 and 2.0; compact and expanded widths; Turkish and English.
2. Run `/impeccable critique` and `/impeccable audit` on the screenshots and the composable source. Fix and re-run.
3. Run `/impeccable harden` (long app names, 6-digit mAh, missing data, RTL sanity), `/impeccable clarify` (copy), `/impeccable animate` (motion against 11.6), `/impeccable adapt` (medium and expanded), `/impeccable optimize` (recomposition and overdraw), then `/impeccable polish`.
4. `/impeccable delight` is limited to two moments: the transition into the Charging screen on plug-in, and the first verdict after onboarding.

**Gates.**

- Per screen: no critical or major findings from critique or audit; all harden items resolved; screenshots committed.
- Whole app: `/impeccable audit` and `/impeccable critique` across all screens; repeat until **two consecutive runs return no finding above minor**. This is the definition of "/impeccable fully satisfied".
- Every run's output is saved in `docs/design-gates/<date>-<command>-<screen>.md`.

## 12. Notifications, Live Updates, widgets, tile, watch and automation

Surfaces outside the app show the verdict and nothing more, and they update only when a snapshot or a level change happens. None of them may add wakeups.

### 12.1 Notification channels

| Channel | Importance | Content |
| --- | --- | --- |
| Monitor | Min, silent | Foreground-service notification: "0,7 %/sa · ekran kapalı 6 sa". The user may hide the channel; the service keeps running |
| Charging | Default, silent, promoted | Live Update (12.2) |
| Alerts | High, sound | Charge target reached, battery temperature |
| Insights | Default | One daily digest at most |
| Privileges | Default | Shizuku lost, grants revoked; actionable fix |

### 12.2 Charging Live Update

- Built with `Notification.ProgressStyle`: one segment from the start level to the target, a second from the target to 100% in a quieter color, a point at the target, and the Coulomb glyph as the tracker icon.
- Short status-chip text such as "62% · 18 W".
- Declare `POST_PROMOTED_NOTIFICATIONS`, call `requestPromotedOngoing(true)`, and check `NotificationManager.canPostPromotedNotifications()`. If promotion is unavailable, post the same content as a normal ongoing notification.
- Update on each 1% change or every 30 s, whichever comes later. End it on unplug with a 10 s summary ("+46% in 38 min, avg 21 W").
- On One UI 9, verify and record whether it appears in the Now Bar.

### 12.3 Alerts

- Charge target alarm: fires once per session when the level reaches the target (default off; suggested 80% when Samsung battery protection is off). Sound comes from the channel, so Do Not Disturb and Android 17's background audio rules are respected.
- Temperature alarm: battery ≥ 42 °C (configurable), at most once per 30 minutes.

### 12.4 Widgets (Glance)

| Widget | Size | Content |
| --- | --- | --- |
| Level and verdict | 2×1 | Level, state, one-line verdict |
| Top drains | 4×2 | Window name, top three apps with share bars |
| Last night | 4×2 | Last screen-off session: duration, %/h, deep sleep |
| Health | 2×2 | Capacity estimate, health %, cycles |

- Updates come from snapshot events through `GlanceAppWidget.updateAll`; no periodic update interval.
- Themed with the Coulomb palette or wallpaper color per setting; generated previews in the widget picker.
- Layouts respond to size; text never truncates mid-number.

### 12.5 Quick Settings tile

Shows level and current drain rate as its subtitle; tap opens Now; long-press opens app settings. Tile state refreshes on `onStartListening` from the latest light snapshot only.

### 12.6 Wear OS (Phase 6, optional)

A tile and a complication on the paired watch showing phone level, drain rate and charging state, sent through the Wearable Data Layer only on level change or plug events, or on a periodic sync; never a live stream (ADR-0007). The watch module holds no network permission either.

### 12.7 Automation API (for Tasker and similar)

- Exported broadcast actions protected by a signature-or-user-granted permission `io.github.akane599.coulomb.permission.AUTOMATE`: `ACTION_SNAPSHOT_NOW`, `ACTION_SET_CHARGE_TARGET`, `ACTION_APPLY_DOZE_PROFILE`.
- A read-only `ContentProvider` (same permission) exposing current level, rates, last night's report and the top three drains.
- Every automation call is written to the action log with origin "automation".

## 13. Action Center

Every action Coulomb can take is previewed, logged with its previous value and revertible in one tap; nothing runs without a user tap. The Action Center is off until the user enables it, which also grants `WRITE_SECURE_SETTINGS`.

### 13.1 Rules

- Before running: show the exact command, the expected effect, the risk level and how to revert.
- Execute through typed worker methods (4.3), never raw shell from the UI.
- Read the current value first and store it in `ActionLog.beforeValue`; then write; then read back and verify.
- **Drift detection:** each heavy snapshot re-reads every active action's target. A value changed by something else is marked "Changed outside Coulomb" and never silently re-applied.
- **External managers:** if `device_idle_constants` is non-default and was not set by Coulomb (for example by a Tasker or dozectl setup), Doze profiles show "Managed externally" and stay read-only until the user explicitly takes over.
- A "Revert all" control restores every active action in reverse order.

### 13.2 Actions

| Action | Worker operation | Risk | Revert |
| --- | --- | --- | --- |
| Force stop | `am force-stop <pkg>` | Low | Not needed (transient) |
| Restrict background | `appops set <pkg> RUN_ANY_IN_BACKGROUND ignore` | Medium: delayed notifications | Restore previous mode |
| Standby bucket | `am set-standby-bucket <pkg> rare\|restricted` | Medium; the system may re-evaluate | Restore previous bucket |
| Deny wakelocks | `appops set <pkg> WAKE_LOCK ignore` | High: alarms, music and sync can break | Restore previous mode |
| Doze whitelist add/remove | `cmd deviceidle whitelist +<pkg>` / `-<pkg>` | Medium | Inverse command |
| Doze profile | `settings put global device_idle_constants <value>` | Medium | Previous string restored verbatim |
| Sensor restriction (advanced) | `dumpsys sensorservice restrict <pkg>` / `enable` (probe support) | High | `enable` |
| Battery saver | Through `PowerManager` system API in the worker (verify method) | Low | Previous state |
| Reset system battery stats (advanced) | `dumpsys batterystats --reset` | High: loses system history | Not revertible; two confirmations |
| Charge control (root, experimental) | Out of v1 scope (ADR-0007): not built; stays hidden and off | High | n/a in v1 |

### 13.3 Doze profiles

| Profile | Intent |
| --- | --- |
| Stock | Device default (empty string) |
| Balanced | Deep idle about 30 minutes after screen off |
| Aggressive | Deep idle about 10 minutes after screen off, longer idle windows |
| Imported | The current external value, captured verbatim with a name |

Constant names and valid keys differ by Android version. The agent MUST read the key list from `dumpsys deviceidle` on the device and build profiles only from keys that exist there.

## 14. Onboarding, permissions and settings

Onboarding takes at most four screens and ends on a real result, never on a tour. Every permission request states the benefit in one sentence before the system dialog.

### 14.1 Onboarding flow

1. **Welcome.** One sentence of value and two choices: "Connect Shizuku" (recommended) or "Start in basic mode".
2. **Notifications and usage access.** `POST_NOTIFICATIONS` for the monitor and alerts; Usage access (settings intent) for per-app foreground time. Both skippable.
3. **Shizuku.** Detect: not installed, stopped, running (shell or root), Sui. Request permission. On a 5 s hang or a failure on Android 17, show the compatible-manager explanation (4.5) and the ADB commands as a copy-ready block. One quiet line says Android 17 needs a Shizuku fork or Sui (ADR-0028).
4. **Bootstrap result.** The capability summary in plain words first: "Coulomb can see per-app drain, wakelocks and kernel wakeups. Power rails: not exposed on this device." Then:
   - "Keep the monitor running" (14.2), when the OEM needs it;
   - the checklist of grants (4.4), saying which ones stay after Shizuku stops or the phone restarts;
   - a key to the four provenance glyphs (6.7).

   The footer button, "Show my battery", says when the first figures appear (ADR-0023).

Then land on Now with the first verdict. Calibration (6.1) runs in the background; the Health screen explains: "First capacity estimate after a charge from 30% or less to 80% or more."

### 14.2 Keeping the monitor alive

- Ask the user to add Coulomb to One UI "Never sleeping apps" with a deep link to the battery settings; explain why in one sentence.
- Do not request a Doze whitelist for Coulomb by default: Coulomb should sleep when the phone sleeps.
- On `BOOT_COMPLETED`, start the monitor if Full mode is on, and rebind the worker when the Shizuku binder arrives.

### 14.3 Privileges screen

Always reachable from the top app bar. Shows the active manager (Shizuku, Sui, fork version, uid), each grant with its state and a repair button, the full Capability Matrix and the last probe time. "Re-run probes" and "Export capability report" live here.

### 14.4 Settings and defaults

| Setting | Default | Options |
| --- | --- | --- |
| Monitoring mode | Full | Full, Lite |
| Screen-on sampling | 5 s visible / 30 s background | 2–60 s |
| Charging sampling | 1 s on Charging screen / 5 s screen on / 30 s screen off | Fixed ranges |
| Charge target alarm | Off (80% suggested) | 50–100% |
| Temperature alarm | 42 °C | 38–50 °C, off |
| Insights digest | On, 09:00 | Time, off |
| Theme | System | Light, Dark, True black, Wallpaper color accent |
| Language | System | Türkçe, English |
| Units | mA, W, °C | °F |
| Show system apps in Drain | Off | On |
| Scale attribution to measured | Off | On (values marked Derived) |
| Action Center | Off | On (grants `WRITE_SECURE_SETTINGS`) |
| Automation API | Off | On |
| Data retention | As 7.7 | Longer raw retention |
| Developer console | Hidden | Unlocked by tapping the version 7 times; read-only typed queries |

### 14.5 Data and export

- Export: CSV per table, a JSON bundle, and a raw bundle (`batterystats` checkin and history from the worker) usable with Battery Historian on a computer.
- Import: Coulomb's own JSON bundle only, for moving to a new phone.
- "Delete all data" with confirmation; it leaves privileges untouched.
- Android Auto Backup excludes the database and snapshot blobs; settings may back up.

## 15. Engineering architecture

Coulomb is a multi-module Kotlin and Jetpack Compose app with a strict one-way flow: system sources → privileged layer → parsers → analytics → database → UI. Only `:core:privileged` touches Shizuku, root or hidden APIs.

### 15.1 Toolchain

- Kotlin 2.x (K2), JDK 21 toolchain, newest stable Android Gradle Plugin that supports `compileSdk` 37, Gradle version catalog, KSP (no kapt).
- Compose BOM newest stable; `material3` per 11.7; R8 full mode; baseline profiles; convention plugins in `build-logic/`.

### 15.2 Modules

| Module | Responsibility | May depend on |
| --- | --- | --- |
| `:app` | Activity, navigation shell, DI root, manifest | All features |
| `:core:model` | Pure Kotlin domain types | Nothing |
| `:core:common` | Dispatchers, result types, clocks, formatting | `:core:model` |
| `:core:database` | Room entities, DAOs, migrations | `:core:model` |
| `:core:datastore` | Proto DataStore settings | `:core:model` |
| `:core:privileged` | Shizuku and Sui client, AIDL, UserService, allowlisted commands, root client, capability probes | `:core:model`, `:core:common` |
| `:core:parsing` | Proto and text parsers (pure JVM) | `:core:model` |
| `:core:analytics` | Calibration, sessions, capacity, attribution diffs, provenance | `:core:model` |
| `:core:collect` | Foreground service, triggers, samplers, `CollectorActor` | privileged, parsing, analytics, database |
| `:core:insights` | Rule engine, thresholds, Drain Story templates | analytics, database |
| `:core:designsystem` | Tokens, theme, type, motion scheme, components, charts | Compose only |
| `:core:ui` | Shared composables (rows, metrics, states) | designsystem, model |
| `:feature:now`, `:feature:drain`, `:feature:appdetail`, `:feature:timeline`, `:feature:health`, `:feature:charging`, `:feature:actions`, `:feature:onboarding`, `:feature:settings` | Screens and ViewModels | core modules, never each other |
| `:widget` | Glance widgets, tile | core modules |
| `:wear` (optional) | Watch tile and complication | `:core:model` |
| `:benchmark` | Macrobenchmark, baseline profile generator | `:app` |
| `:lint` (build tooling, ADR-0016) | Android Lint checks for the no-wakeup policy (7.4), loaded by every Android module through `lintChecks` | Nothing |

`design-lab/` (HTML) sits outside Gradle.

### 15.3 Libraries

Resolve every version at build time (rule 0.3.2). Expected set: Compose BOM, Material 3 and Material 3 Adaptive (navigation suite, list-detail), Navigation 3 if stable (else type-safe Navigation Compose), Lifecycle, Hilt with KSP, Room, Proto DataStore, kotlinx-coroutines, kotlinx-serialization, kotlinx-collections-immutable, Wire (protobuf), Glance, WorkManager (Lite mode only), Shizuku API and provider, Sui API, HiddenApiBypass (feature-flagged), libsu (only for the backlogged root-manager layer, ADR-0028), LeakCanary (debug only). Tests: JUnit, Truth, Turbine, Robolectric where useful, Roborazzi or Compose Preview Screenshot Testing, Macrobenchmark, UI Automator.

No library may pull in `INTERNET` or analytics. A Gradle task fails the build if the merged manifest contains `android.permission.INTERNET`.

### 15.4 Manifest permissions

Declared: `POST_NOTIFICATIONS`, `POST_PROMOTED_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED`, `PACKAGE_USAGE_STATS`, `BATTERY_STATS`, `DUMP`, `INTERACT_ACROSS_USERS` (T1 batterystats dumps, ADR-0010), `WRITE_SECURE_SETTINGS` (granted only on demand), `QUERY_ALL_PACKAGES` (labels for every UID; personal distribution), Shizuku's API permission (via the library). Not declared: `INTERNET`, `READ_LOGS`, location, phone state.

### 15.5 Code standards

- Unidirectional data flow: ViewModel exposes one immutable `UiState` `StateFlow`; events go up as functions.
- Immutable UI models (`@Immutable`, immutable collections) so Compose skips work; stable keys on every lazy list.
- Coroutines only; no `GlobalScope`; I/O on a limited-parallelism dispatcher; parsing on `Default`.
- No `!!`; `explicitApi()` in core modules; detekt and ktlint with Compose rules; Android Lint with warnings as errors.
- Every system or hidden API call sits in one file per service inside `:core:privileged`, with the AOSP link and the SDK range it was verified for.
- StrictMode in debug (disk, network, leaked closables); `ProfilingManager` triggers in debug (3.3).
- R8 keep rules for AIDL, Shizuku and Wire classes, tested by a release-build smoke test.

### 15.6 Build, CI and distribution

- Variants: `debug` (StrictMode, developer console), `release` (R8, signed with the owner's key), `benchmark`.
- CI (GitHub Actions): build, unit and parser golden tests, screenshot diff, detekt, lint, manifest permission check, release assemble.
- Releases: signed APKs on GitHub Releases with semantic versions, so the owner can track updates with Obtainium.

## 16. Testing and accuracy validation

Accuracy claims ship only with a passing protocol from 16.6; parsers ship only with golden tests on real dumps from the primary device. Test results are committed under `docs/validation/`.

### 16.1 Unit tests

- Analytics: calibration on synthetic Samsung-style (mA, inverted) and AOSP-style (µA) traces; capacity estimator with known ground truth; window diffs across stats resets (property-based tests); session splitting across reboots and time changes.
- Insights: each rule with a firing case, a non-firing case and a boundary case.
- Formatting: Turkish and English locale output for every metric type.

### 16.2 Golden fixtures

Capture on each device and OS build, through ADB, into `fixtures/<device>/<build>/` with a README noting date, state (charging, screen) and uptime:

```
adb shell dumpsys batterystats -h
adb shell dumpsys batterystats --proto > batterystats.proto.bin
adb shell dumpsys batterystats --checkin > batterystats.checkin.txt
adb shell dumpsys batterystats --history > batterystats.history.txt
adb shell dumpsys alarm > alarm.txt
adb shell dumpsys deviceidle > deviceidle.txt
adb shell dumpsys power > power.txt
adb shell dumpsys jobscheduler > jobscheduler.txt
adb shell dumpsys suspend_control_internal > suspend_control.txt
adb shell dumpsys battery > battery.txt
adb shell dumpsys thermalservice > thermal.txt
```

Commands that fail are recorded as unsupported, not skipped silently. Parsers must parse every fixture without exceptions; unknown-field counts are asserted so regressions show up.

### 16.3 Integration tests

- UserService contract tests (instrumented, need Shizuku on the device; skipped with a reason on CI).
- Failure drills: kill Shizuku mid-dump, revoke each grant, simulate a fingerprint change, return a truncated stream. Expected: no crash, correct degraded state, correct copy.
- Mode switch (ADR-0028), on the API 37 emulator with a Shizuku fork: start Shizuku as root, kill it while Coulomb collects, restart it in shell mode; then the reverse. An automated check proves no fake zeros, no double counting and no crash.
- Collector: simulated trigger sequences (screen off for 8 h, plug at 20%, reboot mid-session).
- Time-compressed stress test (ADR-0019): a 30-day trigger sequence on a simulated event clock with synthetic data, covering daily screen cycles, nightly charging, 8 h screen-off nights, reboots mid-session, and retention and compaction runs. It runs on the API 37 emulator in under 10 minutes; the sequence runs a 31st day so that every 30-day cut has run. Pass: no crash and no ANR; heap after GC at the last day within 10% of heap after GC at day 1; database size within the 7.7 projection: the day-31 file, plus the day-16-to-31 growth of every row that 7.7, ADR-0015 and ADR-0021 do not cap extended to day 365, with one-minute aggregates turning hourly after 180 days, stays under 150 MB (10^6 bytes; ADR-0020, ADR-0021).

### 16.4 UI tests

- Screenshot suite per 11.12, diffed in CI.
- Accessibility: Compose accessibility checks on every screen; a TalkBack script covering onboarding, Now, Drain and an action with revert.
- Navigation and predictive back on compact and expanded layouts.

### 16.5 Performance

| Check | Target |
| --- | --- |
| Cold start to verdict (Macrobenchmark, S25 Ultra) | p50 < 800 ms, p90 < 1.2 s |
| Timeline pan, 24 h range | < 1% janky frames |
| Heavy snapshot | < 1.5 s wall, < 300 ms app CPU |
| Parse 20 MB dump | Peak heap < 64 MB |
| APK size (release) | < 15 MB |

### 16.6 Accuracy protocols

| Protocol | Method | Pass |
| --- | --- | --- |
| A. Calibration | 30 min fixed load (video, fixed brightness). Compare integrated `CURRENT_NOW` with the `CHARGE_COUNTER` delta | Agree within 5% |
| B. Screen-off drain | 8 h overnight, the same night as E. Coulomb's %/h vs (level delta ÷ hours) vs the system battery usage graph | Within ±0.1 %/h |
| C. Attribution | 1 h script: 20 min video, 10 min navigation, idle remainder. Compare per-app share with the system Battery usage screen and raw `dumpsys batterystats` | Within ±1 percentage point |
| D. Capacity | Three qualifying charge sessions. Compare with the system and OEM health values | Within ±3% |
| E. Self-cost | One night in Full mode, the same night as B: ≥ 8 h with the screen off and unplugged. Read the system's mAh, partial wakelock, alarm and wakeup records for Coulomb's UID at the start and the end of the night (ADR-0019) | While the screen is off: 0 wakeups and 0 alarms from Coulomb; ≤ 0.1% of capacity for the night; Coulomb partial wakelocks < 30 s in total |
| F. Privilege drills | 16.3 failure drills on the real device | All pass |
| G. OS matrix | Parsing and UI on the API 37 emulator; A–F on the phone; API 36 emulator only for a reported Android 16 issue | All pass or documented as hardware-limited |

### 16.7 Device matrix

| Device | OS | Role |
| --- | --- | --- |
| API 37 AVD on the emulator host | Android 17 | Default target: instrumented, UI and on-device parser tests |
| Galaxy S25 Ultra (`sm-s938b`) | One UI 9 (Android 17) | Protocols A–F, Samsung probes, fixtures; only with the owner's permission (0.5) |
| API 36 AVD on the emulator host | Android 16 | Only to reproduce an Android 16-specific bug |
| Pixel 8 or newer (if one becomes available) | Android 17 | Power rails (6.6) |

## 17. Delivery phases

Seven phases, each closed by an exit gate; the agent never starts a phase while the previous gate is red, except that P3 starts while the P2 gate waits for its phone night (ADR-0019). Data correctness comes before UI, and design direction is locked in HTML before any Compose screen is built.

&#91;embedded content: Delivery roadmap · 7 phases, 7 gates\]

The design-lab gate at P3 is the only planned human checkpoint; every other gate is checked by tests and `/impeccable` runs.

1. **P0 Foundations.** Repo, modules, convention plugins, CI, `SPEC.md`, `CLAUDE.md`, `AGENTS.md`; `/impeccable init`; fixture capture from the S25 Ultra (16.2); first capability report.
   - Gate: CI green; fixtures and capability report committed; manifest check proves no `INTERNET`.
2. **P1 Privileged core.** Shizuku and Sui client, UserService with streaming AIDL, bootstrap grants, typed command allowlist, capability probes, failure handling (4.5).
   - Gate: protocol F passes on the device; a 20 MB dump streams without OOM; bind-timeout fallback demonstrated.
3. **P2 Data engine.** Parsers with golden tests, Room schema, collector actor, triggers, samplers, sessions, calibration, references, retention.
   - Gate: 100% fixtures parse; the stress test (16.3) passes; protocols A and E pass on the phone, E in one night (no UI yet). P3 does not wait for that night: the gate closes when its report arrives (ADR-0019). Longer runs are optional and hold no gate.
4. **P3 Design lab.** HTML prototypes of the seven key screens, `/impeccable` loops, tokens locked in `DESIGN.md`.
   - Gate: `npx impeccable detect` returns zero; critique and audit show no major findings; one human review of the direction (the only scheduled human checkpoint). While waiting, continue P4 work that has no UI: analytics wiring, ViewModels, navigation skeleton.
5. **P4 Core screens.** Now, Drain (all lenses), App detail, Health, Charging, onboarding, Privileges; analytics for capacity and attribution.
   - Gate: per-screen `/impeccable` gates (11.12); protocols B, C and D pass; Macrobenchmark targets met; the mode-switch test (16.3) passes on the API 37 emulator (ADR-0028).
6. **P5 Depth.** Timeline, Night Report, insight engine with all 20 rules, Drain Story, Action Center, notifications and Live Updates, widgets, tile.
   - Gate: whole-app `/impeccable` gate (two clean consecutive runs); protocols E and G pass; drift detection demonstrated with an external Doze change.
7. **P6 Finish.** Export and import, true black, DeX and expanded polish, automation API, Wear tile (optional), release signing, Obtainium-ready releases.
   - Gate: every item in section 18 checked.

## 18. Final acceptance checklist

The app is done when every box below is ticked with evidence linked in `docs/progress.md`.

**Platform and privileges**

- [ ] Runs on Android 16 (API 36) and Android 17 (API 37); targets 37; adaptive at every width, including DeX.
- [ ] Works with Shizuku forks, with Sui, and in basic mode without any of them; official Shizuku on Android 17 is untested (ADR-0028).
- [ ] Switches between shell and root Shizuku both ways without a restart; root-only data shows "not collected", never zero (16.3 mode-switch test).
- [ ] 5 s bind timeout and fallback proven on the device; every failure mode in 4.5 shows its specified copy.
- [ ] Durable grants survive a Shizuku stop and a reboot; repaired in one tap after revocation.
- [ ] Capability report exported for the S25 Ultra build in use.

**Data and accuracy**

- [ ] Every source in section 5 is either implemented or marked unsupported by probe, with reason.
- [ ] All fixtures parse; unknown-field counts asserted.
- [ ] Protocols A to G pass (16.6), results committed.
- [ ] Every metric on screen shows provenance and follows the rounding rules (6.7).
- [ ] Unattributed drain appears whenever it exceeds 10%.

**Battery cost**

- [ ] Protocol E passes on one 8 h screen-off, unplugged night in Full mode: 0 wakeups and 0 alarms from Coulomb, ≤ 0.1% of capacity, partial wakelocks < 30 s.
- [ ] No alarms or long wakelocks anywhere (lint check green).
- [ ] Nothing animates while idle.

**Features**

- [ ] 100% of the parity matrix (section 2).
- [ ] All 20 insight rules with tests; Drain Story in Turkish and English.
- [ ] Action Center: every action previewed, logged, verified, revertible; drift detection works; external Doze managers respected.
- [ ] Charging Live Update promoted where the OS allows, with a correct fallback.
- [ ] Four widgets, Quick Settings tile, automation API.
- [ ] Export, import and delete all data.

**Design**

- [ ] `npx impeccable detect design-lab/` returns zero findings.
- [ ] Two consecutive whole-app `/impeccable audit` and `/impeccable critique` runs with nothing above minor.
- [ ] Screenshot suite green in all themes, three font scales, two widths and both languages.
- [ ] WCAG 2.2 AA contrast; TalkBack script passes.

**Engineering**

- [ ] No `INTERNET` in the merged manifest.
- [ ] Performance targets in 16.5 met.
- [ ] The 30-day time-compressed stress test passes (16.3): 0 crashes, 0 ANRs, heap growth after GC < 10%, database within the 7.7 projection.
- [ ] Every deviation from this spec has an ADR.
- [ ] Signed release published on GitHub Releases.

## 19. Appendix

### 19.1 Worker allowlist

The complete set of shell operations the UserService may run. Anything else requires a spec change and an ADR.

| Group | Operations |
| --- | --- |
| Read | `dumpsys batterystats` (`-h`, `--proto`, `-c`, `--history`, `-c --history-start`; ADR-0005, ADR-0014), `dumpsys alarm`, `deviceidle`, `power`, `jobscheduler`, `battery`, `thermalservice`, kernel wakelock dump (probe), `am get-standby-bucket`, `appops get`, `settings get` (allowlisted keys), `cmd deviceidle whitelist`, sysfs reads under `/sys/class/power_supply`, `/sys/class/wakeup`, `/sys/power/suspend_stats` and `/sys/kernel/wakeup_reasons`, the kernel log (`/dev/kmsg`) filtered to suspend and resume lines (ADR-0028) |
| Bootstrap | `pm grant` for the permissions in 4.4 only (BATTERY\_STATS, DUMP, PACKAGE\_USAGE\_STATS, INTERACT\_ACROSS\_USERS, and WRITE\_SECURE\_SETTINGS with the Action Center; ADR-0010), Coulomb's package only, `appops set` for Coulomb's `GET_USAGE_STATS` |
| Actions | The operations in 13.2, each with its inverse |
| Advanced | `dumpsys batterystats --reset` (two confirmations). Charge-control writes are out of v1 scope (ADR-0007) |

### 19.2 Risks and open questions

| Risk | Mitigation |
| --- | --- |
| A future Android or One UI update removes or changes `dumpsys` flags or proto fields | Probes, versioned parsers, fixture capture after every OS update |
| Shizuku compatibility shifts again | Fork-agnostic client, bind timeout, durable grants as the safety net |
| One UI kills the foreground service | "Never sleeping apps" onboarding step; Lite mode loses nothing in attribution |
| Samsung exposes no power rails | Attribution stays Modeled and is reconciled with measured coulombs |
| `material3` Expressive APIs not yet stable | Pin and isolate in `:core:designsystem`, ADR |
| Hidden API calls break on an ART update | Run them only in the worker process; in-app path stays behind a probe |

Owner decisions on the former open questions (2026-11-06, ADR-0007):

- App name: **Coulomb** (`io.github.akane599.coulomb`). Icon direction still comes from the design lab (P3).
- Wear OS tile: optional in Phase 6; syncs on change or periodically, never live.
- Root-only charge control: hidden and off, out of v1 scope.

### 19.3 Glossary

| Term | Meaning |
| --- | --- |
| Partial wakelock | An app's request to keep the CPU running with the screen off |
| Kernel wakelock / wakeup source | A driver-level hold that keeps the device from suspending |
| Wakeup reason | The interrupt that brought the device out of suspend |
| Deep sleep (suspend) | CPU suspended; the lowest-power state with the screen off |
| Doze (light, deep) | Android's idle modes that batch background work |
| Standby bucket | Android's ranking of how often an app may run in the background |
| `BatteryUsageStats` | The platform's modeled per-UID power use, by component and process state |
| Coulomb counter | The fuel gauge's measured charge counter (`CHARGE_COUNTER`) |
| Power rail / ODPM | On-device power monitor measuring energy per hardware rail |
| CC / CV | Constant-current and constant-voltage charging phases |
| State of health | Usable capacity as a share of design capacity |
| Provenance | Whether a number is Measured, Modeled, Estimated or Derived |

### 19.4 Sources

- [Android 17 release notes](https://developer.android.google.cn/about/versions/17/release-notes?hl=en)
- [Android 16 behavior changes, all apps](https://developer.android.com/about/versions/16/behavior-changes-all)
- [SystemHealthManager reference](https://developer.android.com/reference/android/os/health/SystemHealthManager)
- [Excessive partial wake locks metric](https://developer.android.com/blog/posts/raising-the-bar-on-battery-performance-excessive-partial-wake-locks-metric-is-now-out-of-beta?hl=en)
- [Live Updates requirements, Android 16 QPR1](https://www.androidauthority.com/android-16-qpr1-live-updates-3573399)
- [Shizuku on Android 17, issue #2180](https://github.com/RikkaApps/Shizuku/issues/2180)
- [Impeccable README](https://github.com/ourslyio/impeccable)
- [One UI 9 stable for the Galaxy S25 series](https://androidauthority.com/samsung-one-ui-9-galaxy-s25-fold-7-flip-7-stable-3716021)
- [BatStats listing](https://alternativeto.net/software/batstats/about) · [Batt listing](https://alternativeto.net/software/batt/about)
