# Questions that can wait

The agent keeps working with the stated default until the owner answers (SPEC 0.6). Re-read at the
start of every phase. Answered questions move to the bottom with the answer and date.

## Open

- 2026-11-23. **SPEC 16.4's Compose accessibility checks run nowhere (audit TEST-002).** The
  screenshot suite composes every screen and compares pixels; nothing runs the Accessibility Test
  Framework over those trees, so a missing label or a target under 48 dp passes CI. Wiring it
  means a new test dependency (Roborazzi's accessibility-check module, at the Roborazzi version
  in use, to be confirmed as published), a check in each module's screenshot helper, and then a
  first wave of findings over some 420 screen tests, each fixed in UI code (which can move
  goldens) or suppressed with a recorded reason. That is a piece of work with its own review,
  not a fix beside others. Default in use: not wired; SPEC 18's accessibility row stays "Partly"
  and names this. Suggested order when it is taken up: a spike on `:feature:now` to count
  findings, the check at warning level across all modules, then at error level.
- 2026-11-22. **An app removed inside a long window (audit BUG-062).** A reference, day or
  session window diffs the counters at its ends, and keeps a snapshot between them only around a
  reset, a restart or, since 79eba39, the last place an app gone from the end's counters was seen.
  Retention deletes the counters of such snapshots after 2 h (ADR-0015), so a window rebuilt
  later (Today and Since unplugged are rebuilt at every screen open; a day window at the next
  day's first heavy snapshot) again loses the earlier use of an app removed more than 2 h before.
  A whole fix stores something new: keep that snapshot's counters until the day window spanning
  it is built, as the restart edges are (ADR-0055), which needs a marker on the snapshot, or carry
  the removed app's last counters forward into later snapshots until the next reset. Either is a
  data format change and needs a second opinion (ADR-0027). Default in use: the 2 h fix only; a
  removed app's use can be missing from a long window, never counted twice.
- 2026-11-21. **Release key and the first release (hard stop, SPEC 0.6).** The build signs a
  release when four environment variables name a key, and `tools/release/build.sh` builds and
  checks the APK without publishing (ADR-0067, `docs/releasing.md`). Still the owner's: creating
  the key (the `keytool` line is in `docs/releasing.md`) and keeping its backup; the first
  version number (default proposed: `0.1.0`, as a pre-release, until SPEC 18 is ticked; `1.0.0`
  after); the tag and the GitHub Release. Also the owner's: whether the phone moves to the signed
  build. It cannot be installed over the development build there, so that build would have to
  be removed, and its history would come back only as an imported history beside a new one.
  Update 2026-11-22: the owner gave the key (`keystore.properties`, untracked), and 0.1.0 is built,
  signed and verified. The owner's decision: publish only after the R8 smoke test has run on the
  emulator or the phone (audit TEST-002). Default in use: nothing is tagged or published, and the
  phone keeps its build.
- 2026-11-21. The wide layouts (SPEC 10.10) are checked through screenshot tests at fixed widths
  only. Not seen: a freeform or DeX window resized across the width classes, an external
  display, the mouse wheel and the keyboard on the charts. Default in use: an emulator run in a
  resizable window is queued with P6's device tests; DeX itself needs the phone and waits with
  the other phone checks.
- 2026-11-21. **The Automation API's permission keeps stock automation apps out** (ADR-0065 §1).
  SPEC 12.7 asks for a "signature-or-user-granted permission". Android grants a permission only to
  an app whose own manifest names it, so an automation app that was not built for Coulomb cannot be
  allowed; root can call the API without it (`docs/automation-api.md` has the commands). The other
  shape, no permission and a secret the user pastes into the automation app, was set aside: a
  secret in an intent extra is readable by more than its receiver. Default in use: the permission
  as the spec names it. Say so if a pasted token should be offered beside it.
- 2026-11-21. The Automation API (ADR-0065) is built and tested on the build server only. Not seen
  on a device: Android's dialog for the permission, a call and a query from an app that holds it,
  from one that does not, from root and from the shell, and a query to a force-stopped Coulomb.
  Default in use: an emulator run with a small caller app is queued with P6's device tests.
  `APPLY_DOZE_PROFILE` writes `device_idle_constants` through the Action Center, so it waits for
  the same yes as the Action Center's writes below; nothing of it is run until then.
- 2026-11-21. Automation calls are rows of the action log, as SPEC 12.7 says, and not a table of
  their own (ADR-0065 §8). Two kinds of call are not written: the same refusal again within the
  hour, and a call refused for its rate (more than ten a minute, or beyond 100 logged calls a
  day). Default in use: built that way, so that an app in a loop cannot fill the log.
- 2026-11-21. Settings' new rows (ADR-0064) are built and tested on the build server only. Not
  seen on a device yet: the chosen theme at a cold start (the first frame is held until the
  stored theme is read), Android's per-app language page, Lite mode (the service stopping and
  starting on a mode change, the six-hour job), the collector's start after a boot now that the
  receiver reads the mode first, and the first start after a restore from a backup. Default in
  use: an emulator run is queued with P6's device tests; none of it needs the phone.
