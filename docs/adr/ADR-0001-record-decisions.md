# ADR-0001: Record decisions as ADRs

- Status: accepted
- Date: 2026-11-06
- Phase: P0

## Context

SPEC 0.1 asks for one Architecture Decision Record per deviation, ambiguity or version substitution,
and SPEC 0.6 asks that costly-to-reverse decisions record both the main agent's view and a
second opinion.

## Decision

ADRs live in `docs/adr/ADR-NNNN-<slug>.md`, numbered in order, never renumbered. Each has Status
(proposed, accepted, superseded by ADR-NNNN), Date, Phase, Context, Options, Decision, Consequences,
and for costly-to-reverse decisions a "Second opinion" section. Code that depends on an ADR cites it
(`ADR-0005`). ADRs that change behaviour the spec describes are listed under "Decisions to review" in
`docs/progress.md`.

## Consequences

Cheap-to-reverse choices inside the spec's options (for example Roborazzi over Compose Preview
Screenshot Testing, both allowed by SPEC 15.3) are noted in `docs/progress.md` instead.
