# Polish: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3, the last gate).
- Method: impeccable 4.7.1 `polish`. One fresh read-only agent read DESIGN.md, the earlier
  Charging gate records, the sources and both languages' strings. It then went through every
  golden at full size, cropped and pixel-measured. The interview step was skipped because the
  brief answers it.

**What holds.**
- Spacing: one gutter, the section gaps, 12 dp heads, the 720 dp column.
- The hero: power is the one copper Display figure, signed in ink while drawing, with
  "Calibrating" before calibration. Estimated marks are on the phase, the charger's share and
  every time.
- Copper only on the numeral, wave, bracket, cone and chosen chip; dark and black match light.
- The chart: no end dot; the projection follows its third-of-axis rule; grid values give way to
  the target marker at 70, 85 and 90%.
- Every SPEC 10.11 state and each charger and phase state has a golden.
- Both tables mark provenance per column or per row.
- The strip reflows at 130% and 200%; Turkish has no suffix after a placeholder.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | At the default 80% target the 100% hairline ran through the bracket's words | **Fixed.** A level grid line breaks around the words' box, 4 dp clear on each side (`page`, `tr`, `ar`). |
| Minor | In RTL, a section title with neither caption nor action followed its text's direction, so "Live readings" sat at the left beside a right-hand "This session" | **Fixed** in `core:ui`'s `SectionTitle`: such a title sits at the layout's start, as the other two branches do. Health's and App detail's Arabic and RTL goldens were re-recorded; nothing else changed. |
| Minor | "2" and "minutes" split across lines (Unbreakable Figure Rule) | **Fixed.** A no-break space in EN and TR. |
| Minor | With both times missing, two near-identical sentences stacked into five lines | **Fixed.** One sentence, `charging_times_need_both` (`new-charger`, `uncalibrated`). |
| Minor | The phase sat 2 dp off the level's baseline | **Fixed.** The level, separator and phase align by baseline. |
| Minor | Before calibration, "52%" sat 3 dp off the baseline of "Calibrating" | **Fixed.** Both align by baseline then; with a power figure the level stays centred beside it. |
| Minor | The Modeled level named no source | **Fixed.** "Battery fuel gauge" in the scale's TalkBack description and in the scale table's Level row. The level stays secondary, so no visible caption. |
| Minor | The error body gave no next step, and TR used the formal "siz" | **Fixed.** "Try again; if it keeps happening, restart the app."; TR in "sen". Now's TR error body still says "deneyin": left for the whole-app copy pass. |
| Minor | While drawing, the times were simply absent | **Fixed.** "No times while the phone draws on the battery." (`drawing`). |
| Nit | The wave's and track's round caps reached past the gutter | **Fixed.** The scale is inset by half its stroke at both ends. |
| Nit | The skeleton started 8 dp low and had no level or charger lines | **Fixed.** Hero-shaped: figure, level line, charger line, then the bar. |
| Nit | The separator was its own wrap item, so "·" could end a line alone | **Fixed.** It trails the level in one item, as the charger line's no-break space does. `slowing-tr-200` shows the longest phase at 200%. `DASH` and `ErrorState` move to `core:ui` in the whole-app cleanup. |

## Result

No critical or major finding. All eight SPEC 11.12 gates have run on Charging with no critical or
major finding left open:

| Gate | Result |
|---|---|
| critique | 28/40; majors fixed |
| audit | 16/20; P1s fixed |
| harden | one major, fixed |
| clarify | minors fixed |
| animate | one major, fixed |
| adapt | one major, fixed |
| optimize | minors fixed |
| polish | minors and nits fixed |

Pending: on the emulator, the 1 Hz rate while resumed and the Now/Charging swap on a faked plug;
on a device, the phase and times on a real charge.
