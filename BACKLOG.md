# Backlog

Work that `SPEC.md` marks optional. None of it is built until the mandatory phases and the section 18
checklist pass; then the owner picks what gets built (ADR-0027). One line per item: what it is, and
where the spec has it.

- Wear OS tile and complication, with the `:wear` module (SPEC 12.6; 15.2; 17, P6; 19.2).
- Wallpaper colour mode: the Material You seed replaces only the accent (SPEC 11.4).
- Root tier beyond Shizuku running as root: a libsu client for other root managers, wakeup sources from
  sysfs (S9), the `power_supply` sysfs fallback (S23), CPU frequency residency and thermal zones
  (SPEC 4.1 "T3 is optional"; 5; 15.3). Tier detection and capability states stay, because SPEC 1.2
  requires them.
- Validation runs longer than protocol E's one night (SPEC 17, P2 gate).

Read but not listed: SPEC 9.1's "optional action ids" is a field a rule may leave empty, not a task.
