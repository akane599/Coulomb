# ADR-0063: Decisions of the whole-app design gate, run 2

- Status: accepted (replaces ADR-0061 §2)
- Date: 2026-11-21
- Phase: P5 (SPEC 11.12, 17)
- Builds on: ADR-0027 (each of these is a one-line decision), ADR-0028 §3 (wake coverage),
  ADR-0039 §4 (partial wakelock time), ADR-0046 (Charging), ADR-0061

## Context

Run 2 of the whole-app gate found seven things above minor: five in the critique
(`docs/design-gates/2026-11-21-critique-whole-app-run-2.md`) and three in the audit
(`…-audit-whole-app-run-2.md`), one of them in both. The fixes that change what a screen holds or
what a figure is made of are recorded here, with the reason for each.

## Decisions

1. **Charging shows the open insights as Now does.** Its own "Insights" head, two cards with the
   highest severity first, and "Show N more". This replaces ADR-0061 §2, which kept Watch and Info
   insights on Now: while plugged in Now cannot be reached (ADR-0046 §1), so an insight the digest
   had just named could not be read until the phone was unplugged. Reason: ADR-0046 §1 stays, and
   nothing the app says it has is out of reach.
2. **The night page says what was collected before it says what was recorded.** "What kept it
   awake" reads S5's wake coverage as Drain does (ADR-0028 §3). Nothing collected: one line says
   so, and the section carries no Measured mark. Part collected: the counts are called lower
   bounds, and an empty group reads "None recorded in the part that was collected." Reason: a
   missing record is a zero only where its source covered the whole stretch.
3. **A notification names what the phone is doing, not that a cable is in.** The Live Update's
   title follows Charging's phase (holding, full, drawing from the battery); the power is printed
   only while charging and when it rounds to a watt. The Monitor line follows the battery's status,
   as the widget and the tile do. Reason: "Charging · 80% · 0 W" beside a screen that says
   "Holding" is two answers to one question.
4. **An app's awake time is its own total, never a sum of its rows.** The story's "It kept the
   phone awake for…" takes `partial_wakelock_ms`, batterystats' per-app time with the overlaps
   between tags removed (the `awl` line), in App detail and in the night story. Without that total
   there is no sentence. Reason: ADR-0039 §4 already says no screen adds the rows; the story did.
5. **The Night Report's list is on its stack once.** Asking for the list while a night is open on
   it returns to the list. Reason: Back showed the same list twice.

6. **The Timeline's current panel grows with the text.** It is at least three lines of its value
   labels tall, and each end value is centred on the line it names; stacked, the panel's title
   moves up by the half line the top value needs. Reason: at 200% both values were pushed inside a
   56 dp panel, where the lower one sat on the zero line and read as its label.
7. **A lens and its table share one total.** Drain's component table adds the "Not broken down"
   energy to its total and lists it as its last row, as the lens does. Reason: one component had
   32% in the lens and 34% in the table of the same screen.

## Consequences

- `DrainReport.heldMs` and `AppCounters.partialWakelockMs` carry the per-app total to the screens.
- Charging's golden with insights has a head; `charging-insights-more` is new.
- `night-not-collected` and `night-partly-collected` are new goldens, and `drain-components-s4-table`.
- Timeline goldens at 130% and above are a little taller.

## Reverting

Each decision is one small change: the section in `ChargingScreen`, the coverage branch in
`NightScreen`, the title and line in `SurfaceNotifier` and `SurfaceObserver`, two lines in the
story builders, one branch of `AppNavigator.show`, the panel's height in `TimelineChart` and the
total in `DrainTable`.
