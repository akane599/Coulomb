# Clarify: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `clarify`, in the same read-only agent run as `harden`.

## Checked, nothing to change

- The voice is calm and specific, and the phase words are plain. Turkish keeps the informal "sen".
- After the critique, no Turkish suffix follows a placeholder.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | "of its baseline" is jargon | **Fixed.** "96% of its usual power"; TR "olağan güce göre". |
| Minor | The temperature figure was labelled "Battery" | **Fixed.** "Temperature"/"Sıcaklık". |
| Minor | The need-more-charges lines did not say a paused charge does not count | **Fixed.** "…without a pause"/"ara vermeden". |
| Minor | "Sets the tick" names a drawing detail | **Fixed.** "Moves the mark on the level bar and the time to it. There is no alert at the target yet." |
| Minor | TR summaries used an arrow, which a screen reader reads aloud | **Fixed.** "Seviye başta %1$s, şimdi %2$s; …". |
| Minor | The falling note did not say when times return | **Fixed.** "No times while the level is below this charge’s highest. They return once it climbs back." |
| Minor | The holding definition said "has not risen", but holding now allows a small current either way | **Fixed.** "has not changed … goes in or out". |
| Minor | The learning line read as if Coulomb were doing something | **Fixed.** "Coulomb can tell which charger this is after…". |
| Nit | TR `temp_steady`, `full_in`, `to_level`, `row_reaches` read stiffly | **Fixed.** "değişmedi", "Dolmaya %1$s", "%1$s seviyesine %2$s", "%1$s seviyesine ulaşma". |
| Nit | Health says "Doluluk" where Charging says "Seviye" | Accepted for the whole-app copy pass. |

## Result

No critical or major finding. Every minor is fixed in EN and TR.
