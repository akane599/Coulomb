# Harden: App detail (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `harden`, native variant. A fresh read-only subagent ran it with
  `clarify` on the App detail sources, strings and goldens after the critique and audit. Each
  finding was re-checked against the tree.
- Decisions are in ADR-0041, "Gate decisions".

**Checked, nothing to change:** six-digit mAh and counts at 100% and 200%; a UID with no label; an
app with use but no wake records; a day of 0 against a gap; a trend whose largest day is 0; a missing
or estimated measured total; huge byte counts; Turkish expansion; the plurals.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | RTL: figure columns don't line up with their headers | **Fixed.** End-aligned cell boxes (`harden-rtl`, `ar`). |
| Major | At 200% text, stacked tables lose their column names | **Fixed.** Labelled values (`harden-200`). |
| Minor | A charge inside the window is never mentioned | **Fixed.** Drain's note under the share caption (`harden`). |
| Minor | A partly-off breakdown is not explained | **Fixed.** "Component breakdown was off for part of this window; that part isn’t split by component." |
| Minor | 99.56% rounds to "100%" | **Fixed.** ">99%" below the whole, in `Formats.share`, so Drain gets it too (`ShareFormatTest`; `harden`, `drain-harden-200`, `drain-harden-rtl`). |
| Minor | Fourteen missing days draw an empty plot | **Fixed.** "No records in the last 14 days." replaces the chart and its table button (`appdetail-trend-all-gaps`). |
| Minor | Traffic with one side missing shows one side as the total, with no caption | **Fixed.** "298 MB down; sent not recorded" and the reverse. |
| Minor | RTL App info label hugs the icon | **Fixed.** The label takes the start. |
| Nit | Durations of 100 h or more wrap in the 84 dp column | **Accepted.** Only a custom range of over four days gets there, and the cell wraps without clipping. |
| Nit | No golden shows the long app name in the top bar | **Fixed.** `shell-compact-long-title`. |

## Result

No critical finding. Both majors and every minor are fixed and verified in the goldens and unit
tests.
