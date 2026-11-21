# ADR-0061: Decisions of the whole-app design gate

- Status: accepted
- Date: 2026-11-21
- Phase: P5 (SPEC 11.12, 17)
- Builds on: ADR-0027 (each of these is a one-line decision), ADR-0046 (Charging), ADR-0054 (insight
  cards), ADR-0057 (notifications)

## Context

SPEC 17 ends P5 with `/impeccable audit` and `critique` over the whole app, repeated until two runs
in a row find nothing above minor. Each run is in `docs/design-gates/`. The fixes that change a
shared component, a rule or what a screen holds are recorded here, with the reason for each.

## Decisions

1. **An icon button has its own colour and names itself.** `CoulombIconButton` draws in
   `text-secondary` instead of the ambient content colour, which is black outside a Material surface
   and vanished on the dark ground. A long press or a resting pointer shows its content description.
   Reason: three call sites had to remember to provide a colour and the Timeline's did not.
2. **Charging shows the open insights that ask for action.** While plugged in, Charging stands where
   Now does (ADR-0046 §1), so an Act insight could not be read during the charge it is about (R11).
   At most two, on Now's card, between the times and the readings. Watch and Info insights stay on
   Now. Reason: the cheapest way to keep ADR-0046 §1 and still show what needs doing.
3. **Now starts with two insights and offers the rest.** SPEC 10.2 gives two cards. A digest names up
   to five, and reaching the third meant dismissing one. "Show N more" brings in every open insight
   until Now is left. Reason: the two-card default holds; nothing is hidden behind a destructive tap.
4. **An insight about a stretch or a charge says which.** R03 and R04 name the screen-off stretch by
   the weekday and time it ended; R11 and R13 name the charge by when it began and R19 its window,
   in the body. The weekday form is used whatever today is, because a notification is read later.
   R04 offers "Open that night" before the list. Reason: the same quantity with another value
   stands two sections above the card on Now.
5. **A stopped Shizuku is not a loss of access.** Its notification is titled "Shizuku is stopped" and
   says granted permissions keep working (PRODUCT principle 5). Reason: it contradicted the screen
   it opens and would repeat after every reboot on a phone without root.
6. **Every translatable string exists in both languages, checked.** `tools/ci/translations.py` runs
   in the static stage. Reason: two modules shipped without Turkish and nothing noticed.
7. **Now's app rows open the app's detail under Drain**, on the window the row came from. Reason:
   the detail belongs to Drain's list, so back shows the list the row is in.
8. **The evidence sheet's value row is "Found".** "Measured" is a provenance word and eight rules are
   not measured. Reason: SPEC 6.7 reserves the word.
9. **Insight and digest notifications open Now; the monitor notification opens Coulomb as it was.**
   Reason: an insight is on Now; the monitor line is about no screen in particular.

## Consequences

- Minors left open by a run are listed with their disposition in that run's file; they do not hold
  the gate, and the ones about expanded widths, the rail and a refresh control are P6's.
- A third language needs its `values-<lang>` in every module at once, or the static stage fails.

## Reverting

Each item is one small change: the tint and tooltip in `TopAppBar.kt`, the slot in
`ChargingScreen.kt`, the button in Now's `Insights`, the strings and `InsightCopy` arguments, the
notifier's title, and the check's line in `tools/ci/check.sh`.
