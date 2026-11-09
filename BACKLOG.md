# Backlog

Work that `SPEC.md` marks optional. None of it is built until the mandatory phases and the section 18
checklist pass; then the owner picks what gets built (ADR-0027). One line per item: what it is, and
where the spec has it.

- Wear OS tile and complication, with the `:wear` module (SPEC 12.6; 15.2; 17, P6; 19.2).
- Wallpaper colour mode: the Material You seed replaces only the accent (SPEC 11.4).
- A libsu layer for root managers other than Shizuku started as root and Sui (SPEC 15.3; ADR-0028).
- CPU frequency residency and thermal zones read from sysfs as root, which the T3 row listed before
  ADR-0028 (SPEC 2, "CPU time and frequency residency"; ADR-0028 §2).
- Validation runs longer than protocol E's one night (SPEC 17, P2 gate).
- A two-pane Charging layout for expanded windows, readings and session beside the hero (SPEC 10.10's
  adaptive layouts; the Charging adapt gate, ADR-0034 keeps one column).

Read but not listed: SPEC 9.1's "optional action ids" is a field a rule may leave empty, not a task.
