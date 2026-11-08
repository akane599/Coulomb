# Harden: Drain (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `harden`, native variant. A fresh read-only subagent ran it on the Drain
  and Compare sources and their goldens, after the critique and audit fixes. Each finding was
  re-checked against the tree.
- Dispositions describe the code after the fixes. Decisions are in ADR-0038 §6.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | An estimated period shows one exact rate, and the change against it looks exact | **Fixed.** A range per hour with the Estimated mark and "From the level" in the caption; the change shows only when both periods were measured, else a note (`compare-estimated`). `ComparisonTest.anEstimatedPeriodIsARangeAndGivesNoChange`. |
| Major | A period without S5 reads "0 min on battery" and its lenses say "too short" | **Fixed.** The caption gives the counter's own time ("Measured over 5 h 12 min"); the lenses say S5 was not collected (`compare-no-stats`). |
| Major | TalkBack reads a wake row's app name without its tag, so two rows of one app sound alike | **Fixed.** "Wayline, LocationUpdates: …" (`aTaggedRowReadsItsTag`). |
| Minor | Two rates round alike around a change that shows ("0.6, 0.6, up 0.1") | **Fixed.** Such a row shows its rates one digit finer (`Formats.fineAmount`). |
| Minor | A batterystats group collected for part of a period gives no hint that its rates cover part of it | **Fixed.** A per-period note: rates are per hour of the part collected. |
| Minor | With no wake source in a period, every group repeats "not collected" | **Fixed.** One note per period; the caption, key and groups wait, since rows need both periods (`compare-wakeups-no-stats`). |
| Minor | `today` is fixed when the screen opens, and a restored period may lie outside the 30 days | **Fixed.** The last day is read again with each report; a restored period outside the days kept falls back to its default. |

## Result

No critical finding. The three majors and four minors are fixed and verified in the goldens and
unit tests.
