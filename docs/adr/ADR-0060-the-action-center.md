# ADR-0060: The Action Center

- Status: accepted (amends SPEC 13.2, 13.3 and 19.1 as listed in "What the spec's table becomes")
- Date: 2026-11-21
- Phase: P5 (SPEC 13, 14.4, 19.1)
- Builds on: ADR-0009 (battery settings keys), ADR-0027 (second opinions)

## Context

SPEC 13 lists the actions and their shell operations, and asks that each be previewed, logged
with its previous value, verified by reading back, revertible, and checked for drift. Before any
code, every operation was read in AOSP at `android-16.0.0_r4` and `android-17.0.0_r1`; the file and
line of each fact is cited where the code uses it. Several rows of SPEC 13.2 do not do what the
table says:

1. **Deny wakelocks.** `Notifier` starts the `WAKE_LOCK` op and throws its answer away
   ("XXX need to deal with disabled operations"), and `PowerManagerService` never reads the op. On
   AOSP the action changes nothing, and a read-back would still show `ignore`: proof of a change
   that did not happen.
2. **Sensor restriction.** `dumpsys sensorservice restrict <pkg>` puts the whole sensor service in
   restricted mode and lets `<pkg>` (matched as a substring) through: every app but the named one
   loses its sensors. That is the opposite of the row's intent.
3. **App-ops.** `appops set <pkg> <op> default` stores the mode `default`, which the consumers of
   `RUN_ANY_IN_BACKGROUND` treat as restricted. A mode set for the whole UID wins over the
   package's.
4. **Force stop** acts on every user unless told otherwise, prints nothing even for a package that
   does not exist, and leaves the app in the stopped state: no alarms and no pushes until it is
   opened again.
5. **Battery saver.** `cmd power set-mode` calls the same `setPowerSaveModeEnabled` in the system
   server that the spec's "PowerManager system API" names, prints nothing, and does nothing in
   either direction while the phone charges.
6. **Doze constants.** The setting overrides the device's defaults key by key; one malformed pair
   drops every override; `settings get` prints `null` for an unset key. The defaults differ by
   device: the phone's fixture reaches deep idle about 5 minutes after the screen goes off and the
   API 37 emulator in about 30 seconds, both sooner than SPEC 13.3's "Balanced, about 30 minutes".
   The phone's `device_idle_constants` is not empty as shipped in its fixture: it holds six pairs,
   one of them a key AOSP does not have. Writing SPEC 13.3's "Stock (empty string)" would delete
   them.

Nothing in this ADR was run on a device. Writes need the owner's yes, also on the emulator
(AGENTS rule 6).

A fresh read-only subagent gave a second opinion on a neutral brief (ADR-0027). It found the
non-empty stock string and that the app cannot read that key itself, and its recommendations are
taken unless a section says otherwise.

## Decisions

### Where it lives

1. The engine is the package `actions` in `:core:collect`: the only core module the graph lets
   hold the worker, the parsers and the database together, and the heavy snapshot's drift check
   starts there. A module of its own would need a spec change (SPEC 15.2). The collector's own
   code does not call it; it stays read-only.
2. Pure parts sit where pure code sits: the readers of command output in `:core:parsing`, the
   Doze profile builder in `:core:analytics`, the value types in `:core:model`.
3. The preview's command line is built from the same argument list the worker executes, so the
   two cannot differ.
4. The engine itself refuses to act while the Action Center is off, so no caller can skip the
   setting. One lock serialises every action.

### The worker

5. One typed method per operation, with closed sets for modes and buckets, and `--user` given on
   every command. The worker refuses Coulomb's own package and any package whose UID is below
   10000. It returns a status and at most 2 KB of output for display; whether an action worked is
   always decided by a separate read.
6. A `device_idle_constants` value must be printable ASCII without a leading `-`, at most 2,000
   characters, and a comma-separated list of `key=value` pairs or empty. Key names are not
   limited to AOSP's, so an OEM's own keys survive.

### What the spec's table becomes

7. **Deny wakelocks is not offered** (context 1). The Action Center says why. An OEM could
   enforce the op; whether One UI does is a phone test.
