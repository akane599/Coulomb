# ADR-0065: The Automation API

- Status: accepted
- Date: 2026-11-21
- Phase: P6 (SPEC 12.7, 13.1, 14.4, 7.2, 7.4)
- Builds on: ADR-0027 (second opinion for a public API), ADR-0060 (the Action Center's engine and
  log), ADR-0062 §15 and ADR-0064 §9 (consents do not travel in a backup)

## Context

SPEC 12.7 asks for three broadcast actions and a read-only `ContentProvider` behind one
permission, with every call written to the action log with the origin "automation". It is the one
place where another app makes Coulomb act, and what other people write against it cannot be
changed later without breaking them. A fresh read-only subagent gave a second opinion on a neutral
brief (permission shape, what a call may change, the provider's rows, the log, versioning); this
ADR follows it except where a section says otherwise. A second independent subagent reviewed the
finished code for ways around the permission and the limits; what it found is under "Reviewed".

## Decisions

1. **The permission is Coulomb's own and `dangerous`.** `${applicationId}.permission.AUTOMATE`,
   defined in Coulomb's manifest and not requested by it, so `permissions-allowlist.txt` is
   unchanged. SPEC 12.7 says "signature-or-user-granted": a signature permission would admit only
   apps signed with Coulomb's key, which is no automation app, so the user grants it, per app, on
   Android's own permission page. An app must name the permission in its manifest to ask for it.
   Root reaches the API without it (`am broadcast` as uid 0); the shell user does not.
   Android refuses to install a package that defines a permission another signer's package
   already owns (`InstallPackageHelper`, `INSTALL_FAILED_DUPLICATE_PERMISSION`), so no other
   developer's app can hold the name first or redefine it. Coulomb still checks, when it opens the
   components, that the definition is its own and `dangerous`, and keeps them closed otherwise.
2. **Off means not there.** The receiver and the provider are `android:enabled="false"` in the
   manifest. The app enables them when SPEC 14.4's "Automation API" is on and returns them to the
   manifest's state when it is off, and it applies the stored setting at every process start, so a
   state left by a restore or by "Delete all data" is put right. The engine and the store read the
   setting again on every call. A restored backup starts with the setting off (ADR-0064 §9).
3. **Three actions, one answer.** One receiver takes `…action.SNAPSHOT_NOW`,
   `…action.SET_CHARGE_TARGET` (extra `…extra.CHARGE_TARGET_PCT`, an int 50–100 or the same as a
   decimal string) and `…action.APPLY_DOZE_PROFILE` (extra `…extra.DOZE_PROFILE`:
   `stock`, `balanced`, `aggressive`). A manifest receiver gets only explicit broadcasts, so a
   caller sets Coulomb's package. The answer is the ordered broadcast's result and nothing else:
   a code (1 done, 2 accepted, 3 unchanged, 10 invalid, 11 rate limited, 12 blocked, 13 failed,
   14 unverified; never 0, which is what an unanswered broadcast leaves), a reason word as the
   result data, and the API version in the result extras, with the seconds to wait for a rate
   limit. A value outside its range refuses the whole call; nothing is brought into range.
   The work runs off the main thread with the broadcast held open, and sets no alarm, job or
   wakelock (SPEC 7.4). The answer is due within eight seconds. Work that takes longer is not
   cut off, since a change half made is worse than a late one: the caller gets "unverified" with
   the reason `timeout`, and the log has the outcome. Extras that cannot be read end the call
   without an answer.
4. **A snapshot on request is a refresh, with limits.** `SnapshotTrigger.AUTOMATION` is appended
   to the trigger codes (a code list only grows at its end) and is treated as pull-to-refresh is:
   heavy, behind the collector's one-minute debounce. A call is refused while the screen is off
   and the phone is on battery (`screen_off`): an automation must not become a schedule that wakes
   the phone. At most four are accepted in any two hours, with the seconds until the next in the
   answer. The call waits up to six seconds for the snapshot before it answers, so the process is
   not frozen under it and the provider's figures are new when the answer arrives. The second
   opinion would refuse a snapshot while the collector's service is not running; it is taken
   through the collector instead, which works in Lite mode too and starts no service.
5. **The charge target takes SPEC 14.4's range.** It is the same stored setting the Settings row
   changes; the same value again answers "unchanged" and writes nothing.
6. **A call applies a Doze profile only as a repeat of the user's own change.** SPEC 13 says
   nothing runs without a user tap, and SPEC 12.7 lists `APPLY_DOZE_PROFILE`. Both hold when a
   call may only repeat a change the user previewed and applied on this phone: the action log
   must hold a Doze profile row whose origin is not "automation", for the same profile, from the
   value the phone holds now, to the value the profile would write now. Otherwise the answer is
   `needs_first_apply`. The change then runs through the Action Center with the origin
   "automation", with its preview's block, its read-back and its row. `stock` reverts the applied
   profile as its row's own button would, drift rule included, and is "unchanged" when none is
   applied. A call never takes a value over, and never reverts one profile to apply another
   (`profile_active`). The Action Center being off blocks the call (`action_center_off`).
7. **The provider gives out what the screens show, as rows that say what they are.**
   `content://${applicationId}.automation/v1/{info,level,rates,night,drains}`, every path with the
   same fifteen columns (`metric`, `rank`, `subject`, `subject_label`, `value`, `low`, `high`,
   `text`, `unit`, `provenance`, `quality`, `state`, `from_wall_ms`, `to_wall_ms`,
   `as_of_wall_ms`), one row per figure. Figures are rounded as on screen (SPEC 6.7), each carries
   its provenance, a range has `low` and `high` and no `value` (SPEC 6.8), and a figure Coulomb
   does not have is a row with a null value and a state (`collecting`, `unavailable`,
   `not_applicable`), never a missing row or a guess. The drains are always three rows; an app of
   another user or profile keeps its share and loses its name. No UID and no package name goes
   out; `subject` is null in version 1 and kept for a later one. Nothing is read until a
   caller asks; the night and the drains are then kept until the next heavy snapshot and the level
   and rates for thirty seconds, so a caller that polls costs one read in that time. Projection,
   selection and sort order are ignored; nothing can be written; no URI permission is granted; a
   caller without the permission gets no type for any URI. The times of a figure's span (the
   unplug, the night) go out with it: they are part of the report the permission names.
   The second opinion's `source`, `reason` and `age_s` columns are left out: the provenance and the
   state say the first two, and the third is `as_of_wall_ms` against the caller's own clock. The
   permission's description and the setting's note both name the app names among what is read.