- 2026-11-21. "Screen-on sampling" (SPEC 14.4: "5 s visible / 30 s background", options 2–60 s)
  is read as the rate while a Coulomb screen shows on battery; behind another app it stays 30 s,
  or the chosen rate when that is slower, and charging's rates stay fixed (ADR-0064 §4). "Longer
  raw retention" is one switch, 60 days instead of 14 (§6). Say so if either should be otherwise.
- 2026-11-21. **Import keeps another phone's history apart; it does not merge it** (ADR-0062 §9,
  after a second opinion). SPEC 14.5 says import is "for moving to a new phone". Merged rows would
  put the old phone's capacity, cycles, baselines and app UIDs under this phone's name, so an
  imported bundle becomes an archive: "Data and export" lists it with where it is from, its dates,
  its session counts and its size, and it can be deleted. Default in use: no screen draws an
  archive's sessions yet; the bundle's neutral settings can be applied. Say so if the old phone's
  history should be browsable on Coulomb's screens, or continue this phone's charts.
- 2026-11-21. Whether Battery Historian accepts the raw bundle's two files (`batterystats -c` and
  `--history`) without a full bug report is not verified; it needs Historian on a computer and a
  bundle from the phone. Default in use: the bundle holds both dumps as Android printed them, and
  its README says the check has not been made.
- 2026-11-21. "Delete all data" restarts Coulomb's process after clearing, because the collector
  holds the boot, the calibration and the capacity in memory. The restart is the usual
  start-the-launcher-activity-then-exit and has not run on a device. Default in use: built that
  way, tested against a stand-in; an emulator run is queued with P6's device tests.
- 2026-11-21. **Needs a yes before it is run (device writes).** The Action Center's commands
  (`appops set`, `am set-standby-bucket`, `cmd deviceidle whitelist`, `settings put global
  device_idle_constants`, `cmd power set-mode`, `am force-stop`, `dumpsys batterystats --reset`)
  are built from AOSP source and have never been run. Proposed: one run of each, with its revert,
  on the API 37 emulator against a test app. Default in use: nothing is run; the engine is tested
  against fakes, and every action is verified at run time by reading the value back (ADR-0060).
- 2026-11-21. Three rows of SPEC 13 are not built as written, with the reasons in ADR-0060: "Deny
  wakelocks" (AOSP records the setting and does not act on it), "Sensor restriction" (the command
  restricts every app except the named one), and the Doze profile "Stock" (it restores the value
  found on the phone, not an empty string, because the phone ships with its own). Default in use:
  the first two are shown as not offered, with the reason. Whether One UI acts on the wakelock
  setting would be a phone test.
- 2026-11-21. **R15 waits for a two-minute look at the phone.** The rule needs "battery protection
  off". One UI keeps the choice in `global: protect_battery` (3 on the phone when its fixture was
  taken), and nothing Coulomb can read says what each number means; it is Samsung's own key, so
  AOSP does not settle it. Needed: the owner switches the protection modes in Settings while
  Coulomb reads the key. Default in use: the setting counts as unknown, so R15 finds nothing (it
  cannot claim the setting is off).
- 2026-11-21. **Needs a yes before it is built (it deletes data).** Windows stored before the fix of
  ADR-0055 hold drain that never happened: on the phone's data of 2026-11-21, 21 consecutive windows
  across a system restart (about 47,800 mAh modeled, 900 mAh measured) and four day windows that span
  one. Proposed: after the update, one repeatable sweep deletes every consecutive window whose two
  ends are a system-restart pair and clears the modeled total and detail of the day windows that span
  one. Default in use until answered: nothing is deleted; the consecutive windows leave by retention
  after 30 days, the day windows stay, and insights computed over those days read too high.
- 2026-11-21. R09 says "wakeup sensor", but the stored counters give sensor time per app without
  saying which sensors are wakeup sensors. Default in use: the rule counts every sensor except GNSS
  held while the screen is off, and its copy says "a sensor" (ADR-0054). Telling them apart needs a
  per-sensor counter and a map from sensor handle to its wakeup flag.
