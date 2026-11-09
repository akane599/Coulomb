# Polish: Health (Compose, `:feature:health`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3, the last gate).
- Method: impeccable 4.7.1 `polish`. One fresh read-only agent read DESIGN.md, the earlier Health
  gate records, the sources and both languages' strings. It then went through every golden at full
  size and in crops. The interview step was skipped because the brief answers it.

**What holds.**
- Spacing: one gutter on the 4 dp grid, section gaps, 12 dp heads, rows of 64 dp or taller with
  hairlines.
- Type roles; copper only on the bracket and band; neutral share bars; the selected pane row.
- Provenance agrees between the hero, the cross-check, the strips and the rows.
- The settled terms in both languages.
- Reflow at 130% and 200%, including tables stacked with labelled values.
- The first-week, error and gone states; dark matches light.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | The session's Display figure set "%" at full size in `text-primary`, put the Modeled mark mid-height (it read as a degree sign) and named no source | **Fixed.** `FigureText` with Display and Display unit, as Now sets the level, with the mark on the numeral's baseline. "Battery fuel gauge" sits under it. The sign stays with the digits, or goes before a leading percent sign in Turkish. |
| Minor | The session detail's line has no Estimated mark; the list row has one | **Fixed.** `MarkedText` when the line has cycles or stress. |
| Minor | The trend table had no units, though the critique's axis decision relied on it having them | **Fixed.** Estimate and interval cells use `mAh`, as the scale table does. |
| Minor | The design dialog ran "Now: 5,000 mAh" into the next sentence | **Fixed.** `health_design_source` ends with a full stop in EN and TR. |
| Minor | The dialog's Headline title, `surface` container and caret colour differ from the rest of the app | **Fixed.** Title, `surfaceRaised`, and a copper caret. |
| Minor | "Last 30 days" sat 11 dp high: `SectionTitle` hung any action as if it were a 48 dp button | **Fixed.** `SectionTitle` takes a `caption`, which sits on the title's first baseline. Only actions hang. |
| Minor | The missing-power line replaced content but was set as a note | **Fixed.** `StandIn` (Body), 24 dp above. |
| Nit | "·" started a line in the cycles strip and charger rows | **Fixed.** A no-break space before the dot in `health_made_in` and `health_charger_line`, EN and TR. |
| Nit | "± 70" and "±143 mAh" on one screen | **Fixed.** `health_change_*` use "±" plus a no-break space. |
| Nit | "Low confidence" was capitalised mid-line | **Fixed.** A lower-case inline string in Health. |
| Nit | The fixture's estimates were not rounded to 10 mAh | **Fixed.** Estimates and half-widths rounded to tens. |
| Nit | `SessionError` copied `ErrorState`; `DASH` was defined twice | **Fixed.** The session screens use `ErrorState`, and there is one `DASH`. |

## Result

There are no critical or major findings. With this pass, all eight SPEC 11.12 gates have run on
Health with nothing left open. The device checks still pending are listed in `docs/progress.md`.
