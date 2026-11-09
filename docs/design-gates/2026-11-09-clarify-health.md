# Clarify: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `clarify`, in the same read-only agent run as `harden`.

## Checked, nothing to change

- The voice is calm and specific; Turkish keeps the informal "sen".
- `health_no_estimate` is SPEC 14's wording; the trend caption matches the code.
- The change lines match the comparison made.
- Design-source notes, error, loading and `page_*` strings match App detail.
- `health_cycles` says "cycles" for both forms on purpose (a decimal value).
- Android and OEM values are Modeled; the cycle count is Measured (SPEC 6.7).

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | "(SPEC 6.4)" in `health_stress_meaning`; "temperature" for peak temperature | **Fixed** in EN and TR. |
| Major | `health_differ` wrong for some source sets | **Fixed.** Split into `health_differ_coulomb` and `health_differ`, as proposed. |
| Major | `health_no_power` false for past sessions | **Fixed.** It now says "too short, or not calibrated yet" for this charge. |
| Major | Wrong provenance marks on wear figures | **Fixed.** See the critique. |
| Minor | `health_trend_summary` miscounts and has no plural | **Fixed.** Plurals for windows, with the first and last mAh. |
| Minor | Turkish "pencere" for capacity windows | **Fixed.** "ölçüm" throughout. |
| Minor | `health_no_values` blames the permission alone | **Fixed** as proposed. |
| Minor | "Wear" vs "Wear stress" | **Fixed.** "Wear stress" / "Aşınma stresi". |
| Minor | Awkward Turkish rename hint | **Fixed** as proposed. |
| Nit | "agree within 0 points" | **Fixed.** `health_agree_same`. |
| Nit | "Baseline" reads like a status | **Fixed.** "Usual power · last seen". |
| Nit | The first-use date loses its source beside the made-in date | **Fixed.** "made in %1$s · %2$s". |

Also from the critique: terms with definitions for depth of discharge and "Counted by Coulomb"
(`health_depth_meaning`, `health_counted_meaning`).

## Result

No critical finding. All four majors are fixed.
