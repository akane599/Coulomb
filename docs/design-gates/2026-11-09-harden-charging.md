# Harden: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `harden`, native, in the same read-only agent run as `clarify`. Inputs:
  PRODUCT.md, DESIGN.md, SPEC 6.1, 6.7, 6.8, 7.3, 10.9 and 10.11, ADR-0046, the code and the goldens.

## Checked, nothing to change

- Before calibration, power and current read "Calibrating" and the charger line says why (`uncalibrated`).
- Learning, started-high, new and known chargers each have a line and a golden.
- A long charger name wraps at 100%, 200% and RTL (`harden`, `harden-200`, `harden-rtl`).
- Times are missing case by case with a reason; at full and while drawing there are none.
- Both tables are snapshots, so rows do not shift at 1 Hz while a sheet is open.
- A later charge never opens on an earlier one's figures: the model returns to Loading once stopped.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | Early in a long charge the projection runs the time axis hours ahead, and the session shrinks to a sliver | **Fixed.** The projection shows only while the session keeps at least a third of the axis (ADR-0046 gate amendment 4). The review proposed half; a third keeps the cone on a typical charge 20 minutes in. `harden` now shows no cone for its 2-hour target. |
| Minor | The curve's power was clamped at zero, so a stretch drawing on the battery read as no power | **Fixed.** The reader keeps power signed. The chart shows only power into the battery, so drawing is a gap. The table and summary use the signed figure (`drawing`). |
| Minor | A level held while a real current leaves read as holding | **Fixed.** The hold limit applies to the current's size either way, so that case is DRAWING (`ChargePhaseTest`). |
| Minor | The holding note said nothing about the target | **Fixed.** At or above it: "Holding at the target." Below it, the note names the charge limit (`holding`, `holding-below-target`). |
| Minor | Table rows from an earlier day read as today's | **Fixed.** Such rows name the weekday; today's keep their seconds. |
| Nit | A lower end of 0 read "0 min" | **Fixed.** "<1 min". |
| Nit | A current that rounds to 0 mA still said "into the battery" | **Fixed.** No direction. |
| Nit | No golden for a right-to-left charger name in a left-to-right page | **Fixed.** `rtl-name`. |
| Nit | "33.8 °C" uses the shared Celsius format | Accepted: one format app-wide. |

## Result

No critical finding. The major is fixed and verified in the goldens.