8. **Sensor restriction is not offered** (context 2). The per-app mechanism,
   `cmd sensorservice set-uid-state`, is not in SPEC 19.1; it also overrides the app's real state
   in the foreground and is lost at reboot. Not built.
9. **Restrict background** is offered only when the op reads `allow` for the package and no
   UID-wide mode exists, and its revert writes `allow`. `default` and `appops reset` are never
   used.
10. **Force stop** runs with `--user` for the current user. Its risk reads Medium, and its text
    says the app gets no alarms or notifications until it is opened. It is logged and has nothing
    to revert.
11. **Standby bucket** offers Rare and Restricted to an app whose bucket is Active to Restricted.
    Android may keep a higher bucket than asked (an exempt app); the read-back then says so and
    nothing is logged as done. The revert sets the bucket read before.
12. **Doze allowlist** adds to or removes from the user's list only; a system entry is shown and
    not changed.
13. **Battery saver** goes through `cmd power set-mode` (context 5: the same system call, without
    a hidden-API call in the worker) and is verified with `PowerManager.isPowerSaveMode()`. It is
    not offered while charging. It is logged; it is not watched for drift, because plugging in
    and a restart clear it by design.
14. **Reset battery statistics** needs two confirmations and counts as done only when the
    command prints "Battery stats reset.".
    Amended 2026-11-23 (audit DATA-019, DATA-001): a reply lost to a timeout or a dead worker
    does not say the command did not run. A reset or a force stop whose reply is lost keeps its
    record and answers "unverified"; neither record is a change in force. A failure the command
    itself reported still leaves no record.

### Doze profiles

15. **The baseline is the string found, kept verbatim.** A profile is the baseline's pairs with
    some keys changed; no baseline key is dropped. "Stock" means the baseline is written back
    (or the key deleted, when it was unset), never an empty string over a non-empty baseline.
16. **A profile never makes the phone doze later.** Keys that delay idle take the smaller of the
    device's value and the profile's target; idle durations take the larger. Keys that are not in
    the device's `dumpsys deviceidle` are left out (SPEC 13.3). `min_time_to_alarm`, the
    allowlist durations, switches and keys Coulomb does not know are never touched. A profile
    with nothing to change on this phone cannot be applied, and says how soon the phone already
    idles.
17. **Targets.** Balanced: `inactive_to` 15 min, `idle_after_inactive_to` 15 min. Aggressive:
    `light_after_inactive_to` 1 min, `inactive_to` 5 min, `idle_after_inactive_to` 5 min,
    `motion_inactive_to` 5 min, `idle_to` 2 h. These are SPEC 13.3's intents in keys; no saving
    is promised, because none was measured.
18. **A profile is applied only from stock.** While one is applied the engine refuses another
    and says to return to stock first, so a profile is always computed from the device's own
    values, not from another profile's.
19. **Ownership.** Coulomb owns the setting while it equals the value Coulomb last wrote. A value
    that is neither unset, empty nor Coulomb's is "Managed externally" and read-only (SPEC 13.1).
    Taking over records the value found, with a name, as the baseline; nothing is written.
20. **After a write** the effective values in `dumpsys deviceidle` are read too. If a changed
    key did not take its value, the baseline is written back and the action reports failure.

### The log

21. No schema change. `actionType` is a stable id, `target` the package or the profile,
    `afterValue` the value read back. A row is written before the command and completed after
    the read-back, or removed when nothing changed, so a change can never exist without its row.
22. An action is active while it has a state to restore and is neither reverted nor drifted.
    Drift is recorded once and the row is then left alone; a read that fails is never drift.
    One pass reads at most 24 rows and the next goes on where it stopped.
22a. **One row describes one value.** A second change of the same kind for the same app (or
    battery saver again) over a change still in force is refused, unless it is that change
    undone, which reverts the row. Two rows about one value could not both be put back. When
    the value has moved on since, the old row is marked as drifted and the new change is logged
    on its own.
22b. **A command that reports a failure is still read back.** A timeout does not say the change
    did not land. The row goes only when the value is read and unchanged; a revert counts as
    done when the value read is the one from before.
23. "Revert all" goes from the newest active row to the oldest and writes only where the current
    value is still the one Coulomb left; otherwise it records drift and moves on.
