# Whole-app critique, run 2

- Date: 2026-11-21, phase P5 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique` with the Android reference, run as one independent read-only
  review with a fresh brief: six slices by screen group, the shell, navigation, notifications,
  widgets and tile traced in `:app`, and every string in both languages (1,335 English, 1,326
  Turkish). 347 of the 354 goldens were opened. Every finding above minor was opened again in
  source by the reviewer before it was reported. The detector does not apply to a native app.
  Nothing ran on a device.
- Result: **0 critical, 5 major.** This run does not count towards the two clean runs. One of the
  five (1) was made by run 1's own fix.

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
| 10 | Help and documentation | 3 |
| | **Total** | **28 / 40** |

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Major | On Privileges, "Re-run probes" was not drawn once a check time existed: a section title holds a caption or an action, and run 1's fix gave it both. Rows still said "Re-run probes to update it." | **Fixed.** The button stays in the title; the time of the check is a line under it. `privileges-connected-page` shows both. |
| 2 | Major | The night page's "What kept it awake" ignored wake coverage: a Measured mark and "None recorded." under each heading also when nothing had been collected (Basic mode, a failed read), where Drain says of the same report that wakeups were not collected. | **Fixed** (ADR-0063 §2). Nothing collected and nothing recorded: one line, "Wakeups weren’t collected for this stretch.", and no mark. Part collected: a note that the counts are lower bounds, and "None recorded in the part that was collected." New goldens `night-not-collected`, `night-partly-collected`. **Open, minor:** no link to Privileges from that line (critique run 1, #26). |
| 3 | Major | The Monitor notification and the Charging Live Update said "Charging" for every plugged state: "Charging · 80% · 0 W" while holding, "Charging · 52%" while drawing from the battery, against the screen, the widget and the tile. | **Fixed** (ADR-0063 §3). The Live Update's title follows the phase ("Holding at 80%", "Full · 100%", "Plugged in · 52%"), and the power is printed only while charging and from half a watt. The Monitor line follows the battery's status as the widget and the tile do. |
| 4 | Major | While plugged in, Watch and Info insights could be reached nowhere: the digest opens Now, Charging stands in its place and showed only Act insights. Re-raised against ADR-0061 §2. | **Fixed** (ADR-0063 §1, which replaces ADR-0061 §2). Charging has Now's Insights section: its head, two cards by severity, "Show N more". Goldens `charging-insight`, `charging-insights-more`. |
| 5 | Major | "It kept the phone awake for 37 min." was the sum of the app's partial-wakelock rows, which overlap (ADR-0039 §4); the page's own table contradicted it. The night story added per app the same way. Re-raised from run 1, #14. | **Fixed** (ADR-0063 §4). The sentence takes the app's own total, which batterystats keeps with the overlaps removed (`partial_wakelock_ms`, the `awl` line), in App detail and in the night story. No such total, no sentence. |
| 6 | Minor | A battery saver change that ended by itself stays "In force" in the Action Center's log. | **Open, minor.** Whether One UI turns it off on plug-in is pending real device. |
| 7 | Minor | With the temperature alarm off, the note said "No alert for battery temperature." while a heat insight still notifies. | **Fixed.** "No alarm at a set temperature. An insight about heat can still notify." |
| 8 | Minor | A night is named by its end on the widget and in R03/R04, by its start in the list. | **Open, minor.** |
| 9 | Minor | Opening the Night Report from the widget or an insight over an open night stacked the list twice. | **Fixed.** `AppNavigator.show` returns to the list that is already open; covered by a test. |
| 10 | Minor | Opening an app from the wakeup source page replaces that page. | **Open, minor.** |
| 11 | Minor | An open Act insight sits below Now's first viewport with nothing pointing to it. | **Open, minor.** |
| 12 | Minor | "Halcyon used 33% of it" does not name its base when the total is not measured. | **Open, minor.** |
| 13 | Minor | Charging's cards had no head, so a card about another day read as part of this charge. | **Fixed** with #4. |
| 14 | Minor | Drain's component table leaves out "Not broken down" and divides by a smaller total. | **Fixed** (audit run 2, #3, which rated it major; ADR-0063 §7). |
| 15 | Minor | Timeline: the locked line said "only through Shizuku"; a partly read window in Basic mode gives no reason; no first-run line. | **Fixed** for the wording: "only with Shizuku or with permissions granted once over ADB". **Open, minor:** the other two. |
| 16 | Minor | Health's change line printed its margin to 1 mAh beside figures rounded to 10 mAh. | **Fixed.** The margin rounds outward to 10 mAh. |
| 17 | Minor | Three wordings of what the first capacity estimate needs. | **Open, minor.** |
| 18 | Minor | A session row can end in "low confidence" with no estimated figure. | **Open, minor.** |
| 19 | Minor | A Modeled level on the night page and the Timeline's readout does not name its source. | **Open, minor.** |
| 20 | Minor | Privileges: "Changed since this check" for a stopped manager; stored "Available" rows under a helper that is not available; the ask state's grants note. | **Open, minor.** |
| 21 | Minor | Onboarding with Shizuku stopped says granted permissions keep working when none is granted. | **Open, minor.** |
| 22 | Minor | Settings reads only the app-level notification permission, not the channels. | **Open, minor.** |
| 23 | Minor | Action Center: battery saver's bare "Off"; a failed rollback's sentence; "Revert all, then turn off" after a failed revert. | **Open, minor.** The writes have never run on a device (`docs/questions.md`). |
| 24 | Minor | R17's body promises a Doze figure that the Action Center shows only when it is on. | **Open, minor.** |
| 25 | Minor | "Mobile radio" and "Mobile network"; five Turkish pairs for one term each. | **Open, minor.** |
| 26 | Minor | Three error lines give no next step. | **Open, minor.** |
| 27 | Minor | No golden shows App detail with its Actions section. | **Open, minor.** |
| 28 | Nit | Action Center, Privileges and Settings icons are on the four first screens only (SPEC 14.3). | **Open, nit.** |
| 29 | Nit | The Health widget's sample showed figures the estimator never prints. | **Fixed.** 4,710 mAh, ± 70. |
| 30 | Nit | Alerts open the app where it was; the Monitor and the Live Update repeat a line; the Privileges channel's description. | **Fixed** for the description. **Open, nit:** the rest. |
| 31 | Nit | Drain: Compare's printed change against its rates; an empty "Small apps" row toggles; "Alarms" has no term. | **Open, nit.** |
| 32 | Nit | English counts with no singular; "Prizdeyken"; two Turkish strings that drift; "Session" for three things. | **Open, nit.** |
| 33 | Nit | Right to left, "62%+"; one unguarded call in Charging's level scale. | **Open, nit.** |

Recorded decisions re-read: ADR-0046 §1 and ADR-0056 §1 are kept. ADR-0061 §2 is replaced
(finding 4).

## Not verifiable from source and goldens

Motion, the Now and Charging crossfade, predictive back, haptics, 60 fps panning and the 1 Hz
redraw; TalkBack order, labels and live regions; notification taps, the Live Update's promoted look
and the Now Bar; widgets on One UI's launcher and the tile in the panel; how often a night has no
collected wakeups, and how often holds overlap, on a real phone; every Action Center write;
channel-level notification blocks; true black and expanded layouts (P6).
