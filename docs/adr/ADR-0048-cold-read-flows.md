# ADR-0048: Cold read flows for Now, Drain and App detail

- Status: accepted (decided under ADR-0027 without a second opinion: an internal data-flow change,
  reverted by restoring two `stateIn`/`shareIn` calls; no schema, format or public API change)
- Date: 2026-11-09
- Phase: P4

## Context

The Charging audit found that a failed database read in a flow shared in the app scope never
reaches a screen: the exception ends the sharing coroutine in `AppScope`, whose `SupervisorJob` has
no handler, so the process ends, and a "Try again" could not restart it anyway. Charging moved to a
cold flow (ADR-0046 gate amendment 8). `BatteryRepository.reading`, `DrainRepository`'s inputs and
`sinceUnplugged` still had the shared pattern.

## Decision

Those three flows are cold, one read per collector, as Charging's is. Each collection of
`reading` has its own level-line cache. A failed read reaches the model's `catch`, which shows the
error state; "Try again" collects afresh.

## Consequences

- Each screen's model keeps the flow over a rotation (`WhileSubscribed(5 s)`), as before.
- Now and Drain no longer share one set of Room observers. Only one screen collects at a time, and
  the drain inputs change only after a heavy snapshot, so the extra cost is a few small queries an
  hour while a screen is open. Compare reads its two windows from two sets of observers.
- A pull to refresh on Now waits for the next emission of its own collection, the read after the
  snapshot.
