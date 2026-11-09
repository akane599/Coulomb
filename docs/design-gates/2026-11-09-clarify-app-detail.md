# Clarify: App detail (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `clarify`. The same fresh read-only subagent as `harden` read every
  string in English and Turkish against Drain's settled wording (`2026-11-09-clarify-drain.md`).
- Fine as is: error and loading strings, the share captions and their provenance, the Cached
  definition, the network captions, the gap plurals, the "sen" register.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The trend caption is stale and doesn't say the bars stay unscaled | **Fixed.** "From Android’s model. A stretch that runs past midnight is split between its days." plus "Not scaled to measured." when the header is scaled (`scaled`). |
| Major | "Updated" reads as data freshness | **Fixed.** Marker "App updated" / "Güncelleme"; note "App updated on %1$s." |
| Major | Zero and not collected read alike | **Fixed.** "None among the longest holds Coulomb keeps for this window." against "Not collected for this window."; alarms "Wakeup alarms weren’t collected for this window." |
| Major | Under Jobs, a missing source says "Wakelocks" | **Fixed.** `appdetail_jobs_not_recorded`. |
| Major | "1 runs": `jobs_total` is no plural | **Fixed.** `<plurals>` in EN and TR. |
| Major | The empty state ignores the window and says "snapshot" | **Fixed.** Five per-window strings; "the first full reading after the unplug" (`empty`). |
| Major | TR uses "pencere" where Drain settled on "aralık" | **Fixed.** Every Turkish string. |
| Minor | "No battery use recorded" hides the small-amount floor | **Fixed.** "…Small amounts are counted with Small apps on Drain." |
| Minor | The partial note can contradict "not collected" | **Fixed.** Reworded, and shown only beside a count. |
| Minor | The lower-bound note says "this" but covers the counts | **Fixed.** "…so this figure and the counts below may be a little low." |
| Minor | "Wakelocks" is jargon without a term | **Fixed.** "Partial wakelocks" / "Kısmi uyanık tutmalar" as a term. |
| Minor | The alarm note is a fragment | **Fixed.** Full sentences, EN and TR. |
| Nit | Labels drift from Drain | **Fixed.** "Show meaning" / "Anlamını göster"; "Tablo olarak gör". |
| Nit | EN and TR differ on the Settings line | **Fixed.** "You can turn it on in Settings." |

## Result

No critical finding. All seven majors and every minor and nit are fixed.