24. Each active row shows its revert as a copyable `adb shell` line, for the day the worker is
    not there.

### On screen

25. **The switch is on the Action Center's own screen**, beside what turning it on means and
    grants. Settings lists the Action Center with its state and opens it (SPEC 14.4). Turning it
    on stores the setting and asks the worker for the `WRITE_SECURE_SETTINGS` grant. Without a
    worker it is on all the same, and each action shows its command for adb (SPEC 4.4) wherever
    the command does not depend on a value read from the phone; a Doze profile's does.
26. **Turning it off** with changes in force asks first: revert them all, or keep them. The log
    stays, and a change in force can be reverted while the Action Center is off. Undoing is never
    refused.
27. **An app's actions are App detail's last section** (SPEC 10.4 item 8), with the value each
    would change. The three values are read when the section shows and the Action Center is on,
    each once per look; off, nothing is read. An app of another user or profile is not acted on:
    every command names the user Coulomb runs as. The Doze allowlist is the one Android gives no
    per-profile form: it keeps package names for the whole phone and stores their app ids
    (services/core/java/com/android/server/DeviceIdleController.java, android-17.0.0_r1
    L2989-3015, L5053-5081), so adding or removing an app reaches its copy in every profile. The
    action's effect says so before it is confirmed (amended 2026-11-22, audit BUG-021).
28. **An insight card's button** (SPEC 9.2 "Action offered") opens the app's actions as a sheet
    over the screen the card is on, for a rule that offers one; the list and a chosen action's
    preview share the sheet, and what runs from it is logged with the insight. The other offered
    actions open a screen of Coulomb or a page of the system's settings. Battery protection, the
    self-diagnostic and Lite mode have no place to open yet, so R15's and R20's cards have no
    button. `ActionId` moved to `:core:model`, so `:core:ui` can name the buttons.
29. **A taken-over value is named by the day it was taken over.** SPEC 13.3 asks for "a name";
    a typed one is not asked for.
30. **Revert is one tap on the log's row** (SPEC 13). A tap on the row itself shows its values
    and its revert as a command. "Revert all" asks once.

## Consequences

- Two rows of SPEC 13.2 are not built as written, and R01's and R02's cards lose "Deny
  wakelocks" as an offered action.
- On the phone as its fixture shows it, Doze profiles start as "Managed externally", and after a
  takeover Balanced has nothing to change.
- `WRITE_SECURE_SETTINGS` is still granted when the Action Center is turned on (SPEC 13). Nothing
  uses it yet: the app cannot read the key it would write. A blind restore without the worker is
  possible later.
- A row left by a crash between the command and the read-back reads as drift at the next heavy
  snapshot.

## Not verified yet

- Every command and every output format comes from source, not from a run. The first writes on
  the emulator need the owner's yes (`docs/questions.md`).
- Whether One UI enforces the `WAKE_LOCK` op, and whether its Doze keys take values the way
  AOSP's do.

## Reverting

Remove the `actions` package, the worker's action methods and the feature's screens. The action
log table stays as it was.

## Gate amendments (2026-11-21)

From the P5 per-screen gates (`docs/design-gates/2026-11-21-gates-action-center-settings.md`).

1. **Without the worker a preview still says how to undo**, where the inverse does not depend on
   a value read from the phone (restrict background, the Doze allowlist, battery saver), and
   that a change run by hand is not in the log and cannot be reverted by Coulomb. Where the
   inverse needs the value, the preview says so. A Doze profile's rows are inert there (§25).
2. **A profile row says what this phone would get**: "to N" only when the profile shortens the
   time to deep Doze; otherwise what it changes and that the time stays.
3. **A command is shown with break points after commas and spaces and is not selectable**; the
   copy button copies the raw line. A selection would carry the break marks into a terminal.
4. **A result is said where it happened**: a revert's under the Log's title, any other at the
   top, brought into view, with the confirm or reject haptic of SPEC 11.6.
5. **State rows read "Now: …"**, so a row's value is never mistaken for what the action sets.
6. **The log is one eager list.** Rows come only from the user's own actions.
7. **Risk stays a word.** Severity chips are the insights' vocabulary; the one irreversible
   action has its second confirmation.
