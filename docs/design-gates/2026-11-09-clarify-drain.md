# Clarify: Drain (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3).
- Method: impeccable 4.7.1 `clarify`. A fresh read-only subagent read every Drain and Compare string
  in English and Turkish against the goldens. Each finding was re-checked against the tree.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | "per hour of use" says screen time; the rates are per hour on battery | **Fixed.** "per hour on battery" / "pilde geçen saat başına". |
| Major | "Not measured" covers two cases: no counter reading, and a counter that shows no charge used | **Fixed.** "No drain measured" with "The counter shows no charge used", beside "Not measured" with "No battery counter reading" ("Pil sayacı değeri yok"). |
| Minor | Notes state a problem without a way out | **Fixed.** "…no records for these days. Choose other days to compare."; "…too little for rates. Choose a longer period." |
| Minor | Jargon: "each window keeps its longest records", "snapshot" | **Fixed.** "Coulomb keeps only the largest records of each stretch, so a small one may be missing."; "the first full reading after the unplug". |
| Minor | Turkish drift: "pencere" beside "aralık", "uyanmalar" beside "uyandırmalar", formal picker titles | **Fixed.** "aralık" and "uyandırmalar" throughout; "Birinci/İkinci dönemi seç". |
| Minor | Compare's Components lens drops the note Drain shows when power is only under Other | **Fixed.** The same caption when either period has Other (`compare-components`). |
| Nit | "More windows" titles a sheet that also opens Compare | **Fixed.** "More options" / "Diğer seçenekler". |

## Result

No critical finding. All findings are fixed; the goldens are re-recorded.
