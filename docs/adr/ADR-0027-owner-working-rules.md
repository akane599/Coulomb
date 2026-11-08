# ADR-0027: Optional work waits; second opinions only for big decisions

- Status: accepted (edits SPEC 0.6)
- Date: 2026-11-08
- Phase: P4

## Context

On 2026-11-08 the owner set two working rules, effective immediately.

## Decisions

### 1. Optional work goes to the backlog

- Anything `SPEC.md` calls "optional" or "if time allows" is not built for now.
- Each such item gets one line in `BACKLOG.md`: what it is, and where the spec has it.
- When the mandatory phases and the section 18 checklist are done, the owner sees the backlog and picks
  what gets built.
- The first scan found four items: the Wear OS tile, the wallpaper colour mode, the root tier beyond
  Shizuku running as root, and validation runs longer than one night.
- "T3 is optional" (SPEC 4.1) says the user does not need root. The backlog reads it as deferrable work
  too: root cannot be tested on the owner's phone, and no core screen may require it. Tier detection
  and capability states stay, because SPEC 1.2 requires them.

### 2. Second opinions only for big decisions

- A second-opinion subagent is consulted only when a decision is hard or expensive to reverse:
  - architecture;
  - a data format;
  - the database schema;
  - an external or public API;
  - deleting or reverting something.
- Every other choice the agent makes itself, gives a one-line reason in an ADR, and moves on.
- This replaces SPEC 0.6's earlier list: module boundaries, the schema after P2, a core library, a
  parser strategy and an estimator method.
- SPEC 0.6's independent review of accuracy-critical code stays. That is a gate check on code once it
  is written (parsers, calibration and estimators, attribution diffs), not an opinion on a decision.
  The owner can drop it with one word.

## Consequences

- SPEC 0.6 now has the backlog rule, and the decision ladder now has the owner's list.
- `AGENTS.md` mirrors both.
- Backlog items count in no phase gate.
