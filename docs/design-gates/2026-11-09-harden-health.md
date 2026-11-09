# Harden: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `harden`, native, in the same read-only agent run as `clarify`. Inputs:
  PRODUCT.md, DESIGN.md, the cited SPEC sections, ADR-0042–0044, the code and the goldens.

## Checked, nothing to change

- Every section's empty state is in `health-first-week`.
- Without a design capacity, the hero explains and offers "Enter design capacity" on a scale in mAh.
- A null cycle count or date leaves its figure out; a session without cycles or stress has a shorter line.
- A charger removed during rename closes the dialog; a deleted session shows a stand-in.
- Long charger names wrap at 100%, 200% and RTL. Turkish fits the strips. Numbers are locale-formatted.
- A session across midnight is labelled by its start day with its duration.
- The design value is checked again in the repository; the rename is trimmed and capped there.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The disagreement note names a source not in the table; the needs-design note is missing | **Fixed.** See the critique. |
| Major | No low-confidence mark anywhere (SPEC 6.7) | **Fixed.** "Low confidence" under the hero figure and in a session row's line when quality < 0.5 (`harden`). |
| Major | The session's highest temperature could show as its average | **Fixed.** Without a mean it reads "Highest temperature", Measured. With one, "Temperature", average and highest, Derived. |
| Major | The hero breaks at 200% and cannot hold 6 digits | **Fixed.** A FlowRow hero; the hardened fixture is 123,450 mAh (`harden`, `harden-200`, `harden-rtl`). |
| Minor | ± after its number in RTL | **Fixed.** LTR isolate. |
| Minor | Design input: mid-typing error, pastes dropped, silent Done | **Fixed.** See the audit. |
| Minor | Rename paste over 40 dropped | **Fixed.** Cut to 40 with a "23/40" counter. |
| Minor | Future timestamps labelled inconsistently | **Fixed.** A day in the future reads "Today" in both `startedAt` and `lastSeen`. |
| Minor | Long sessions read as same-day clock times | **Fixed.** The end clock and the curve's labels and table add the day when it differs from the start. |
| Nit | Dates never show a year | **Fixed.** `Formats.day` adds the year when it is not this year. |
| Nit | OEM keys break at any character | **Fixed.** `breakable()`. |
| Nit | An uncalibrated session titles its chart "Level and power" | **Fixed.** "Level" with the Modeled mark when there is no power. |

## Result

No critical finding. All four majors are fixed and verified in the goldens.
