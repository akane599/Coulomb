# ADR-0068: Closing the parity matrix and the sources still unread

- Status: accepted
- Date: 2026-11-21
- Phase: P6 (SPEC 2, 5, 7.6, 18)
- Builds on: ADR-0027 (one-line decisions), ADR-0015 (retention keeps referenced snapshots),
  ADR-0021 (30-day consecutive windows), ADR-0036 (Drain spans), ADR-0053 (phone tests last)

## Context

The SPEC 18 audit found parity rows without a screen (custom references, searchable session
history, alarms per hour, Doze maintenance windows, signal strength, CPU and radio time, syncs,
longest hold, abandoned jobs, power rails, live wakelocks) and section 5 sources that were probed
and parsed but never read by a screen (S10, S12, S13, S16, S19, S24) or not used at all (S14 in
part, S15, S17, S22). SPEC 18 asks that every source be "implemented or marked unsupported by probe,
with reason". The phone is not available for tests (ADR-0053), so everything here was built and
tested on stored data, fixtures and Robolectric.

## Decisions

1. **Named references are a span, not a new table.** `DrainSpan.Between(from, to?)` names two
   heavy snapshots (or one, to the latest). The `reference` table and its USER rows already exist
   (SPEC 8), retention already keeps a referenced snapshot's counters (ADR-0015), and Drain reads
   between them as it reads a custom range: the chain of consecutive windows. Marking takes a heavy
   snapshot first (debounced as a refresh). Reason: no schema change, and the read path is one that
   is already tested. Consequence: once retention has taken the windows after a reference (ADR-0021,
   30 days), the rest of the chain would be a shorter span under the reference's name, so Drain
   reads nothing for it and says why, rather than a total for less time. Deleting the reference
   behind the open span returns Drain to "Since unplugged".
2. **Session history reuses the charge list.** Charges and time on battery are two tabs of one
   screen with one search; a stretch on battery opens Drain on its own span
   (`DrainSpan.Discharge`), as a night does. The search matches every word of the query against
   what the row shows and its date, in the user's locale. The list holds the latest 1,000 of each
   kind, years of use. Reason: the cheapest searchable list that needs no index.
3. **S16 is stored with its type in the name.** `rail_energy.rail` holds `rail:<name>` for a
   measured rail and `consumer:<name>` for an energy consumer (`RailNames`), so no column is added.
   A window's energy is the difference of two readings on the same boot, and only for a span wholly
   on battery, since the counters count from boot and include charging (PowerMonitorReadings.java
   L92-96). Rails are listed as the device names them and **do not replace the modeled component
   shares**: rail names are device specific (PowerMonitor.java L27-37), and a mapping table would
   be a guess. Reason: measurement honesty outranks the SPEC 6.4 wish to replace modeled shares;
   a later per-device mapping, verified on the device, can do that.
4. **S10, S12, S13 and S19 are read on demand and never stored.** `LiveReads` runs one dump through
   the allowlisted services when the user opens its sheet: wakelocks held now (Drain, Wakeups),
   what an app has scheduled now (App detail: jobs and their unmet constraints, stop reasons,
   pending alarms, alarms delivered with and without waking), and every temperature sensor
   (Charging). Reason: zero background cost (SPEC 1.2), tags may hold personal text, and S5 and S7
   already give the history these views would duplicate. SPEC 5's "heavy snapshots" cadence for
   S10 and S13 is not used for that reason.
5. **S24 is read when Now resumes.** The refresh rate and whether brightness is automatic sit under
   the session rates; the brightness level is not shown because its setting maps to the slider
   through a curve the platform does not publish. Storing it per sample would need a schema change
   for context the explanations only need now.
6. **S11** is read where it is used: Doze states and maintenance windows from S7's history (the
   night page counts Doze's pauses; DeviceIdleController reports each window as Doze off), and the
   deviceidle dump by the actions that change the allowlist. **S14**: foreground time comes from
   S4/S5, standby buckets through the worker (R-rules); usage events are not read, since S4/S5
   already give per-app foreground and background time. **S15**: per-UID mobile and Wi-Fi bytes
   come from S5's network lines, which cover every UID without usage access; S15 would duplicate
   them. **S17**: probed; not read, because its counters are the ones S4/S5 already store and a
   cross-check needs both on the same device run, which waits for the phone (ADR-0053).
7. **S22 stays pending real device.** The phone's fixture shows the protection threshold and
   recharge level but no key whose value says whether protection is on; finding it needs the
   setting changed on the phone, which is an owner hard stop. Until then R15 cannot fire, and says
   nothing rather than guess.

## Reviewed

One independent review of the parity commits (`3c0a72f` to `beb6b96`) found one finding above
minor and two minor, all fixed with tests: a reference older than the kept windows was read as the
shorter chain still kept, marked Measured (decision 1); pending alarms were matched by package, so
a clone in another profile added its alarms (now the dump's per-uid count, and no wakeup split or
next alarm when the package is shared); and deleting the open span's reference left the span open.
It found the rail arithmetic, signal bins, alarm rate basis, Doze pauses, held-now attribution,
temperature units and session spans correct.

## Verified

Unit tests for each reader and model (references, rails, held now, scheduled now, temperatures,
session search, Doze pauses), golden screenshots of each new view (English; the Turkish strings are
in place but not captured), and the full `tools/ci/check.sh all`. Not verified: any of this on the
phone or the emulator (both unavailable today); S16 on a device with power monitors (the emulator
has five, queued).