8. **Calls are rows of the action log itself.** The second opinion proposed a separate bounded
   table (schema version 8). SPEC 12.7 says "written to the action log", the log already holds rows
   with nothing to revert (a force stop, a stats reset), and one list is what the user reads, so
   there is no new table, no migration and no change to the bundle format: `ActionOrigin`
   gains `AUTOMATION` (stored by name), and `ActionType` gains the non-stateful `automation_call`.
   Its target is the call's name and, where the sender shared its identity
   (`BroadcastOptions.setShareIdentityEnabled`), the sender's package; "before" is the argument or
   the value replaced; "after" is the new value, `accepted`, or `refused:` and the reason. Only the
   contract's own words and numbers are stored: a profile name that is not one of the three is
   refused and not written down. A Doze profile a call applies is the Action Center's own row
   with the origin, and beside it the call's row, which is where the caller is named. A call's row
   is written before its change and taken back if the change does not happen.
   Growth is bounded instead of pruned: the same refusal again within the hour is not written
   twice, automation adds at most 100 rows in any 24 hours and is refused beyond that
   (`rate_limited`) before it changes anything, and more than ten calls in a minute are refused
   before anything is read. So two things SPEC 12.7's sentence covers are not rows: a repeated
   refusal and a call refused for its rate. Storage and self-drain rank above the functional spec
   (SPEC 0.2). A call reads the automation rows of the last day and the Doze profile rows it
   needs, never the whole log; the Action Center's own reads of rows in force leave the call rows
   out, and its screen draws the log fifty rows at a time.
9. **Version 1 is pinned.** The names and numbers live in `AutomationContract` (`:core:common`),
   every one prefixed with the application id; `docs/automation-api-v1.json` holds them, and a unit
   test fails when the two differ. A version only gains names; a change of meaning is a `/v2/`
   beside it. `docs/automation-api.md` is the description for people who write automations. The
   Action Center's reasons for refusing a profile are its block names in lower case; a second test
   pins those words, so a rename there is seen as the API change it is.

## Consequences

- A stock automation app cannot hold the permission unless its own manifest names it; until one
  does, the API is reachable by root and by apps built for it (`docs/questions.md`).
- The log can grow by up to 36,500 automation rows a year if an allowed app tries to; they are
  small rows, and the list is paged.
- The call's rate limits are read from the log, so they survive a restart; the ten-a-minute limit
  is in memory and starts again with the process.
- Not built: the second opinion's circuit breaker that switches the setting off after sustained
  refusals. The limits above bound the cost, and a setting the user turned on is not turned off
  behind their back.
- A call from a sender that does not share its identity (root's `am broadcast` among them) is
  carried out and logged without a caller. Refusing such calls would shut out the one caller that
  works today.

## Reviewed

One independent subagent read the finished code against AOSP for ways around the permission, the
consent rule and the limits. It found no way in for an app without the permission and no way to
forge the consent. Fixed from its findings:

- A caller's own text (an unknown profile name of any length) was stored in the log and shown.
  Nothing the caller writes is stored now.
- Every call read the whole action log, and so did each heavy snapshot's drift check once call
  rows existed. Reads are bounded queries now (§8).
- A change ran before its row was written; the row comes first now (§8). The eight-second answer
  could report "failed" for a change still under way; it reports "unverified, timeout" (§3).
- A user's row that something else had since changed still counted as consent. It does not:
  SPEC 13.1 never silently re-applies such a value (§6).
- A bundle of extras built not to unparcel would have ended the process; it ends the call.
- The provider answered `getType` for a caller without the permission; it answers nothing (§7).
- "Accepted" meant three things in the code, this ADR and the public description; it means one
  now (§4), and the call waits for its snapshot.
- The successful Doze call did not record who asked (§8). UIDs are no longer given out (§7).
- The "another app defined the permission first" state cannot arise across signers (§1); its
  screen text is gone.

Left as it is: the Action Center plans the change again when it runs, after the engine checked
the consent against a preview. Only something holding `WRITE_SECURE_SETTINGS` could change the
value in between, and the run reads the value back.

## Not verified yet

On a device: the permission dialog of a custom dangerous permission without a group; the grant
surviving an update; a broadcast and a query from an app that holds the permission, from one that
does not, from root and from the shell; whether a query starts a force-stopped Coulomb; the
six-second wait inside a receiver. `APPLY_DOZE_PROFILE` writes a device setting and is covered by
the same rule as the Action Center's writes: it is not run on any device without the owner's yes.
Everything here is tested against stand-ins on the build server (`docs/questions.md`).
