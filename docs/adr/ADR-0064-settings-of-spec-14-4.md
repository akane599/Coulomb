# ADR-0064: The remaining settings of SPEC 14.4

- Status: accepted
- Date: 2026-11-21
- Phase: P6 (SPEC 6.9, 7.1, 7.3, 7.7, 14.4, 14.5)
- Builds on: ADR-0018 (retention schedule), ADR-0027 (each of these is a one-line decision),
  ADR-0047 §8 (Settings grows with its features), ADR-0062 §15 (Auto Backup)

## Context

SPEC 14.4 lists fifteen settings. Seven were built with their features. This ADR records the
choices made for the rest, where the table's one line leaves something open: Monitoring mode,
Screen-on sampling, Theme, Language, Units, Data retention and the Developer console, with SPEC
6.9's self-cost line and ADR-0062 §15's reset after a restore. The Automation API has its own ADR.

## Decisions

1. **Theme is applied by the activity, before its first frame.** System, Light, Dark and True
   black; `MainActivity` holds the first frame until the stored choice is read, so a chosen theme
   never follows a flash of the system's. The bars' icons follow the theme Coulomb draws. Widgets
   and notifications keep the launcher's and the system's mode: they are drawn on the system's
   surfaces. "Wallpaper color accent" is optional (SPEC 11.4) and stays in `BACKLOG.md`.
   Reason: one place decides the theme, and nothing else needs to know.
2. **Language is Android's per-app language, not a stored setting.** Settings reads and sets it
   with `LocaleManager`; `locales_config.xml` lists English and Turkish, so the same choice shows
   on the system's "App languages" page. Nothing is kept in `settings.pb`, so the two can never
   disagree. Reason: the platform already stores it, applies it to every surface of the app and
   restores it.
3. **Units changes temperatures only.** One process-wide value, set from the stored setting at
   start, feeds every formatter: screens, notifications, widgets and the tile. Everything is
   stored and compared in tenths of a degree Celsius; a threshold prints whole where it is whole
   in the chosen unit ("38 °C", "100.4 °F"), and a rise converts without the 32° offset. Current
   stays in mA and power in W (SPEC 14.4 offers no other unit for them). Reason: a unit is a way
   of printing, and no stored figure or rule may depend on it.
4. **"Screen-on sampling" sets the rate on battery while a Coulomb screen shows.** 2, 5, 10, 30
   or 60 s (the spec's range; five steps cover it). Behind another app the rate stays at 30 s, or
   the user's own when that is slower: a setting that saves battery must not sample faster in
   the background than in front. The charging rates stay fixed, as the table says. Reason: the
   cheaper reading of "5 s visible / 30 s background, 2–60 s" that still does what the user set.
5. **Lite mode is followed by the collector itself.** The stored mode is the one source: on Lite
   the collector stops its service and schedules one WorkManager job every six hours; on Full it
   cancels the job, and starts the service only when the change was made in this process (in
   Settings, with Coulomb in front). A boot or an update starts nothing in Lite; an app open
   takes its snapshot. The boot receiver reads the mode off the main thread and holds its
   broadcast open meanwhile (`goAsync`); the allowance to start a foreground service is a
   temporary allowlist of the app, not of the call. A job left over from a mode change the
   process did not see cancels itself. The row's note says what Lite gives up: live current,
   exact session times and the two alarms. Reason: SPEC 7.1, with the least that can go wrong
   when settings arrive from a backup or a file.
   Amended 2026-11-22 (audit BUG-007): Lite keeps the process when Android lets it, so the engine
   counts a stretch as watched only while the service's receivers stayed attached since the last
   snapshot (`CollectorEngine.watching`), not while the process lived. A Lite snapshot reads the
   history and the level for what changed in between, as after a process start, so a session
   never joins across a plug or screen change Coulomb did not see.
   Amended 2026-11-23 (audit BUG-001): "never joins" held only when a history read covered the
   stretch. Without one (no privileges, a light snapshot, a read that failed, was skipped, was
   not placed or has a gap) nothing shows whether the screen changed, so the screen-on or
   screen-off session ends at that look with a data gap and a new one opens. No reference moves,
   since no change is known. The plug is not treated that way: closing the discharge session at
   every Lite look would restart Now's discharge summary and its rates at each app open. Its
   witnesses are the level (a rise while unplugged) and, new here, the plug type: on a charger
   at both looks but another one means an unplug in between. A fragment between two looks
   still states the screen's state for that stretch; its data-gap end is what marks it as less
   than a watched session.
   What stays unseen without a history read (independent review, 2026-11-23): a charge after
   which the level is back at or below where the last look left it, however large; an unplug and
   replug on the same charger; and a fall of the level between two looks on a charger. A plug
   type that changes without an unplug (a cable added on a wireless pad) ends the charge session
   all the same, and that charge then gives no capacity estimate. A read whose cursor lies after
   the platform's own end of history is whole and empty every time and counts as no read. One
   cost in Full mode: when a process starts before its privileges are known, its first snapshot
   is light and has no history read, so the screen session ends there, although the heavy
   snapshot that follows could have shown that nothing changed. That is rare (an update, a
   crash, a force stop; with durable grants the grants are read within milliseconds) and errs
   toward saying less.
6. **"Longer raw retention" is one switch: 60 days instead of 14.** Raw samples only; every other
   line of SPEC 7.7 stays. A setting can lengthen the time, never shorten it. By
   `SizeBudgetTest`'s rates (5,000 samples a day at 77 bytes) the 46 further days add about 18 MB
   to the 79 MB projected after a year, so the 150 MB target holds; a faster "Screen-on sampling"
   used for hours a day would add more, and that is the user's own choice of both. Reason: one honest step is easier to explain and to bound than a free number.
7. **The developer console asks fixed questions.** Five typed queries (storage, latest snapshots,
   the latest snapshot's sources, capabilities, boots), each a fixed `SELECT`; there is no SQL
   field. Cells are typed (text, count, time, none) and printed in the user's formats; column
   names are the database's own and are not translated. Seven taps on the version unlock it;
   "Hide the console" locks it again. It is neither exported nor imported. Reason: "read-only
   typed queries" (SPEC 14.4), and a free SQL field would be a way to read or lock the database
   that nothing else has.
8. **Coulomb's own use is the figure R20 judges.** Settings shows the share Android's power model
   gives Coulomb's UID between the heavy snapshots of the last day, marked Modeled, with the
   time it counts from and the 0.3% budget; without a known capacity it shows the mAh alone. The
   self-cost insight's button now leads to Settings, where the mode is switched too. Reason: the
   line and the insight must never disagree.
9. **Settings from a backup start without consents.** Auto Backup copies `settings.pb` and not
   the database (SPEC 14.5). At the first start of an install, known by a mark and the database
   both missing from the no-backup directory, `onboarded`, the Action Center and the Automation
   API are switched off before the first screen is drawn; on a fresh install that changes
   nothing. Reason: a grant or a consent belongs to the install it was given on (ADR-0062 §15).

## Consequences

- `CollectorSettings` carries the mode, the visible sampling rate and the raw retention;
  `:core:collect` depends on WorkManager, which the app already ships for its widgets.
- `Formats.celsius` is gone: a temperature is printed with `temperature`, `threshold` or
  `temperatureChange`, each in the user's unit.
- `InlineTable` can keep its stacked layout at any width, for a table of words.

## Not verified yet

On a device: the theme without a flash at a cold start, the per-app language page, Lite mode's job
and the service stopping and starting on a mode change, the start after a boot through `goAsync`,
and the first start after a restore from a backup (`docs/questions.md`).
