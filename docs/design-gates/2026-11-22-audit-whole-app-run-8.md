# Whole-app audit, run 8

- Date: 2026-11-22, phase P6 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native), one independent read-only review with a fresh brief;
  about 105 of 417 goldens opened (all 39 widget goldens, every screen's main variants, dark and
  true black), 1,646 strings compared by script, run 7's diff read in full, at `fccaca9`. Nothing
  ran on a device.
- Result: **0 critical, 0 major. Clean**, the second consecutive clean run after run 7: **the
  whole-app design gate passes** (SPEC 11.12).

## Scores

16 / 20: accessibility 3, performance 3, theming 4, platform conformance 3, adaptivity 3.

## Findings and dispositions

None blocks the gate. The minors were fixed after it (ADR-0069 §13).

| # | Sev | Finding | Disposition |
|---|---|---|---|
| 1 | Minor | On an old reading, five times on Now and Charging (level table, last-discharge caption, Charging's "Since" caption, summary start, curve table) still judge days by the reading. | Fixed after the gate (ADR-0069 §13). |
| 2 | Minor | The Last night widget at 250×110 dp with 200% text falls to the bare rate, with no day and no spoken time. | Fixed after the gate (ADR-0069 §13). |
| 3 | Minor | The Level widget's clock glyph sits beside the Modeled ring at the same size and reads as a second mark. | Fixed after the gate (ADR-0069 §13). |
| — | Nit | The glyph does not scale with text; phase words stay present tense on an old reading; a trailing "·" in Turkish at 200%. | Open, nit. |
