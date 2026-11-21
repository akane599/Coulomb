# Per-screen gates: wakeup source detail, insight cards, widgets

- Date: 2026-11-21, phase P5 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, `audit`, `harden`, `clarify`, `adapt`, `animate`, `optimize`
  and `polish`, run as one independent read-only review with all eight references open. The detector
  does not apply to a native screen. The reviewer read PRODUCT.md, DESIGN.md, the SPEC sections,
  ADR-0054, ADR-0058 and ADR-0059, the sources, both string files and 44 goldens at full size. One
  file holds the run because one run produced it; the command of each finding is in its row.
- Surfaces: **A** the wakeup source detail (`:feature:drain`), **B** insight cards on Now and App
  detail (`:core:ui`), **C** the four widgets (`:widget`).

## Scores before the fixes

| Gate | A | B | C |
|---|---|---|---|
| critique (Nielsen, of 40; C of 28) | 27 | 26 | 20 |
| audit (native dimensions, of 20) | 16 | 16 | 13 |

No Critical. Six Major, two on each surface.

## Findings and dispositions

| # | Sev | On | Command | Finding | Disposition |
|---|---|---|---|---|---|
| 1 | Major | A | clarify | "What you can do" said "If an app above stands out" on pages that list no app. | **Fixed.** The advice is chosen by whether the apps table shows; without one it points to Partial wakelocks and Alarms in Drain. |
| 2 | Major | A | harden | A device-tree name broke inside a word at 200%. | **Fixed.** `breakable` also breaks after `,` and `-`; `IdentifiersTest` has the name. |
| 3 | Major | B | critique | App detail showed two durations in the same words; the card named no period. | **Fixed.** R01, R06 and R09 say "in a day". |
| 4 | Major | B | clarify | The evidence sheet's period named a weekday only, for rules that look back a week or more. | **Fixed.** `Formats.clock` gives the clock today, weekday and clock within a week, date and clock from then on. |
| 5 | Major | C | critique | The Health widget showed an estimate without its range. | **Fixed.** The widget carries the interval and shows the range, or "± N" where the range does not fit, before health and cycles. |
| 6 | Major | C | harden, adapt | No size or text-scale rule for Night, Level and Health; declared minimums below what the layouts need. | **Fixed.** Each widget picks its lines by measured fit; Level's minimum height is 50 dp. Goldens at each minimum and at 200% text. |
| 7 | Minor | A | critique | "In this window" never named the window. | **Fixed.** The section title carries the window's name. |
| 8 | Minor | A | harden | A night collected in part showed its count as whole. | **Fixed.** "At least 41 times". |
| 9 | Minor | A | clarify | Nights are named unlike the Night Report, and open nothing. | **Accepted.** The row is a count per night, not a night's page; opening the night from here needs a route from `:feature:drain` into `:feature:timeline`, which the module graph forbids and the app shell does not offer yet. Listed for the whole-app pass. |
| 10 | Minor | A | clarify | "After 2 the history does not name who took it." | **Fixed** in both languages. |
| 11 | Minor | A | clarify | Two advice lines stated a cause as fact. | **Fixed.** "Usually…". |
| 12 | Minor | A | critique | A Measured mark over a section with no figure. | **Fixed.** No mark without an entry. |
| 13 | Minor | B | critique, animate | Snooze and Dismiss act at once, with no undo and no motion. | **Open, minor.** The app has no snackbar; adding one is a pattern for the whole app and is decided in the whole-app pass. A dismissed insight returns when its rule fires again. |
| 14 | Minor | B | clarify | R17's body named "the Doze page"; its button opens the Action Center. R20 names Lite mode, which has no place yet. | **Fixed** for R17. R20's sentence waits for P6, which builds Lite mode (ADR-0060 §28). |
| 15 | Minor | B | harden | A button whose page cannot open does nothing. | **Accepted.** The only such case is a system settings page a build lacks; the card stays and nothing is lost. |
| 16 | Minor | B | adapt | Card prose ran about 95 characters a line on expanded. | **Fixed.** Title and body are capped at 560 dp. |
| 17 | Minor | B | clarify | "Stops most of it" promised an unmeasured effect; terms without a glossary. | **Fixed** for the promise ("usually lowers it"). The terms stay: the evidence sheet explains the rule, and DESIGN.md keeps glossary marks out of card prose. |
| 18 | Minor | B | clarify | Turkish "Drain’de" for the destination "Tüketim". | **Fixed.** |
| 19 | Minor | B | audit | TalkBack read two identical "More options". | **Fixed.** The button is labelled with the card's title. |
| 20 | Minor | B | harden | R02 put a raw tag in prose and printed empty quotes for an empty tag. | **Fixed.** The tag goes through `breakable`; an empty one uses a body without it. |
| 21 | Minor | C | animate | The first layout was Glance's spinner on Glance's colour. | **Fixed.** A static initial layout on the app's ground, light and dark. |
| 22 | Minor | C | adapt | Top drains left 40 dp empty at its minimum height. | **Fixed.** A tighter gap where it lets one more row in. |
| 23 | Minor | C | clarify | "Maps 31%" had no subject. | **Fixed.** Title "Share of drain since unplugged", with a short form. |
| 24 | Minor | C | clarify | Two Turkish names for one window. | **Fixed.** "Şarjdan çıkalı". |
| 25 | Minor | C | optimize | "ended 7:42 AM" was formatted against the clock at render time. | **Fixed.** The weekday is always there. |
| 26 | Nit | C | critique | "Level and verdict" shows no verdict at 2×1. | **Accepted** (ADR-0058 §5): the verdict shows from the next size up. |
| 27 | Nit | A | harden | A fixture's apps did not add up. | **Fixed.** |
| 28 | Nit | A | clarify | "Times" as a figure heading. | **Fixed.** "Count" / "Sayı"; "82% with the screen off". |
| 29 | Nit | A | animate, optimize | App rows without keys or placement animation. | **Accepted.** The list is at most eight rows and does not reorder while shown. |
| 30 | Nit | B | clarify | Two casings of "Night Report"; straight apostrophes. | **Fixed.** |
| 31 | Nit | B | harden | A wrapped "Why am I seeing this?" sits inside the button's edge at 200%. | **Accepted.** It reads and is not cut. |
| 32 | Nit | B | clarify | Turkish "Kapat" for Dismiss. | **Fixed.** "İçgörüyü kapat". |
| 33 | Nit | C | critique | A Derived mark on an empty heading. | **Fixed.** |

## Result

All six Majors are fixed. Of 19 minors, 15 are fixed, three accepted with the reason above and one
(undo for Snooze and Dismiss) goes to the whole-app pass. Goldens re-recorded: 9 in `:feature:drain`,
5 in `:feature:now`, 3 in `:feature:appdetail`, 25 in `:widget` (15 of them new).

Not checked here, as the review lists: a real launcher (One UI cell sizes are pending real device,
ADR-0058), TalkBack order, true black and 130% text for these surfaces.
