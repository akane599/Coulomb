# Adapt: Drain (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3).
- Method: impeccable 4.7.1 `adapt`, native variant. A fresh read-only subagent read Drain and
  Compare across widths, font scales and text direction, from the source and the goldens. Each
  finding was re-checked against the tree. The rail's insets with 3-button navigation in landscape
  were left out on purpose; they need the emulator.
- Dispositions describe the code after the fixes.

**What holds.** The layout switches on the space it has, never on the device. Two columns from 600 dp
with content capped at 1200 dp, wrapping choices and stacked tabs at 200%, mirrored bars, marks and
legend in RTL, 48 dp targets, and a keyboard order of selector, summary, tabs, rows at every width.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | In two columns the selector and the privilege issue sit fixed above both columns; on a landscape phone at 200% they can take all the height. The two-column check ignores text size | **Fixed.** The 280 dp minimum column grows with the font scale, so a landscape phone at 200% falls back to one scrolling list (`drain-apps-landscape-200`). Below 480 dp of height the controls scroll in the summary column (`drain-issue-landscape`). |
| Minor | Plural counts use `%1$d`, so an Arabic system shows Arabic-Indic digits beside Latin figures | **Fixed.** `%1$s` with `formats.integer(n)` in every plural (EN, TR). |
| Minor | In RTL, "Measured, but no app accounts for it" hugs the other side from the captions around it | **Fixed.** The heading is full width. |
| Minor | At 200% kernel names wrap mid-word, and tags cut at two lines lose their distinguishing end | **Fixed.** Mono names break after `.`, `/`, `_` and `:` (`breakable`), and tags wrap fully above 130% text (`tagLines`). |
| Minor | The skeleton and the error state are always one full-width column | **Fixed.** The skeleton takes the same two-column check as the content; the error text is capped at 560 dp. |
| Minor | The range picker always opens on the month grid; no golden in a short window | **Fixed.** Below 480 dp of height it opens for typing (`drain-range-landscape`). |
| Minor | The provenance mark's long-press target is the 16 dp glyph | **No change; verified.** Compose expands a pointer target smaller than 48 dp to 48 dp (checked for Now, `2026-11-08-critique-now.md`), and TalkBack has the "Explain provenance" action. |
| Nit | Medium and landscape goldens use the full window width, though the rail takes about 80 dp | **Fixed.** `drain-apps-medium-rail` renders 620 dp, the width beside the rail. |
| Nit | At 1000 dp the summary column is mostly empty below the reconcile bar | **Open, with a default.** It waits for the list-detail decision (ADR-0036 §23); noted in `docs/questions.md`. |

## Result

No critical finding. The major is fixed and covered by two new goldens. The emulator check of the
rail's insets is still open (`docs/progress.md`).
