# ADR-0053: Phone tests longer than five minutes run last

- Status: accepted (owner decision, 2026-11-21)
- Date: 2026-11-21
- Phase: P4 to P6
- Amends: SPEC 17 (phase order); ADR-0019 and ADR-0049 (which gates wait for the phone)

## Context

SPEC 17 lets no phase start while the gate before it is red. Two gates are open, each on one
phone run that takes the phone's screen for a long time:

- P2: protocol A (30 minutes of video at fixed brightness).
- P4: protocol C (a one-hour script).

Everything else in both gates has passed or is recorded as hardware-limited (protocol D). The
owner uses the phone daily, and it was away for service for part of November. Waiting for these
runs would stop all work on P5 and P6.

## Decision

The owner decided on 2026-11-21:

1. **Every phone test that takes more than five minutes moves to the end**, after P6's work:
   protocols A and C now, and P5's protocols E and G and the drift demonstration when they come
   due, where they need a long run.
2. **P5 and P6 start now** and are built and checked on the data already captured from the phone,
   on fixtures and on the emulator.
3. **A gate that waits only for such a test is recorded as "pending real device, deferred"**
   (ADR-0053). It is not passed and it is not skipped. The next phase may start.
4. **Short phone actions stay as they were**: a read-only data pull or an update install takes
   under five minutes and still needs the owner's permission each time (SPEC 0.5).
5. **SPEC 18 is unchanged.** Final acceptance still needs every protocol to pass on the phone or
   to be documented as hardware-limited.

## Consequences

- Features built in P5 and P6 rest on calibration (A) and attribution (C) that the phone has not
  yet confirmed. If a deferred run fails, the fix may reach into those features. Protocols B and E
  and the Macrobenchmark have passed on the phone, and the estimators and attribution diffs had
  their independent reviews, which limits this risk.
- `docs/progress.md` lists the deferred runs in one place, with what each needs from the owner.
- AGENTS.md rule 3 names the exception.
