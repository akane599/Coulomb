# Harden: Now (Compose)

- Date: 2026-11-08, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `harden`, native variant. A fresh read-only subagent ran it on the source
  and the goldens after the critique and audit fixes. Each finding was re-checked against the tree.
- Dispositions describe the code after the fixes. Decisions are in ADR-0034.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Clock times have no day: an estimate 31 to 59 h out reads "9:30 PM–1:20 AM", and the axis shows "12 PM" twice | **Fixed.** Weekday once a time leaves today, and the weekday on midnight ticks (ADR-0034 §27). `now-harden-rtl` reads "Mon 9:30 PM–Wed 1:20 AM", with "Mon" on the axis. `DayClockTest` pins it. |
| Major | Section heads sit mid-row in RTL: a Latin title resolves left to right and aligns left | **Fixed.** `CoulombSectionHead` gives the title `wrapContentWidth(Alignment.Start)`, so it sits at the layout's start (`now-harden-rtl`). |
| Major | The deep-sleep share has no threshold and no quality score (SPEC 6.7) | **Fixed.** `DeepSleepShare` carries hours and quality. It waits for 15 min and shows "Low confidence" below 0.5 (ADR-0034 §28). An independent review found that the first version's quality never fell below 0.5, so the window length is now part of the score. Tests pin the 15 min edge and the weighting. |
| Major | At 200% with a six-digit mAh, the name column is narrower than "Application" | **Fixed.** The value moves under the name below 7.5 em and stays at the end (ADR-0034 §29). New golden `now-harden-200`: the long name wraps by word, and no figure clips. |
| Minor | No golden uses a real RTL locale | **Fixed.** `now-discharging-ar`. It exposed mid-word breaks in lines that mixed Arabic-Indic digits with English words. Figures now follow the strings' language, and chart labels never wrap (ADR-0034 §32). |
| Minor | An old reading looks current: nothing says "as of" when the collector stops | **Open.** The stale signal belongs to collector health, which the Privileges screen reads later in P4. Now takes it from there. Listed in `docs/progress.md`. |
| Nit | No golden covers the data-table sheet (from the audit) | **Fixed.** `now-table` and `now-table-200`. |

## Result

No critical finding. The four majors are fixed and verified in the goldens. One minor is open with
a named home.