- 2026-11-09. One UI's "Never sleeping apps": no public intent opens that list. The keep-running
  button opens `Intent.ACTION_POWER_USAGE_SUMMARY` (battery settings), falling back to the app's
  details page; the Turkish copy calls the list "Hiç uyumayan uygulamalar". Default: as is until
  checked on the phone; correct the Turkish name to One UI's own wording then (ADR-0047).
- 2026-11-09. Samsung prints two health-like values in `dumpsys battery`: `mSavedBatteryAsoc` (100) and
  `mSavedBatteryBsoh` (96). Which one does Settings > Battery > Battery information show on the
  phone? Default: Health shows both, each named by its key, and neither stands for "the" OEM health
  (ADR-0042 §3).
- 2026-11-07. Protocol B's capacity: the night report turns the level change into mAh with the capacity
  the platform reports (`pws`, the summary's capacity). Coulomb's own estimate (SPEC 6.3) could replace
  it once it exists. Default: the platform's capacity, so B does not test Coulomb against itself.
- 2026-11-07. Protocol B's rounding: levels are whole percents, so a night's level-delta rate is known
  only to about 1/hours %/h. A gap above the 0.1 %/h pass mark but within that rounding reads
  "inconclusive" (ADR-0020). Default: inconclusive is neither pass nor fail; a second night decides.
- 2026-11-08. Time left on Now projects the current screen state's rate, as the reviewed lab shows: with
  the screen on, it is the time left if the screen stays on. The independent review proposed instead
  integrating hour-of-day profiles of whole past discharges (nights included) forward to empty, which
  reads longer and closer to a typical day. Default: per screen state (ADR-0033 §5).
- 2026-11-09. Drain at 1000 dp and up leaves the summary column mostly empty below the reconcile bar
  (`drain-apps-expanded`). Default: left as is. App detail now opens beside Drain at expanded
  widths (ADR-0041 amendment), which fills the width while an app is open; Drain alone keeps its
  two columns.

- 2026-11-08. Now on expanded widths: one column capped at 720 dp, or two panes at 840 dp and up
  (hero, verdict and strip on the left; drains and live current on the right). Default: the capped
  column (ADR-0034 §15); two panes would need their own screenshots and an ADR. The same question
  covers a phone in landscape (915 × 412 dp): there the drains fall below the fold
  (`now-discharging-landscape`).
- 2026-11-08. Now's remainder row folds the unattributed energy into "Everything else"; Drain shows
  it separately on a dashed tile, as DESIGN.md describes. Default: folded on Now (ADR-0034 §14),
  because three rows plus two remainders crowd the screen and Drain is one tap away.
- 2026-11-08. Right after unplugging, Now hides the scale until time left exists (about 15 minutes).
  Default: hidden (ADR-0034 §16); the verdict and the strip still show.
- 2026-11-08. S4, the per-component breakdown, makes system_server rebuild the whole statistics session
  from the battery history at every heavy snapshot, a second time after S5, and that cost grows with the
  session. Only the phone can measure it. Default: a Component breakdown setting, Off, until a phone run
  shows at most 150 ms of added system_server CPU per snapshot at p90 (ADR-0035 §2). Until then Drain's
  chips and App detail's breakdowns show "Not broken down" and name the setting.
- 2026-11-09. Settings (SPEC 14.4) is in no phase of SPEC 17, but the Component breakdown setting and
  Drain's "It's in Settings" need it. Default: a minimal Settings screen ships in P4 with onboarding and
  Privileges (both SPEC 14), holding the settings that exist so far; the rest of 14.4 joins as each
  setting's feature lands.
- 2026-11-09. App detail's alarms: S5 names each wakeup alarm, but only the app's total is
  stored, so App detail shows the count and no per-alarm table (ADR-0041 amendment). Default: no
  table. Storing alarm names needs a schema change and its own budget check.
- 2026-11-09. App detail's window: the header names Drain's window and says to change it on Drain,
  instead of offering the lab's window button. Default: one window for both screens, so App
  detail's figure always equals the Drain row it came from.

## Answered

- 2026-11-08, settled by the emulator on 2026-11-11 (no owner answer needed). Work-profile apps:
  - With `INTERACT_ACROSS_USERS` (Full mode), `getPackagesForUid` resolves a managed profile's UIDs.
  - Without it, the call throws a `SecurityException`; it does not return null. That would have
    broken the collector's naming step.
  - `AndroidAppLabels` now falls back to `getNameForUid`, which needs no permission. A profile app
    that the main user also has keeps its name; one only in the profile keeps the fallback name.
  - `WorkProfileLabelsProbeTest` checks this on a profile.

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
