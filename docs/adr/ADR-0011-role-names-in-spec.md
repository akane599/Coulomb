# ADR-0011: Role names instead of host names in SPEC

- Status: accepted (amends SPEC 0.5, 3.1 and 16.7)
- Date: 2026-11-06
- Phase: P1 (publish prep)

## Context

The repository will become public. SPEC 0.5, 3.1 and 16.7 named the owner's PC by its private network
host name and described its OS, and named the build server by its host name. `AGENTS.md` already uses
role names, and the real names live in the untracked `AGENTS.local.md` (ADR-0004 spirit, owner
decision in `docs/questions.md`).

## Decision

SPEC uses the role names build server, emulator host and phone. The phone keeps its public model
number (`sm-s938b`), which also names its fixture directory. Host names, addresses, the network
product's commands and local paths stay in `AGENTS.local.md`. The rules themselves do not change.

## Consequences

Agents map roles to machines through `AGENTS.local.md`, which `CLAUDE.md` imports. Reverting means
restoring three passages of SPEC.
