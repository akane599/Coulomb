# Polish: App detail (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, Compose phase, step 3).
- Method: impeccable 4.7.1 `polish`. A fresh read-only subagent checked every `appdetail-*` golden
  and the shell's list-detail and long-title goldens, after the other seven gates' fixes, against
  DESIGN.md, SPEC 10 and 11, ADR-0041 and the earlier gate records. The fixes were then viewed in
  the re-recorded goldens.

**What holds.** One gutter (16 dp compact, 24 dp from medium) on the 4 dp grid; sections 24 dp
apart on compact and 32 dp from medium. Type roles follow DESIGN.md: Title for section heads, Body
subhead for sub-heads, Label in `text-secondary` for column heads, Data for figures, Mono for tags.
Data hues only on the component bar, neutral trend bars, no copper. Light, dark and black match. The
figures add up. EN and TR use Drain's settled terms. At 200% the tables stack with labelled values
and nothing clips. Loading, empty and error each say what happened and what to do.

## Findings and dispositions

| Sev | Finding | Disposition |
|---|---|---|
| Major | At 130% text, tags break mid-word: the stack threshold converts 120 sp in one step, and non-linear text scaling barely grows a size that large, so rows stay inline | **Fixed.** One em is converted, then multiplied (`TableRow`). At 130% the tables stack and the tags stay whole (`page-130`). |
| Minor | Lines that stand in for a missing table or chart are in Caption; Drain sets them in Body | **Fixed.** `StandIn` (Body) for not collected, none among the longest, and no records in 14 days; `Note` (Caption) stays for qualifiers. |
| Minor | A heading keeps its mark over a sentence with no figure | **Fixed.** "Where it went" and "Last 14 days" drop the mark when a sentence replaces the figures, as Network does (`breakdown-off`, `harden-rtl`, `trend-all-gaps`). |
| Minor | The title and pane title go blank while loading, empty or in error, so on every window change | **Fixed.** The view model keeps the last label it read; the title stays through those states. |
| Minor | RTL: a wrapped tag aligns left while its note sits right | **Fixed.** Mono names align to the layout's start (`harden-rtl`, `ar`). |
| Minor | The package name doesn't go through `breakable` | **Fixed.** |
| Minor | The App info row floats with no rule, in Body name and a 24 dp icon, unlike the lab | **Fixed.** Hairlines above and below, Data, a 20 dp icon, as the lab's row. |
| Nit | "Show all %1$d" takes a raw number | **Fixed.** `%1$s` with `formats.integer`, EN and TR. |
| Nit | The skeleton's gap and header alignment differ from the page | **Fixed** for the section gap and the header's vertical centring. **Accepted:** block heights stay approximate, since the skeleton is still and is shown for well under a second. |
| Nit | Two stale comments | **Fixed.** |
| Nit | In expanded, Caption notes run 85 to 90 characters a line | **Accepted.** They are one- or two-line notes under their figures, and capping them would leave a ragged edge against the full-width tables and charts. |

## Result

No critical finding; the major is fixed. With this pass all eight SPEC 11.12 gates have run on App
detail with no critical or major finding open. Pending: on the API 37 emulator, the shared element,
the split-pane insets with 3-button navigation in landscape, the cut when the split detail swaps
and a landscape split golden; on a device with TalkBack, the walk of the split.
