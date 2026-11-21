# Whole-app critique, run 3

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, Assessment A (Nielsen's ten heuristics), run as one
  independent read-only review with a fresh brief. It opened about 83 of 406 goldens (every new
  one, every true black and pane golden, 20 Turkish ones), diffed the 326 English strings added
  since run 2 against Turkish by script, traced the journeys in the navigation code and traced Lite
  mode through the collector, the repositories, the widget and the tile. Every finding above minor
  was reopened at `110769e`. Nothing ran on a device.
- Result: **1 critical, 4 major.** This run does not count towards the two clean runs.

## Scores before the fixes

27 / 40 (run 2: 28). Visibility of system status, consistency, and recognition scored 2; the
others 3.

## Findings and dispositions

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Critical | Lite mode wrote no battery sample, so Now, Charging, the Level widget and the tile showed the last Full-mode reading as current, with no age; after "Delete all data" in Lite, Now never left its skeleton. Re-raises run 1 #15, which Lite makes certain. | **Fixed** (ADR-0069 §1, §2). Every snapshot stores its own reading as a sample. A reading older than 15 minutes says so: Now and Charging print "Last reading at …", Now's live current is replaced by a line saying there is none, the tile's detail and the Level widget's state line carry the time, and in Lite the Level widget always does. |
| 2 | Major | In Lite, Settings showed both alarms on while its Monitoring note said they do not ring; Charging said "The alarm at the target is on". | **Fixed** (ADR-0069 §3). Lite now skips both alarms and the Live Update in code; Alerts opens with a note that Lite does not ring them and keeps the choices for Full; Charging says the alarm is on but Lite does not ring it. |
| 3 | Major | Session history marked a discharge's level change Measured; the charges tab, Now and SPEC 6.7 mark the fuel-gauge level Modeled. | **Fixed**. Modeled, and the model's KDoc corrected. |
| 4 | Major | One Measured mark and one caption covered both the rails and the energy consumers, which may be modeled. | **Fixed** (ADR-0069 §4). Consumers have their own head, marked Modeled, with a caption that says why; the rails' caption speaks of rails only. |
| 5 | Major | After `e6a0206` the references sheet still promised that an old span "shows only what is kept"; Drain's empty state gave an either-or reason; App detail said no snapshot came after the reference. | **Fixed**. The sheet says an older reference can no longer be read; Drain and App detail each give one reason per case (none yet, or older than the 30 kept days). |
| 6 | Minor | Turkish failure lines named "Ayrıcalıklar"; the screen is "Yetkiler". | **Fixed**. |
| 7 | Minor | The three on-demand buttons show at every tier and can only fail without access. | Open, minor. Folded into the recorded "partial lines without a link" class (audit run 1 #5). |
| 8 | Minor | Turkish "Seyrek" and "Nadir" for one bucket; three words for snapshot. | Bucket **fixed** ("Nadir"); the new strings use "anlık kayıt". The older "anlık görüntü" and "ölçüm" lines stay open, minor. |
| 9 | Minor | App detail's syncs table and total come from two sources with no note. | Open, minor. |
| 10 | Minor | "0.3 an hour" from one alarm. | Open, minor. |
| 11 | Minor | A discharge row leaves Health for Drain; one-minute stretches are listed. | Open, minor. |
| 12 | Minor | The history and temperatures are hard to find. | Open, minor. |
| 13 | Minor | A span between two references names neither date. | Open, minor. |
| 14 | Minor | Automation log rows are all "Automation call"; package names not in Mono. | Open, minor (recorded class, run 1 #22). |
| 15 | Minor | Data's notes do not say an imported history is not shown. | Open, minor (ADR-0062 §9, BACKLOG). |
| 16 | Minor | R20's "Open Settings" lands at the top. | Open, minor. |
| 17 | Minor | The new views have English light goldens only. | Partly fixed: a 200% golden of two long reference names (`drain-between-long-references-200`). The rest open, minor. |
| 18 | Nit | Now's screen note sits in the verdict layer. | Kept; Hz now joined to its number. |
| 19 | Nit | "Just now" for an unknown hold time. | **Fixed**: "Not given". |
| 20 | Nit | Copy tidying ("many degrees old", console columns). | "Long out of date" **fixed**; the rest open. |
