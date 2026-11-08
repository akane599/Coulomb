# Critique: Now (Compose)

- Date: 2026-11-08, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `critique`, run as a design review by a fresh read-only subagent on the 16
  committed Roborazzi goldens, the composable source, SPEC, DESIGN.md and the lab. The deterministic
  detector does not apply to a native screen.
- Scores describe the screen before the fixes below. Each disposition describes the code after them.
- Decisions are recorded in ADR-0034. Questions are in `docs/questions.md`.

## Score: 28/40 (70%), Good

| # | Heuristic | Score | Key issue |
|---|---|---|---|
| 1 | Visibility of system status | 3 | Six minutes after unplugging, the drains copy contradicts the verdict; the scale is a stub |
| 2 | Match with the real world | 3 | "Deep sleep" and "%/h" have no gloss; "First result after 15 min on" is terse |
| 3 | User control and freedom | 3 | Little to control; table sheet and pull to refresh work |
| 4 | Consistency and standards | 3 | App icons clipped against DESIGN.md; chevron not mirrored in RTL |
| 5 | Error prevention | 3 | "One tap restores them" while the button only opens Privileges |
| 6 | Recognition rather than recall | 2 | Marks are explained only by long-pressing a 16×12 dp glyph; the glossary term is gone |
| 7 | Flexibility and efficiency | 3 | View as table, See all, pull to refresh |
| 8 | Aesthetic and minimalist design | 3 | Strong on compact; expanded is stretched; the collecting chart is mostly empty |
| 9 | Error recovery | 3 | Clear copy, but "Try again" is a quiet text button |
| 10 | Help and documentation | 2 | No glossary on Now; mark meanings are hard to reach |

**Design specificity.** The port keeps the lab's world: the copper bracket and cone on a clock
scale, the Display numeral with the Modeled ring, the hairline timetable strip, ruled rows with
neutral share bars, and the slate light, dark and true-black themes. Nothing slid back to a category
default.

**Strengths.**
- The estimate is never a point.
- The collecting, partial and error states each say what is missing and what to do.
- At 200%, 6-digit mAh values and long names reflow without clipping.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | At 200% the state line drops its Measured mark and the side text doesn't reflow | **Fixed.** The state text takes `weight(1f, fill = false)`, so the mark keeps its place. The side column's minimum width is measured from its own lines, so the column drops under the reading once a line no longer fits (`now-discharging-200`). |
| Major | "Deep sleep" lost its glossary term | **Fixed.** `CoulombTerm`: dotted underline, a tap shows SPEC 19.3's definition, and TalkBack reads it as a button with "Show meaning" (ADR-0034 §18). |
| Major | The long-press that explains a mark has a 16×12 dp target | **No change; verified.** Compose expands any pointer target smaller than the 48 dp minimum to 48 dp (`NodeCoordinator.hitNear` / `HitTestResult.hitInMinimumTouchTarget`, checked in the ui-android 1.12.1 classes), and the TalkBack "Explain provenance" action stays. DESIGN.md fixes the glyph at 12 dp. |
| Major | Collecting: "Per-app drain appears after Coulomb sees the phone unplugged" under "Since unplugged 6 min ago" | **Fixed.** Unplugged: "Per-app drain appears with the first snapshot after the unplug." Plugged in with no window yet: the empty copy. |
| Major | Expanded is one stretched column (880 dp between name and figure) | **Fixed.** Capped at 720 dp, aligned to the start (ADR-0034 §15). Two panes went to `docs/questions.md`. |
| Major | The remainder row is unverified: wrong tile, no denominator caption | **Fixed.** "Everything else" has a hairline-outlined "+" tile and is Derived; the caption names the denominator ("Shares are of the 1,220 mAh measured."). Folding unattributed into it on Now is ADR-0034 §14; the question is in `docs/questions.md`. |
| Minor | English axis labels collide and are dropped | **Fixed.** Hour ticks use the locale's best "h"/"Hm" pattern (ADR-0034 §21). |
| Minor | The collecting chart reads as missing history | **Fixed.** The scale stays hidden until time left exists (ADR-0034 §16). |
| Minor | Prose isn't "pretty": "Low / confidence" splits | **Fixed.** No-break space in `low_confidence` (EN, TR); Paragraph line breaking on Body and Caption, Heading on Title and Headline (ADR-0034 §26). |
| Minor | RTL: "1,000−", "1%>", unmirrored chevron | **Fixed.** Bidi isolates (ADR-0034 §20). Directional icons are auto-mirrored by the icon generator (`tools/design/icons.py`). Verified in `now-harden-rtl`. |
| Minor | The live fill ignores DESIGN.md's 30% and 45% tints | **Partly superseded.** Discharge keeps the lab's `.ink-area`, 14% `text-secondary` (`lab.css`). The polish pass found that charging must use the accent (SPEC 11.4), so the area above zero is now copper at 45% (ADR-0034 §31). |
| Minor | The skeleton doesn't match the layout | **Fixed.** Hero row with the source line, scale, verdict, a 104 dp strip, and 72 dp ruled rows with a 40 dp tile (`now-loading`). |
| Minor | Revoked-access copy overpromises | **Fixed.** "…Privileges restores them in one tap." (EN, TR). |
| Minor | Collecting captions are terse | **Fixed.** SPEC 10.11's wording: "…after 15 min with the screen on", "…after 15 min of screen-off time". |
| Minor | Real app icons are clipped to 12 dp corners | **Fixed.** Real icons are drawn as the system provides them; only stand-in tiles have a shape. |
| Minor | The error action is too quiet | **Fixed.** `CoulombOutlinedButton` (ADR-0034 §23). |
| Nit | Hero "%" is `text-primary` | **Fixed.** `text-secondary`, as DESIGN.md says (ADR-0034 §22). |
| Nit | Straight apostrophes | **Fixed.** ’ in every Now and core/ui string. |
| Nit | Plugged caption wording | **Fixed.** "From the last discharge, which ended at 1:55 PM." |
| Nit | "U" tile for unlabeled UIDs | **Fixed.** "#". |
| Nit | "2.34 A" over a mA axis | **Fixed.** Head and axis both switch at 1 A (ADR-0034 §24). |
| Nit | TR "ekran kapalı sürenin" reads unfinished | **Fixed.** "ekran kapalı sürede". |
| Nit | No digit roll | **Fixed** in the animate pass (ADR-0034 §19). |

**Deliberate, not flagged:**
- 410 mA appears in both the hero and the Live current head.
- No row detail line.
- No scrubbing on Now.
- The interim plugged layout.

## Result

No critical or major finding remains open. The one major kept as is has platform evidence behind it.
