# Polish: Now (Compose)

- Date: 2026-11-08, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `polish`, by the same fresh read-only subagent. It checked the goldens
  against DESIGN.md, the lab and SPEC 11, and the fixes were then viewed in the re-recorded goldens.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The charging current is drawn in neutral grey; SPEC 11.4 and DESIGN.md want 45% copper | **Fixed.** The area splits at zero: copper at 45% above, the 14% neutral tint below (ADR-0034 §31; `now-plugged`). The critique's "no change" on the live tint is superseded for charging. |
| Minor | With an issue showing, the shares caption sits above the issue, away from its rows | **Fixed.** Issue, then captions, then rows (ADR-0034 §36; `now-harden-rtl`, `now-partial`). |
| Minor | "Everything else" opens Drain, but the three app rows do nothing | **Open.** App detail is a later P4 screen, and the rows open it once it exists. Listed in `docs/progress.md`. |
| Nit | Uneven caption spacing when plugged in (0 dp, then 8 dp) | **Fixed.** Every caption is 4 dp below the one before it (`now-plugged`). |

## Result

No critical finding. The major is fixed and verified. One minor waits for App detail.

## Gate summary for Now (SPEC 11.12)

| Pass | Result |
|---|---|
| critique | 28/40 before fixes; no open critical or major |
| audit | 14/20 before fixes; no open critical or major |
| harden | four majors fixed |
| clarify | no major |
| animate | no major; motion tests pass |
| adapt | one major fixed |
| optimize | no major |
| polish | one major fixed |

Open minors, each with a named home in `docs/progress.md`:
- the "as of" stale state;
- a spoken form for units;
- row taps to App detail.
