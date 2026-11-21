# ADR-0057: Notifications follow the collector's snapshots

- Status: accepted
- Date: 2026-11-21
- Phase: P5 (SPEC 12.1 to 12.3, 9.4)
- Builds on: ADR-0046 (the charge in progress), ADR-0054 (insight rules)

## Context

SPEC 12 lists five channels, the Charging Live Update, two alarms and a daily insights digest, and
says none of them may add wakeups. It does not say where the code lives (SPEC 15.2 has no module
for it), what drives an update, when "09:00" posts without an alarm, or how often the same thing
may be told.

## Decisions

1. **Where it lives.** In `:app`, package `surface`: the notifications need the read models
   (`:core:data`), the insight store, the settings and the words of `:core:ui`, and only `:app` may
   depend on all of them. The decisions are plain classes with unit tests (`SurfaceRules.kt`); one
   class posts (`SurfaceNotifier`) and one decides when (`SurfaceObserver`).
2. **What drives it.** `SurfaceObserver` is a `SnapshotObserver`: it runs on the collector's
   consumer after a snapshot is stored, which is each level change and each screen or plug event
   (SPEC 7.2). It reads stored state only. No alarm, job, timer or sampler is added. It also runs
   the insight engine after a heavy snapshot, which the app's insights binding did before, so the
   notifications see what that run found.
3. **Monitor line.** The collector's own notification (channel `collector`, id 1) is posted again
   with one line: the screen-off rate and its hours when there are 15 minutes of it, else the
   screen-on rate, else nothing; on the charger, the level. `:core:collect` builds the
   notification; the app supplies the line, because the collector may not depend on read models.
4. **Live Update pace.** Shown with the first snapshot of a charge, then on a level change no
   sooner than 30 s after the last update. Snapshots come with level changes, so nothing ticks
   in between. The request to be promoted is always made; the system and the user decide
   (AOSP `Notification.hasPromotableCharacteristics`, cited in the code).
   `canPostPromotedNotifications()` is for Settings to say whether Live Updates are allowed, not
   for building a different notification: unpromoted, it is the same ongoing notification.
5. **The bar.** From the charge's start level to 100%. A target at or below the start level, or at
   100%, leaves one segment and no point.
6. **Whole watts.** The chip, the title and the summary write power in whole watts, as SPEC 12.2's
   examples do ("62% · 18 W"); the chip has room for little. Screens keep 0.1 W (SPEC 6.7).
7. **The summary.** On the first snapshot after unplugging: the gain to the level at unplugging,
   the time since the charge began and the mean of the readings into the battery. It replaces the
   Live Update and times out after 10 s. A process that starts unplugged withdraws a Live Update
   an earlier process may have left, once.
8. **Charge target alarm.** Fires when the level is seen to cross the target on the charger: below
   it at one snapshot, at or above it at the next, both plugged. A level already there when
   Coulomb first looks, or reached off the charger, does not fire. The target is the charge target
   of SPEC 14.4; the alarm has its own switch (off).
9. **Temperature alarm.** At or above the threshold, then silent for 30 minutes. An Act-level
   thermal insight (R11, R12) posted in the same pass starts that rest, so one heat is told once.
   Those insights post on Alerts; SPEC 9.4 says they do not wait for the digest.
10. **The digest's time.** From the set minute of the local day (09:00), the first snapshot posts
    the digest. A phone asleep at 09:00 gets it when it next wakes for its own reasons. One a day,
    and only when an insight was found since the last digest; it then names every unseen one.
11. **A lost access is told once.** Stopped manager, revoked grants, denied authorization. The
    posted issue is remembered until access is back, so a process restart does not repeat it. A
    phone without a manager gets no notification: Basic mode is a choice. The tap opens Privileges.
12. **Settings fields.** `charge_alarm`, `temperature_alarm_deci_c`, `insights_digest_off` and
    `insights_digest_minute_plus_one` are added to the settings proto. Each default is the zero
    value, so no migration is needed.
13. **What is remembered.** The day and the newest insight of the last digest, and the access loss
    last told, live in a small preferences file (`surfaces`). They are not settings and not data.

## Consequences

- With the screen off and unplugged nothing runs, so nothing is posted: an alarm or the digest can
  be late by as long as the phone sleeps. That is the price of adding no wakeup.
- While charging with the screen off the Live Update moves with each percent, not every 30 s.
- After a process death the alarms forget the last level; the target alarm then needs a new
  crossing and the temperature alarm may repeat within 30 minutes.

## Not verified yet

- Whether One UI 9 shows the Live Update in the Now Bar (SPEC 12.2). Pending real device.
- The promoted look on the API 37 emulator; the unit test checks the request and the content.

## Reverting

Remove the `surface` package and bind the insight runner as a `SnapshotObserver` again. The four
proto fields can stay unused.

## Gate amendments (2026-11-21)

From the P5 per-screen gates (`docs/design-gates/2026-11-21-gates-action-center-settings.md`).

1. **Settings asks for the notification permission** when an alarm or the digest is turned on
   while Coulomb may not post. While one is on and notifications are off, the Alerts section says
   so and offers "Allow notifications": the system's prompt, or the app's notification page after
   a refusal. The switch still stores its value; the screen reads the permission on every return.
2. **The temperature alarm's note says it is checked while the phone is awake**, which is what
   "Consequences" above records.
3. **Thresholds and digest times are presets**, where SPEC 14.4 gives a range and "Time": five
   temperatures and six times, and a stored value outside them joins its group.
