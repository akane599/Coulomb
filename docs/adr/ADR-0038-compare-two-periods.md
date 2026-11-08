# ADR-0038: Compare two periods

- Status: accepted
- Date: 2026-11-09
- Phase: P4

## Context

The More sheet of Drain's window selector holds Compare two periods (SPEC 10.3, ADR-0023 §4). The
entry began as BBS's "Reference vs reference" (SPEC 2: custom references, "compare any two"; SPEC
7.6). In BBS, that shows the counters between two snapshots. ADR-0023 renamed the entry, because the
old name was jargon. The spec says nothing else about it, and the Drain critique of 2026-11-08 found
it missing.

So this ADR settles the open points by SPEC 0.3 rule 5 (more accurate, cheaper in battery, easier to
revert). None is architecture, a data format, a schema or a public API (ADR-0027), so there is no
second opinion. The read-layer change in §3 reads two device rows each window already stores and adds no
column.

## Decisions

1. **Compare puts two periods side by side.** It does not show the counters between two moments.
   - R: the approved name says compare. SPEC 7.6's example, a reference named for the day before an
     app update, asks whether drain changed after the update. That question needs a before and an
     after side by side.
   - R: the counters between two moments are already Drain's own windows. Custom range covers whole
     days. When named references get a screen (SPEC 7.6), a reference can become a window end there.
2. **A period is a range of whole days within the 30 days** (`DrainSpan.Days`). It uses the same
   picker as Custom range. The periods open as yesterday and today; each changes on its own.
   Overlapping periods are allowed.
   - R: the read layer bounds a day range exactly for any of the 30 days (ADR-0036 §2). The
     presets end now and overlap each other, so comparing them says little.
   - R: nights cross midnight. Health's list of screen-off sessions compares them (SPEC 10.8).
3. **Every figure is per hour of the time its counter ran.**
   - The measured drain is divided by the time of the windows it sums (`DrainReport.measuredMs`).
   - Apps and components are divided by the time on battery (`onBatteryMs`), by batterystats' own
     clock in the windows they come from (ADR-0036 §35).
   - Wake records and alarms are divided by their group's time (ADR-0036 §34). Partial and kernel
     wakelocks and alarms use the screen-off time on battery, again batterystats' own clock.
     Wakeup reasons use the time on battery. Root records use the whole period.
   - R: the periods differ in length, time on the charger and screen time, so their totals do not
     compare. Batterystats counts power only on battery, and some wake counters only with the
     screen off. Dividing by the time a counter ran compares like with like. Per wall-clock hour,
     a day spent on the charger would look frugal. A day with more screen time would make every
     wakelock look rarer.
   - R: windows whose start has no plug state count as neither. The measured sum leaves them out
     too (ADR-0036 §3).
   - SPEC 6.2's floor applies to each period. The drain rate shows only when the measured windows
     last at least 15 minutes or hold at least 20 mAh; otherwise the period says it had too little
     time on battery. A period that measured no drain has no rate, never zero or a negative one.
   - Apps and components need 15 minutes on battery in each period, and a wake group 15 minutes of
     its own time. Otherwise no rows show for that list, only a note naming the period and why.
4. **Rows rank by the size of their change.** A row shows both rates and the change in the rate's
   own unit. Only the period totals get a percentage too.
   - R: the comparison asks what changed. A percentage inflates small figures: 0.1 to 0.5 mAh/h is
     +400%. The measured totals are large, so their percentage stays honest.
   - Apps and components use mAh/h. Timed wake groups use minutes held per hour. Groups that
     keep only counts (resume causes, alarms) use counts per hour.
5. **A missing record is zero only where its source covered the period.**
   - A wake group whose source did not cover a period shows that period as "Not collected"
     (ADR-0028 §3). A partly covered one carries the same note as on Drain.
   - When one wake group is read from different sources in the two periods (S5 in one, S9 or S27
     in the other), its rows are not compared, and the group says so.
   - A root group (S9, S27) compares only where its source covered the whole period: its base is
     the whole period, so a part covered would read low by the part missed.
   - Rows show only when both periods count. A note names the period that does not: no records,
     too little time, not collected, collected for part of it.
   - An app missing from a period is zero there. App figures summed over windows are lower bounds,
     and the screen says so as Drain does (ADR-0032 §4).
   - Each window keeps only its largest wake records (ADR-0032), so a wake record missing from a
     period may be a small one, not a zero. The Wakeups caption says a figure may be a little low.
   - The system apps the switch hides share one row ("3 system apps hidden"), so the list still
     accounts for their change.
6. **Every per-hour figure is Derived.** One mark on each list's caption covers its rows (DESIGN.md:
   one source, one mark). The caption names what the arithmetic started from: the measured drain,
   or Android's power model.
   - R: a rate is arithmetic on a Measured, Estimated or Modeled value. Now's rates are Derived
     for the same reason (`StateRate`).
7. **Compare is its own screen below Drain**, opened from the More sheet. It has the two periods at
   the top, the totals, then the lenses Apps · Components · Wakeups.
   - R: a comparison is its own task with its own inputs. Folding it into Drain's window would put
     a second shape of figures behind every lens of the read view.
   - It reads the system-apps switch Drain uses (SPEC 14.4). "Scale to measured" does not apply:
     scaling two periods by their own totals would mix two sources into one rate.
   - It has no privilege block: it reads stored windows only, and Drain already shows the block.
8. **Paired bars.** Each row has two 6 dp bars on one scale with no track: the list's largest rate
   fills the width. The first period's bar is hollow (a 1 dp `text-secondary` outline); the second
   period's is filled, as a share bar is. Each bar's amount sits at its end in a column both share. The caption's key shows both. The bars are decoration: the row says
   both rates in text (SPEC 10.1), and TalkBack reads one sentence per row.
   - R: shares carry no hue (DESIGN.md), and a fill pattern tells the two apart without colour. At
     4 dp, a share bar's height, a hollow bar's outline left almost no inside to read; a track
     would read as a third bar.
   - Rows rank by the change to the tenth they show, so changes too small to print sort by the
     larger rate instead of by rounding noise.

## Consequences

- `ChainTotals` gains `measuredMs`. `DrainReport` gains `onBatteryMs`, `screenOffOnBatteryMs` and
  `measuredMs`. They landed with ADR-0036 §34 and §35, which this ADR's choice of base turned up.
- The comparison is pure (`compare` in `Comparison.kt`). Like an attribution diff, it had an
  independent review before the Drain gate (AGENTS.md). The review found that the bases were
  wall-clock time rather than batterystats' clocks (ADR-0036 §35), that root groups were compared
  when partly covered, that the screen-off share was printed for root records, that a period with
  no measured drain gave a zero or negative rate, that hidden system apps vanished from the list,
  and that the small apps' alarms were dropped. All are fixed above.
- The review also raised that S5 may time a tag's partial wakelocks as pooled time, overlapping
  holds counted once, beside a separate actual time. AOSP confirmed it; the parsers now store the
  actual time (ADR-0039).
- DESIGN.md gains Paired bars under Rows with share bars.
