# ADR-0006: DESIGN.md is created in P3, not by `/impeccable init`

- Status: accepted
- Date: 2026-11-06
- Phase: P0

## Context

SPEC 0.1 lists `PRODUCT.md` and `DESIGN.md` as "created by `/impeccable init`" in Phase 0. The installed
`/impeccable` (plugin 4.7.1) defines `init` as capturing product truth in `PRODUCT.md` only; it "never
writes DESIGN.md". DESIGN.md comes from new-work (establishing the visual world) or `document`/`extract`
(recording a built one). SPEC 11.1 says that where the spec and the installed reference disagree, the
installed reference wins unless 11.11 lists the conflict; 11.11 does not list this one. SPEC 11.12 step 4
already plans "Lock tokens with `/impeccable extract` and `/impeccable document` into DESIGN.md" in P3.

## Decision

P0 ships `PRODUCT.md` from `/impeccable init`, answered from SPEC sections 1 and 11.2 as 11.1 directs.
`DESIGN.md` is written in P3 by the design-lab flow (new-work honoring the tokens SPEC 11 pins, then
`extract` and `document`). No hand-written DESIGN.md exists before then, so there is no second authority
for the P3 detector and critique runs to disagree with.

## Consequences

The P0 gate does not include DESIGN.md. SPEC 11's tokens stay the design source until P3 locks them.
