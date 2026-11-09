# ADR-0047: Onboarding, Privileges and a minimal Settings screen

- Status: accepted (decided under ADR-0027 without a second opinion: screens and a store over the
  existing `capability` table; one additive Proto field; no schema or public API change)
- Date: 2026-11-09
- Phase: P4

## Context

SPEC 14.1 asks for at most four onboarding screens ending on a real result; SPEC 14.3 for a
Privileges screen; `docs/questions.md` (2026-11-09) for a minimal Settings screen holding the
settings built so far. The probe suite (SPEC 4.6) exists but runs only from the debug console, and
nothing stores its results, so neither onboarding's result step nor Privileges has data. The lab
designed only the result step (`design-lab/onboarding.html`).

The spec does not say:
- how the app knows onboarding is done;
- what step 2 does with usage access when Shizuku will grant it a step later;
- what happens to the four-step count in basic mode;
- when the probes run besides "first start, after an OS update and on demand";
- which screen hosts Privileges.

## Decision

1. **Onboarded flag.** Proto field 5, `onboarded`. Until it is true the activity shows onboarding
   in place of the shell. "Show my battery" sets it and lands on Now. Clearing the app's data shows
   onboarding again; nothing else does.
2. **Two paths.** "Connect Shizuku": welcome, notifications, Shizuku, result (4 steps).
   "Start in basic mode": welcome, notifications and usage access, result (3 steps, counted as 3).
   Back goes one step back; leaving the app keeps the step (saved state).
3. **Usage access in step 2** only in basic mode, through `Settings.ACTION_USAGE_ACCESS_SETTINGS`
   (Settings.java, android-16.0.0_r4 L578, android-17.0.0_r1 L586). On the Shizuku path the
   bootstrap grants it (SPEC 4.4), so step 2 asks only for notifications and says so; if the
   bootstrap does not run, the result step's checklist offers the Settings route. Fewer taps, and
   no system screen the user would have to leave for nothing.
4. **Step 3** follows `PrivilegeState`: not installed (a compatible fork or Sui, the thedjchi fork
   named first, as the owner chose), stopped (open the manager; resumes on the binder), running
   (allow Coulomb), a 5 s hang or a denial (the SPEC 4.5 copy and the ADB commands, copy-ready).
   The quiet line about Android 17 and forks shows on Android 17 and later. Once the permission is
   granted, the existing automatic bootstrap runs and the step moves on.
5. **Capability store** (`CapabilityStore`, `:core:collect`) over the existing `capability`
   table. Probes run:
   - on the result step, after the bootstrap (or at once in basic mode);
   - at app start when nothing is stored or the stored fingerprint is not this build's (first start,
     OS update; SPEC 4.6);
   - on "Re-run probes".
   One run at a time; a run that fails leaves the stored results as they were.
6. **The result step** says in plain words what Coulomb can see and what this phone does not
   expose, from the stored capabilities (SPEC 14.1's example), then the keep-running row (One UI
   only), the grants checklist and the key to the marks, as in the lab. Before the first probe
   finishes it says it is checking.
7. **Privileges** lives in `:feature:settings` with Settings, both opened from the top app bar.
   It shows the active manager (Shizuku, Sui or a fork, version, uid, shell or root), each grant
   with its state and a repair button (the bootstrap, or the ADB commands without a worker), the
   Capability Matrix grouped by tier, the last probe time, "Re-run probes" and "Export capability
   report" (a Markdown file through the system's save dialog, `CapabilityReport`).
8. **Settings** holds what exists: Show system apps in Drain, Scale attribution to measured,
   Component breakdown and the charge target (50–100%). The rest of SPEC 14.4 joins as each
   setting's feature lands.

## Implementation notes (2026-11-09)

Onboarding as built:
- **One UI** is told by `Build.MANUFACTURER` ("samsung"). The keep-running button opens battery
  settings (`Intent.ACTION_POWER_USAGE_SUMMARY`), or the app's details page where that is missing.
  No public intent opens the Never sleeping list (`docs/questions.md`).
- **Not installed** links to thedjchi's fork and to Sui on GitHub through the browser (no INTERNET
  permission). "Show ADB commands" opens the copy-ready block in place.
- **Step 3 moves on by itself** only at the moment it connects while open, not on a later visit.
  If the automatic bootstrap was skipped (grants verified on an earlier install), the step runs it
  once itself.
- **The summary's list** is joined by the app's own strings, not ICU, whose English adds a serial
  comma the rest of the copy does not use.
- **The ADB block** wraps each command at its spaces, one paragraph per command, so no permission
  name hides past the edge.
- **New shared pieces:** the filled button and the stepper (`:core:designsystem`); the grants
  checklist, the ADB block, the capability summary and the key to the marks (`:core:ui`), for
  Privileges to reuse.
- **App strings in Turkish:** the shell's own strings (destinations, top-bar screens) had none;
  they are added.

## Consequences

- One probe run at first start and after each OS update: a few seconds of work, then nothing.
- Reverting Decision 1 needs no migration: field 5 stays reserved.
- An install that predates this change shows onboarding once after the update.
