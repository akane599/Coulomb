# Clarify: Now (Compose)

- Date: 2026-11-08, phase P4 (SPEC 11.12, Compose phase, step 2).
- Method: impeccable 4.7.1 `clarify`, by the same fresh read-only subagent as harden, on the EN and
  TR strings, the source and the goldens.
- Dispositions describe the code after the fixes.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Minor | "left" is a separate fragment, so other languages can't reorder the time-left line | **Fixed.** One string, "%1$s left" / "%1$s kaldı", with the range in Title inside it and the mark inline (ADR-0034 §33). |
| Minor | No "Calibrating" in the hero while the current is uncalibrated (SPEC 6.1) | **Fixed.** "Discharging · Calibrating" (`now-collecting`). The live caption keeps "The current reading is being calibrated." When calibration ends depends on what the phone reports (SPEC 6.1), so the caption promises no time. |
| Minor | "Estimating time left" gives no timing; `now_drains_collecting` says "snapshot" | **No change.** The first estimate depends on the screen state's rate or on history (SPEC 6.8), so no fixed time is true. "Snapshot" names what pull to refresh takes (`SnapshotTrigger.PULL_TO_REFRESH`), and Drain explains snapshots. |
| Minor | "At this rate" misdescribes SPEC 6.8's blend of the session rate and the personal median | **Fixed.** "Coulomb estimates it reaches empty in…" / "Coulomb’un tahminine göre…". |
| Minor | Plugged in but draining reads "Not charging" | **Fixed.** "Discharging while plugged in" / "Prizdeyken boşalıyor" (ADR-0034 §34). |
| Minor | TalkBack reads "h", "min", "%/h" and "mA" as written | **Open.** Compose's `AnnotatedString` offers only verbatim TTS annotations, so a spoken form needs a content description on every figure. That lands once for all screens with the P4 accessibility pass. Listed in `docs/progress.md`. |
| Nit | TR: "ekran kapalı sürede" should be "sürenin" | **No change.** The caption stands alone under "%88", and the genitive "sürenin" needs a noun after it ("…sürenin %88'i"), which is why the critique pass chose "sürede". |
| Nit | TR: "%1$s saatinde bitti" | **Fixed.** "Son pil kullanımından (bitiş: %1$s)." It also reads well with a weekday. The EN string matches: "From the last discharge (ended %1$s)." |
| Nit | TR: "Düşük güven" | **Fixed.** "Düşük güvenilirlik". |

## Result

No critical or major finding. Five minors and two nits are fixed. One minor is open with a named
home, and two are recorded with reasons.
