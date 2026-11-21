# Whole-app critique, run 1

- Date: 2026-11-21, phase P5 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique` with the Android reference, run as one independent read-only
  review: six slices by screen group, plus the journeys between screens traced in `:app` and one
  table of all 1,357 strings in both languages. About 270 of the 350 goldens were viewed. The
  detector does not apply to a native app. Nothing ran on a device.
- Result: **0 critical, 6 major.** This run does not count towards the two clean runs.

## Heuristics before the fixes

| # | Heuristic | Score (of 4) |
|---|---|---|
| 1 | Visibility of system status | 3 |
| 2 | Match with the real world | 3 |
| 3 | User control and freedom | 3 |
| 4 | Consistency and standards | 2 |
| 5 | Error prevention | 3 |
| 6 | Recognition rather than recall | 2 |
| 7 | Flexibility and efficiency | 3 |
| 8 | Aesthetic and minimalist design | 3 |
| 9 | Error recovery | 3 |
| 10 | Help and documentation | 2 |
| | **Total** | **27 / 40** |

What held across the app: one hero per screen, hairlines instead of cards, copper kept to the
bracket, charging and the chosen value; parts sum and denominators are named; one error and one
collecting pattern; Turkish in "sen" everywhere; the Action Center's preview, log and revert chain.

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | In dark, the Timeline's Earlier, Later and "Choose tracks" icon buttons drew in Material's default black. | **Fixed.** `CoulombIconButton` draws in `text-secondary` wherever it stands (ADR-0061 §1). |
| 2 | Major | Charging's target note contradicted Settings' alarm. | **Fixed** (audit run 1, #1). |
| 3 | Major | An insight notification led nowhere certain: it opened the app where it was; while plugged in, Charging stands in Now's place and had no insight; a digest names up to five and Now shows two with no way to the rest. | **Fixed.** Insight and digest notifications open Now. Charging shows the open insights that ask for action, on the same card. Now offers "Show N more" under its two cards (ADR-0061 §2, §3). |
| 4 | Major | R04's title "Screen-off drain was 1.2 %/h" named no night, two sections under the strip's "Screen off 0.8 %/h"; R03 the same; R04's button opened the list, not the night. | **Fixed.** R03 and R04 name the stretch by when it ended ("…in the stretch that ended Thu 7:10 AM"); R04 opens that stretch. R11 and R13 name the charge and R19 its window in the body (ADR-0061 §4). |
| 5 | Major | With Shizuku stopped the notification said "Coulomb lost its access … reads basic data only", also with every durable permission in place; the screen it opens says granted permissions keep working. | **Fixed.** That case is titled "Shizuku is stopped" and says what waits and that granted permissions keep working. "Lost its access" stays for revoked grants and a denied authorization. |
| 6 | Major | The monitor notification and "Back" were English on a Turkish phone. | **Fixed** (audit run 1, #2). |
| 7 | Minor | Now's app rows were inert; the verdict's app name is plain text. | **Fixed** for the rows. **Open, minor:** the verdict's name. |
| 8 | Minor | In Drain's Wakeups lens, the night page and the wake page, rows that name an app or a night do nothing beside rows that open a page. | **Open, minor.** |
| 9 | Minor | A link to another destination lands on whatever that destination last showed; the Drains widget and an insight's App detail keep Drain's last window. | **Open, minor.** |
| 10 | Minor | The Night Report's only standing entry is the section under the Timeline's chart. | **Open, minor** (ADR-0056 §1 stands). |
| 11 | Minor | "Last night" is chosen by two rules (Drain, widget) and the item has three names. | **Open, minor.** |
| 12 | Minor | Snooze and Dismiss have no undo. | **Open, minor** (audit run 1, #4). |
| 13 | Minor | The evidence sheet labelled its value "Measured", the provenance word, for every rule; a day-long period had one day. | **Fixed.** The row is "Found"; both ends name their day. |
| 14 | Minor | In App detail a Watch or Info insight is one plain sentence; the story's awake time is not in any table. | **Open, minor.** |
| 15 | Minor | The Level widget, the tile and Now carry no age when the collector is not running. | **Open, minor.** How often that happens on One UI is pending real device. |
| 16 | Minor | On the charger the Top drains widget shows the last discharge under "since unplugged". | **Open, minor.** |
| 17 | Minor | The Health widget marks and names Coulomb's cycle count differently from Health. | **Open, minor.** |
| 18 | Minor | Widget and tile say "On battery" where Now says "Discharging". | **Open, minor.** |
| 19 | Minor | With the Action Center off, an insight's "See actions" leads away and loses the app. | **Open, minor.** |
| 20 | Minor | The grant row says "asked only when you turn on the Action Center" while it is on. | **Open, minor** (audit run 1, #7). |
| 21 | Minor | Result lines read only "Done."; "Revert" looks live with no helper. | **Open, minor.** |
| 22 | Minor | Doze keys and package names are not Mono in previews; goldens missing for the dialogs. | **Open, minor** (audit run 1, #27, #28). |
| 23 | Minor | Settings' save failure was a quiet caption. | **Fixed** (audit run 1, #8). |
| 24 | Minor | SPEC 14.4 rows are absent with no named phase. | **Fixed** in `docs/progress.md`: each missing row is listed under P6. |
| 25 | Minor | A "Limited" capability with no fix shows its raw probe string. | **Open, minor.** |
| 26 | Minor | Partial states without a link. | **Open, minor** (audit run 1, #5). |
| 27 | Minor | No glossary terms on the night and wake pages; "Deep Doze 42 min" reads as time spent. | **Open, minor.** |
| 28 | Minor | Timeline at 200%; the table's missing series; "No history at this time" over "Reading this range…". | **Open, minor.** |
| 29 | Minor | A level-estimated total prints one exact Display figure above "rough". | **Open, minor.** |
| 30 | Minor | "Holding at the target." read as if Coulomb stops the charge; the charger line stays beside a 0.3 W hero. | **Fixed** for the sentence: "Holding at 80%." **Open, minor:** the charger line. |
| 31 | Minor | After granting over ADB, onboarding's step still shows the problem title. | **Open, minor.** |
| 32 | Minor | The provenance key is taught once, in onboarding. | **Open, minor.** |
| 33 | Minor | Top-bar icons had no tooltip; the rail at 200%; the monitor notification had no tap target. | **Fixed** for the tooltips and the notification, which opens Coulomb. **Open, minor:** the rail (audit run 1, #18). |
| 34 | Minor | Turkish terms differ by module. | **Fixed** for the wakelock, wakeup-reason, insight, level, breakdown and Derived words. **Open, minor:** "aralık" for window, range and stretch. |
| 35 | Minor | Right to left, the night head's date sits opposite its figure. | **Open, minor.** |
| 36 | Nit | One setting, two labels (Settings, Drain). | **Fixed.** Drain's labels in both. |
| 37 | Nit | Copy drift between screens. | **Fixed:** "Nothing was lost" in Charging's error, the night caption. **Open, nit:** "first snapshot" and "first full reading", "Times" and "Count", the wake page's bar title. |
| 38 | Nit | Turkish: "Gece raporu", "şarj aleti", "Yetkileri aç". | **Fixed.** **Open, nit:** "Cihaz" in one story sentence. |
| 39 | Nit | Marks on empty groups; fixtures that show states the code prevents; an empty Timeline's axis. | **Open, nit.** |
| 40 | Nit | Charging's wave stays copper while the phone draws from the battery. | **Open, nit.** |

Recorded decisions re-read and kept: ADR-0046 §1 (Charging stands in Now's place) and ADR-0056 §1
(the Night Report lives under Timeline).

## Not verifiable from source and goldens

Motion, the Now and Charging crossfade, predictive back, haptics, 60 fps panning; TalkBack order and
labels; notification taps, the Live Update and the Now Bar, the launcher and Quick Settings on
One UI; how often the collector is down; every Action Center write; true black and expanded layouts
(P6).
