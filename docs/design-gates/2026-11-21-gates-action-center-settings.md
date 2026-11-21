# Per-screen gates: Action Center, app actions, Settings' new rows

- Date: 2026-11-21, phase P5 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, `audit`, `harden`, `clarify`, `adapt`, `animate`, `optimize`
  and `polish`, run as one independent read-only review with all eight references open. The detector
  does not apply to a native screen. The reviewer read PRODUCT.md, DESIGN.md, SPEC 10.4, 11, 12, 13
  and 14, ADR-0057, ADR-0060, the 2026-11-09 Settings logs, the sources, both string files and 51
  goldens. One file holds the run; the command of each finding is in its row.
- Surfaces: **A** the Action Center, its previews, its log and an app's actions (`:feature:actions`),
  **B** Settings' alarm, digest and Action Center rows (`:feature:settings`).

## Scores before the fixes

| Gate | A | B |
|---|---|---|
| critique (Nielsen, of 40) | 28 | 28 |
| audit (native dimensions, of 20) | 15 | 17 |

No Critical. Five Major: four on A, one on B.

## Findings and dispositions

| # | Sev | On | Command | Finding | Disposition |
|---|---|---|---|---|---|
| 1 | Major | A | critique, clarify | A profile's fixed text contradicted the phone's own figure ("about 10 min" beside "after 5 min"). | **Fixed.** The text says "at most"; the value shows only when the profile shortens the time; when it does not, the row says what changes and that deep Doze stays where it is. |
| 2 | Major | A | critique, harden | The result of an action or a revert was one line at the top of the page, off screen from the log. | **Fixed.** A revert's result sits under the Log's title, another action's at the top; the line is brought into view, and a result gives the confirm or reject haptic (SPEC 11.6). |
| 3 | Major | A | harden, clarify | Without the helper, the preview handed over a command with no undo and no word that it is not logged. | **Fixed.** The preview says a change run by hand is not in the log and cannot be reverted by Coulomb, and gives the inverse command where it does not depend on a value read from the phone; where it does, it says so. |
| 4 | Major | A, B | clarify | New Turkish used the formal "siz". | **Fixed.** Every string of the feature and Settings' new one rewritten to "sen". |
| 5 | Major | B | critique, harden | The alarms and the digest could be on while Coulomb may not post notifications, and the screen said nothing. | **Fixed.** Turning one on asks for the permission; while one is on and notifications are off, the Alerts section says so with "Allow notifications", which asks again or opens the system's page after a refusal. The state is read again on every return to the screen. |
| 6 | Minor | A | critique | The value column meant "after" in Doze rows and "now" elsewhere. | **Fixed.** State rows read "Now: …"; a profile's value reads "to 30 min". |
| 7 | Minor | A | critique | Rows that open a preview and rows that do nothing look alike. | **Accepted.** A chevron on every live row would put about twelve on one screen; inert rows carry their reason as their detail, and DESIGN.md keeps chevrons for links to another screen. |
| 8 | Minor | A | clarify | Without the helper a profile's row still opened a preview that had no command. | **Fixed.** The two rows are inert there, under one line that says why. |
| 9 | Minor | A | clarify | The grant sentence was the smallest text and unconditional; Privileges does not list the grant. | **Partly fixed.** The sentence is reworded. Privileges showing the grant as missing while the Action Center is on is a change to the Privileges checklist and goes to the whole-app pass. |
| 10 | Minor | A | harden | Log times were a weekday and clock, in a log kept forever. | **Fixed** with `Formats.clock` (a date from the seventh day). |
| 11 | Minor | A | audit | A log row's click target may have no label for TalkBack. | **Open, minor.** From source only; checked with TalkBack in the device pass. |
| 12 | Minor | A | audit, clarify | Two "Copy command" buttons in one sheet. | **Fixed.** "Copy undo command". |
| 13 | Minor | A | harden | A long command broke inside a number or a key. | **Fixed.** The block shows break points after commas and spaces and copies the raw string; the text is no longer selectable, since a selection would carry the break marks. |
| 14 | Minor | A | audit | Doze keys and package names outside Mono. | **Accepted for now.** Listed for the whole-app pass with the chooser's wrapping (16). |
| 15 | Minor | A | critique | An unchosen package has no container on a sheet. | **Accepted for now**, with 14. |
| 16 | Minor | A | harden | The package chooser's label cannot wrap. | **Accepted for now**, with 14: shared-UID apps with long package names at 200% text. |
| 17 | Minor | A | harden | A preview stayed in the model after the sheet was swiped away. | **Fixed.** Dismissing the sheet clears it. |
| 18 | Minor | A | critique | No progress while a command runs. | **Fixed.** "Running…" above the disabled buttons. |
| 19 | Minor | A | animate | No confirm or reject haptic on results. | **Fixed** with 2. |
| 20 | Minor | A | optimize | The log is composed eagerly, without keys or a limit. | **Accepted** (ADR-0060 amendment): rows are written only by the user's own actions, a few a week at most. |
| 21 | Minor | A | critique | "Not offered" sat above the Log; "Apps" offered no way on. | **Fixed.** "Not offered" follows the Log; "Apps" has "Open Drain". |
| 22 | Minor | A | critique | Risk is plain text; the first confirmation for the irreversible action looks like any other. | **Accepted.** The irreversible action has a second confirmation that says so; a severity chip is the insight vocabulary and would read as a finding. |
| 23 | Minor | A | adapt | One 720 dp column at 1000 dp. | **Open, minor.** Expanded layouts are P6's (SPEC 17, "DeX and expanded polish"). |
| 24 | Minor | A | harden | No true-black, 130%, medium or RTL golden; none of the dialogs. | **Partly fixed.** New goldens: the busy preview and the no-helper preview with and without an inverse. True black arrives with the Theme setting in P6; the rest are in the whole-app pass. |
| 25 | Minor | B | critique | The Action Center row opened a screen but looked like a value. | **Fixed.** The row states the value; "Open the Action Center ›" under it is the link. |
| 26 | Minor | B | critique | Three notification settings, three patterns, two heading levels. | **Fixed.** One "Alerts" section holds the charge alarm and the temperature alarm; the digest stays under Insights. |
| 27 | Minor | B | clarify | Notes contradicted the digest off and the temperature "Off". | **Fixed.** The note follows the state. |
| 28 | Minor | B | clarify | The temperature note promised an alert the sleeping phone cannot give (ADR-0057). | **Fixed.** "Checked while the phone is awake." |
| 29 | Nit | B | critique | Presets where SPEC 14.4 gives ranges. | **Accepted**, recorded in ADR-0057's amendment. |
| 30 | Nit | A, B | clarify | Two messages for one failure, one asserting a cause. | **Fixed.** "Couldn’t save that change. Try again." |
| 31 | Nit | A | clarify | "Return to stock first." on the applied profile's own row; Turkish "uygulanıyor". | **Fixed.** "The Balanced profile is in force." |
| 32 | Nit | A | clarify | "Revert it from the log" shown inside the log. | **Fixed.** "Tap Revert in the log, or run this command." |
| 33 | Nit | A | audit | A raw 28 dp radius. | **Fixed.** `CoulombRadius.Large`. |
| 34 | Nit | B | animate, harden | The digest's times appear and vanish; missing goldens. | **Partly fixed.** Goldens for notifications off, light and Turkish dark. The times still appear with the switch. |

## Result

All five Majors are fixed. Of 23 minors, 14 are fixed, two partly, five accepted with the reason above
and two stay open as minor (TalkBack label of a log row; the expanded layout, which is P6's).
Goldens re-recorded: 23 in `:feature:actions` (2 new), 10 in `:feature:settings` (2 new).

Nothing here was run on a device: the Action Center's writes wait for the owner's yes
(`docs/questions.md`), so sheets, dialogs, haptics and TalkBack are judged from source and goldens.
