# Polish: Drain (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3).
- Method: impeccable 4.7.1 `polish`. A fresh read-only subagent checked the goldens at `e607c56`
  against DESIGN.md, SPEC 10 and 11 and the earlier gate records. The fixes were then viewed in the
  re-recorded goldens.

**What holds.** One gutter and the 4 dp grid on both screens. Type and colour roles follow DESIGN.md:
copper only on the chosen window, data hues only on components. Drain and Compare share their
controls. Every SPEC 10.11 state says what to do next. EN and TR use the same terms, and RTL and
200% text reflow without clipping. Typed input in a short window is the right mode for the range dialog.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | The range dialog hides "Show" until a start date exists, so typed mode shows only Cancel, and Cancel moves when Show appears | **Fixed.** `CoulombTextButton` takes `enabled` (text-primary at Material's 38% while disabled). Show is always there, enabled once a start date is chosen (`drain-range`, `drain-range-landscape`; Compare shares the dialog). |
| Minor | The range dialog has two start edges: title at 24 dp, headline at 64 dp | **Fixed.** Material's headline with the title's 24 dp start. |
| Minor | Wakeups rows centre the value on the whole text block, and the value never drops under the name at 200% | **Fixed.** `RecordRow` uses `NameBesideValue`, like Apps and Compare rows. |
| Minor | Kernel subsystem sub-heads in Caption emphasis read heavier than the group head | **Fixed.** Caption in secondary ink. |
| Minor | Compare's Derived mark sits in its own slot, away from a wrapped caption | **Fixed.** `MarkedText`, so the mark follows the last word. |
| Minor | Compare sets notes that qualify figures in Body, where Drain uses Caption | **Fixed.** `Qualifier` (Caption) for a partly collected group and a withheld change; `Note` (Body) stays for lines that stand in for content. |
| Minor | Mono names break after `_` even when a space break exists, leaving "ipa_ / ws" | **Fixed.** Break points go only into runs of 16 or more characters without a space. |
| Minor | The Unattributed head sits 4 dp above its row, not 12 dp | **Fixed.** 12 dp. |
| Nit | Component swatches centre on the whole row, and the name doesn't share the value's line | **Fixed.** The swatch centres on the name's first line; name and value use `NameBesideValue`. |
| Nit | An exact zero prints "0.0", like a trace | **Fixed.** An exact zero is "0"; a trace stays "0.0" (`Formats.amount`, `roundsToZero`; `CompareFiguresTest`). |
| Nit | Compare's notes sit between the bar key and the rows | **Fixed.** The key comes last, above the first row. |
| Nit | Compare reuses "for this window" | **Fixed.** `drain_compare_components_coarse` ("in one of these periods" / "bu dönemlerden birinde"). |
| Nit | "honours" is British spelling | **Fixed.** "honors". |
| Nit | The range picker's mode-switch icons are Material's filled icons | **Accepted.** `DateRangePicker` gives no way to restyle them; a narrow exception, recorded here. |

## Result

No critical finding; the major is fixed. With this pass, all eight SPEC 11.12 gates have run on
Drain with no critical or major finding open. The emulator check (rail insets with 3-button
navigation in landscape, TalkBack on the opened lists) is still to run.
