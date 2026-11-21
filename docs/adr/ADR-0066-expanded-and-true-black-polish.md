# ADR-0066: Expanded layouts and true black, P6's polish

- Status: accepted
- Date: 2026-11-21
- Phase: P6 (SPEC 10.10, 11.4, 11.12, 14.3)
- Builds on: ADR-0027 (each of these is a one-line decision), ADR-0034 §15 (Now on wide windows),
  ADR-0064 §1 (the theme setting)

## Context

SPEC 17 lists "true black, DeX and expanded polish" under P6. The adaptive layouts of SPEC 10.10
were built with their screens: the rail, the list-detail panes, two-column Drain, Health and
Compare, the Timeline's side panel, tooltips, the pointer's wheel and hover and focus states. True
black became a user's choice with ADR-0064. What was left is below.

## Decisions

1. **Privileges is in every top app bar.** SPEC 14.3 says "always reachable from the top app bar",
   and the second whole-app critique found it on the four first screens only. A screen below a
   destination now has back, its title and the Privileges button; the Privileges screen has none
   to itself. A detail drawn beside its list leaves the button to the list's bar, so one window
   never shows it twice. The Action Center and Settings stay on the first screens: three more
   buttons beside a wrapped title would crowd an inner bar, and SPEC 14.3 asks only for
   Privileges. Reason: the spec's sentence, with the least added to an inner screen.
2. **Every screen has a true black golden.** Timeline, a night, the wake source page, Compare, the
   Action Center, Settings, Privileges, "Data and export" and onboarding joined the screens that
   had one. Nothing needed changing: the black theme only moves `bg` and the inks that follow it.
3. **The detail screens have a golden at a pane's width.** A night and the wake source page at
   560 dp, which is what the detail pane gets in a 1000 dp window; the Night Report's list and
   Compare at 920 dp, a full window beside the rail. Nothing needed changing here either.

## Not verified yet

On a device or in DeX: a freeform window resized across the width classes, an external display,
the wheel and the keyboard on the charts. An emulator run in a resizable window is queued with
P6's device tests (`docs/questions.md`).
