# ADR-0045: Shared page parts live in `:core:ui`

- Status: accepted (decided under ADR-0027 without a second opinion: moving internal composables between modules)
- Date: 2026-11-09
- Phase: P4

## Context

App detail built its own section title, subhead, note, stand-in line, inline table and "View as
table" button. Health (SPEC 10.8) needs the same parts so the two pages read alike. SPEC 15.2 lets
a feature module depend on any `:core:*` module, but not on another feature.

## Decision

The parts move from `:feature:appdetail` to `io.github.akane599.coulomb.core.ui.page` in
`:core:ui`, with their strings (`page_*`). They are public so features can use them. They take
words, not data types, so `:core:ui` stays free of feature logic. A table row can show a
provenance mark (SPEC 6.8) after its last figure. `Modifier.hangVertically` in
`:core:designsystem` becomes public, because the section title uses it to hang its action.

## Consequences

- App detail's goldens are unchanged: the move keeps every measurement.
- A later screen (Charging, Settings) uses the same parts rather than copying them.
- Reverting means copying the files back into each feature that uses them.
