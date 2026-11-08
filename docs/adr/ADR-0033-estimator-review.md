# ADR-0033: Outcomes of the estimator and read-model review

- Status: accepted
- Date: 2026-11-08
- Phase: P4

## Context

AGENTS.md requires an independent review of estimators and attribution code before their gate. A
fresh read-only agent reviewed the SPEC 6.8 estimator (`TimeRemaining`), the per-screen-state rates
(`ScreenRates`), the reference windows and app labels (ADR-0032 §3, §5) and the `:core:data` read
models. This ADR records what changed and what stayed, with one line of reason each.

## Decisions

1. **The open session counts.** An open session stores quality 0 until it closes
   (`SessionSummary.open`), and the discharge clip took the lower quality of span and discharge, so
   the current session never reached the estimate. The summary now rates a session's level ends
   itself: 1 when it is open or closed by its own state change, 0.5 when a reboot, history gap or
   monitoring stop cut it short, and it skips a closed session without an end level.
   - R: the stored quality also holds the session's own level resolution, which
     `ScreenRates.combine` applies again over the combined drop. Rating only the ends counts
     resolution once.
2. **The history band widens when few days back it.** It is at least `0.5 / √n` around the median
   for `n` effective windows (Kish's effective sample size of the weights).
   - R: one past day is as uncertain as an untrusted session, and one dominant night no longer
     shrinks the range to the ±10% floor (SPEC 0.2, measurement honesty).
3. **Disagreement widens the blend.** Each side of the blended band moves out by
   `trust × (1 − trust) × |session − history|`, within the two bands' outer edges.
   - R: a linear blend of a session at 7–13 %/h and a history at 2.7–3.3 %/h gave 4.85–8.15 %/h,
     a range that contains neither rate.
4. **A quantile that falls exactly between two values takes their midpoint**, as the median of an
   even count does. R: the lower value made every even-count estimate slightly optimistic.
5. **Time left stays per screen state.** The reviewer proposed projecting hour-of-day profiles of
   the whole discharge forward to empty. Coulomb keeps the current screen state's rate, as SPEC 6.8
   names the context ("screen on vs off, hour of day") and as the reviewed lab shows (Now: 74% at
   12.6 %/h screen-on gives 5 h 50 min).
   - R: changing it would change what the reviewed design says. The alternative is in
     `docs/questions.md` for the owner.
6. **The session strip carries quality and SPEC 6.2's gate.** Each rate carries its quality (SPEC
   6.7's low-confidence mark below 0.5). It shows "Collecting" until it covers 15 minutes. It stays
   level-based, as SPEC 6.2 requires until Coulomb's capacity estimate (SPEC 6.3) exists.
   - R: the 20 mAh alternative needs a charge change that the level rate does not have. Testing
     time alone shows a rate later, never sooner.
7. **Drain's modeled total is the model's device total.** `WindowDrains.modeledMah` is the stored
   window's device total, as the window's own reconciliation uses. The new `deviceMah` is what the
   model charged to no app (idle, cell standby), and `unattributedMah` is measured minus modeled.
   - R: one window had shown two different "modeled" figures, and the model's own non-app power had
     been counted as unattributed.
8. **"System" follows Settings' rule, not the preinstalled flag.** A UID is system when its app id
   is below the application range or it holds the media provider, the calendar provider or System
   UI (`PowerUsageFeatureProviderImpl.isTypeSystem`, cited in `AppNaming`).
   - R: with `FLAG_SYSTEM`, preinstalled updatable apps such as a browser would have hidden behind
     SPEC 10.3's "include system UIDs" toggle. Settings lists them with the other apps.
9. **No TODAY window from yesterday.** After midnight, before the next heavy snapshot moves the TODAY
   reference, a debounced refresh skips the TODAY window instead of building one over yesterday.
10. **A sample from before the open session is not read.** The summary returns nothing (the loading
    state) instead of a negative span.
    - R: the sampler posts a reading the moment a screen becomes visible or the screen or plug state
      changes (`Collector.sample` restarts with no initial delay), so the gap lasts at most one
      reading.

## Left as they are

- **Work-profile UIDs:** whether `getPackagesForUid` resolves them without cross-user permission is
  open. It is in `docs/questions.md`, and an emulator check with a work profile settles it.
- **A just-unplugged session paired with the previous reference window:** the reviewer rated this
  low confidence. `DrainRepository` matches the window to the current SINCE_UNPLUGGED reference
  snapshot, so a stale window is not shown as the new one.
