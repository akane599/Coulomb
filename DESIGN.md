---
name: Coulomb
description: "A battery instrument read off graduated scales: cool slate, hairlines, one copper bracket for every estimate."
colors:
  bg-light: "oklch(0.985 0.004 255)"
  bg-dark: "oklch(0.17 0.012 255)"
  bg-black: "oklch(0 0 0)"
  surface-light: "oklch(0.965 0.006 255)"
  surface-dark: "oklch(0.21 0.014 255)"
  surface-raised-light: "oklch(0.94 0.008 255)"
  surface-raised-dark: "oklch(0.25 0.015 255)"
  hairline-light: "oklch(0.88 0.010 255)"
  hairline-dark: "oklch(0.32 0.015 255)"
  text-primary-light: "oklch(0.22 0.015 255)"
  text-primary-dark: "oklch(0.94 0.006 255)"
  text-secondary-light: "oklch(0.48 0.015 255)"
  text-secondary-dark: "oklch(0.72 0.012 255)"
  accent-light: "oklch(0.60 0.14 50)"
  accent-dark: "oklch(0.74 0.13 55)"
  good-light: "oklch(0.62 0.13 160)"
  good-dark: "oklch(0.76 0.12 160)"
  caution-light: "oklch(0.70 0.15 80)"
  caution-dark: "oklch(0.82 0.14 85)"
  critical-light: "oklch(0.57 0.20 27)"
  critical-dark: "oklch(0.70 0.17 25)"
  accent-fill-light: "oklch(0.88 0.06 55)"
  accent-fill-dark: "oklch(0.38 0.08 55)"
  on-accent-fill-light: "oklch(0.22 0.015 255)"
  on-accent-fill-dark: "oklch(0.94 0.006 255)"
  on-good-light: "oklch(0.22 0.015 255)"
  on-good-dark: "oklch(0.17 0.012 255)"
  on-good-black: "oklch(0 0 0)"
  on-caution-light: "oklch(0.22 0.015 255)"
  on-caution-dark: "oklch(0.17 0.012 255)"
  on-caution-black: "oklch(0 0 0)"
  on-critical-light: "oklch(0.985 0.004 255)"
  on-critical-dark: "oklch(0.17 0.012 255)"
  on-critical-black: "oklch(0 0 0)"
  data-screen-light: "oklch(0.62 0.11 245)"
  data-screen-dark: "oklch(0.74 0.11 245)"
  data-cpu-light: "oklch(0.62 0.11 30)"
  data-cpu-dark: "oklch(0.74 0.11 30)"
  data-radio-light: "oklch(0.62 0.11 150)"
  data-radio-dark: "oklch(0.74 0.11 150)"
  data-wifi-light: "oklch(0.62 0.11 200)"
  data-wifi-dark: "oklch(0.74 0.11 200)"
  data-gnss-light: "oklch(0.62 0.11 95)"
  data-gnss-dark: "oklch(0.74 0.11 95)"
  data-bluetooth-light: "oklch(0.62 0.11 275)"
  data-bluetooth-dark: "oklch(0.74 0.11 275)"
  data-sensors-light: "oklch(0.62 0.11 340)"
  data-sensors-dark: "oklch(0.74 0.11 340)"
  data-other-light: "oklch(0.62 0.015 255)"
  data-other-dark: "oklch(0.74 0.015 255)"
typography:
  display:
    fontFamily: "Hanken Grotesk"
    fontSize: "56px"
    fontWeight: 600
    lineHeight: "60px"
    letterSpacing: "-0.02em"
  display-unit:
    fontFamily: "Hanken Grotesk"
    fontSize: "28px"
    fontWeight: 400
    lineHeight: "34px"
  headline:
    fontFamily: "Hanken Grotesk"
    fontSize: "28px"
    fontWeight: 600
    lineHeight: "34px"
  title:
    fontFamily: "Hanken Grotesk"
    fontSize: "20px"
    fontWeight: 600
    lineHeight: "26px"
  body:
    fontFamily: "Hanken Grotesk"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: "24px"
  body-name:
    fontFamily: "Hanken Grotesk"
    fontSize: "16px"
    fontWeight: 500
    lineHeight: "24px"
  body-subhead:
    fontFamily: "Hanken Grotesk"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "24px"
  data:
    fontFamily: "Hanken Grotesk"
    fontSize: "15px"
    fontWeight: 500
    lineHeight: "20px"
  data-lead:
    fontFamily: "Hanken Grotesk"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: "20px"
  label:
    fontFamily: "Hanken Grotesk"
    fontSize: "13px"
    fontWeight: 500
    lineHeight: "18px"
    letterSpacing: "0.01em"
  label-button:
    fontFamily: "Hanken Grotesk"
    fontSize: "13px"
    fontWeight: 600
    lineHeight: "18px"
    letterSpacing: "0.01em"
  caption:
    fontFamily: "Hanken Grotesk"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "16px"
  caption-emphasis:
    fontFamily: "Hanken Grotesk"
    fontSize: "12px"
    fontWeight: 600
    lineHeight: "16px"
  caption-nav:
    fontFamily: "Hanken Grotesk"
    fontSize: "12px"
    fontWeight: 500
    lineHeight: "16px"
  mono:
    fontFamily: "Spline Sans Mono"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "18px"
rounded:
  chip: "6px"
  row: "12px"
  sheet: "20px"
  large: "28px"
spacing:
  space-4: "4px"
  space-8: "8px"
  space-12: "12px"
  space-16: "16px"
  space-24: "24px"
  space-32: "32px"
  space-48: "48px"
  space-64: "64px"
  gutter-compact: "16px"
  gutter-medium: "24px"
  touch-target: "48px"
components:
  button-filled-light:
    backgroundColor: "{colors.accent-fill-light}"
    textColor: "{colors.on-accent-fill-light}"
    typography: "{typography.label-button}"
    rounded: "20px"
    padding: "10px 20px"
    height: "40px"
  button-filled-dark:
    backgroundColor: "{colors.accent-fill-dark}"
    textColor: "{colors.on-accent-fill-dark}"
    typography: "{typography.label-button}"
    rounded: "20px"
    padding: "10px 20px"
    height: "40px"
  button-outlined-light:
    textColor: "{colors.text-primary-light}"
    typography: "{typography.label-button}"
    rounded: "20px"
    padding: "10px 20px"
    height: "40px"
  button-outlined-dark:
    textColor: "{colors.text-primary-dark}"
    typography: "{typography.label-button}"
    rounded: "20px"
    padding: "10px 20px"
    height: "40px"
  text-button-light:
    textColor: "{colors.text-primary-light}"
    typography: "{typography.label-button}"
    rounded: "24px"
    padding: "0px 12px"
    height: "48px"
  text-button-dark:
    textColor: "{colors.text-primary-dark}"
    typography: "{typography.label-button}"
    rounded: "24px"
    padding: "0px 12px"
    height: "48px"
  chip-info-light:
    backgroundColor: "{colors.surface-raised-light}"
    textColor: "{colors.text-primary-light}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  chip-info-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.text-primary-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  chip-act-light:
    backgroundColor: "{colors.critical-light}"
    textColor: "{colors.on-critical-light}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  chip-act-dark:
    backgroundColor: "{colors.critical-dark}"
    textColor: "{colors.on-critical-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  chip-watch-light:
    backgroundColor: "{colors.caution-light}"
    textColor: "{colors.on-caution-light}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  chip-watch-dark:
    backgroundColor: "{colors.caution-dark}"
    textColor: "{colors.on-caution-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  chip-good-light:
    backgroundColor: "{colors.good-light}"
    textColor: "{colors.on-good-light}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  chip-good-dark:
    backgroundColor: "{colors.good-dark}"
    textColor: "{colors.on-good-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "4px 10px"
    height: "28px"
  window-choice-light:
    backgroundColor: "{colors.surface-raised-light}"
    textColor: "{colors.text-primary-light}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "0px 12px"
    height: "40px"
  window-choice-dark:
    backgroundColor: "{colors.surface-raised-dark}"
    textColor: "{colors.text-primary-dark}"
    typography: "{typography.label}"
    rounded: "{rounded.chip}"
    padding: "0px 12px"
    height: "40px"
  window-choice-selected-light:
    backgroundColor: "{colors.accent-fill-light}"
    textColor: "{colors.on-accent-fill-light}"
    typography: "{typography.label}"
    rounded: "20px"
    padding: "0px 12px"
    height: "40px"
  window-choice-selected-dark:
    backgroundColor: "{colors.accent-fill-dark}"
    textColor: "{colors.on-accent-fill-dark}"
    typography: "{typography.label}"
    rounded: "20px"
    padding: "0px 12px"
    height: "40px"
  nav-pill-selected-light:
    backgroundColor: "{colors.accent-fill-light}"
    textColor: "{colors.on-accent-fill-light}"
    rounded: "16px"
    width: "64px"
    height: "32px"
  nav-pill-selected-dark:
    backgroundColor: "{colors.accent-fill-dark}"
    textColor: "{colors.on-accent-fill-dark}"
    rounded: "16px"
    width: "64px"
    height: "32px"
  switch-checked-light:
    backgroundColor: "{colors.text-primary-light}"
    textColor: "{colors.bg-light}"
    rounded: "16px"
    width: "52px"
    height: "32px"
  switch-checked-dark:
    backgroundColor: "{colors.text-primary-dark}"
    textColor: "{colors.bg-dark}"
    rounded: "16px"
    width: "52px"
    height: "32px"
  insight-card-light:
    backgroundColor: "{colors.surface-light}"
    textColor: "{colors.text-primary-light}"
    rounded: "{rounded.row}"
    padding: "16px 16px 8px"
  insight-card-dark:
    backgroundColor: "{colors.surface-dark}"
    textColor: "{colors.text-primary-dark}"
    rounded: "{rounded.row}"
    padding: "16px 16px 8px"
  app-row:
    typography: "{typography.body-name}"
    padding: "12px 0px"
    height: "72px"
  share-bar-light:
    backgroundColor: "{colors.hairline-light}"
    textColor: "{colors.text-secondary-light}"
    rounded: "2px"
    height: "4px"
  share-bar-dark:
    backgroundColor: "{colors.hairline-dark}"
    textColor: "{colors.text-secondary-dark}"
    rounded: "2px"
    height: "4px"
  paired-bar-light:
    textColor: "{colors.text-secondary-light}"
    rounded: "3px"
    height: "6px"
  paired-bar-dark:
    textColor: "{colors.text-secondary-dark}"
    rounded: "3px"
    height: "6px"
---

# Design System: Coulomb

This file records the system the design lab (`design-lab/`, SPEC 11.12) shipped: seven static screens at
412 × 915 CSS px, in light and dark. It is the token source for `:core:designsystem` (ADR-0024).

- **Units.** The lab's px read as sp for text sizes, line heights and tracking, and as dp for everything else.
- **Colour values.** OKLCH is normative (SPEC 11.4). The frontmatter holds the only values. sRGB hex is
  derived by `tools/design/tokens.py` and is not repeated here.
- **Modes.** Every colour has a `-light` and a `-dark` key. True black differs from dark only in `bg` and the
  three status inks that follow it (`-black` keys).
- **Components in the frontmatter.** They exist for impeccable's tooling and follow the same mode suffixes.
  For the switch, `textColor` names the thumb; for the share bar, it names the fill. A `rounded` value in
  px on a button, pill, switch or bar is a full pill (half its height), not a radius token.
- **The lab is a prototype; the port is the product** (ADR-0025). The rules below are what the port
  follows. Where the lab does something else, "Where the lab differs" at the end lists it.

## Overview

**Creative North Star: "The Graduated Scale"**

Coulomb reads like a battery instrument. Values sit on graduated scales, and every estimate is drawn as
a bracket spanning its interval, never as a point. The world refuses the category default: a ring gauge
and a big percentage on a coloured card, with score tiles beneath.

The ground is cool slate, tinted to hue 255; nothing is pure grey. Hairlines divide content instead of
cards. Hanken Grotesk with tabular figures sets numbers like a timetable column. Spline Sans Mono appears
only for raw identifiers. One copper accent marks the reading that matters. Data hues appear only on
power-component marks, each with a direct label. Every figure carries one of four drawn provenance marks.

Density is moderate: one hero per screen, then ruled rows at 72 dp. Motion is calm and critically damped,
and nothing moves while the screen is idle. The personality is SPEC 11.2's: precise, calm, candid.

**Key Characteristics:**
- The bracket on a graduated scale is the signature move: time remaining (Now), the capacity interval
  (Health), time to target beside the target tick (Charging).
- Hairlines and space group content. Cards appear only for a movable unit (an insight, a session).
- Copper is rare: the bracket, charging power and the selection.
- Hue names a power component and nothing else. States use neutral ink.
- Tabular, lining figures on every text style.
- Every figure shows its provenance: Measured, Modeled, Estimated or Derived.

## Colors

A cool slate neutral ramp, one copper accent with a tinted fill for text-bearing surfaces, three status
inks, and eight equal-lightness data hues reserved for power components.

### Primary
- **Copper** (`accent`): marks without text only. The bracket and its cone, the charging numeral and its
  unit (Display size, which counts as large text), the wavy charge level, charge areas in charts, the
  focus ring and the text caret. Never text, never a fill that holds text.
- **Copper Fill** (`accent-fill`): fills that carry text or an icon and mark a chosen value. The filled
  button, the chosen window, the chosen toolbar range, a target, a filter chip, the navigation pill and
  text selection. View tabs do not use it.
- **Ink on Copper Fill** (`on-accent-fill`): equals `text-primary` in both modes (11.90:1 light,
  8.62:1 dark).

### Neutral
- **Slate Ground** (`bg`): the screen background, the sticky top bar and the onboarding footer. True black
  replaces it with `oklch(0 0 0)`; surfaces stay tinted.
- **Slate Surface** (`surface`): the navigation bar and insight cards.
- **Raised Slate** (`surface-raised`): sheets, menus, the floating toolbar, unselected window choices, the
  selected view tab, the Info chip and the unchecked switch track.
- **Hairline** (`hairline`): dividers between rows and sections, figure-strip rules, outlined-button
  borders, share-bar tracks, steps still ahead in the stepper.
- **Primary Ink** (`text-primary`): text, the level line in charts, the checked switch track, completed
  steps, the target tick.
- **Secondary Ink** (`text-secondary`): secondary text, icons in the top app bar, provenance marks, axes,
  share-bar fills, neutral chart states. Never on coloured fills.

### Status
- **Good, Caution, Critical** (`good`, `caution`, `critical`): severity chips (Good, Watch, Act) and status
  icons such as a granted permission. Text on them uses `on-good`, `on-caution` and `on-critical`, never
  `text-secondary`.
- **Info** is not a hue: `text-primary` on `surface-raised` (14.5:1 light, 13.4:1 dark).

### Data palette
- Eight hues at equal lightness (L 0.62 light, 0.74 dark) and chroma 0.11 (ADR-0022): screen 245, CPU 30,
  mobile radio 150, Wi-Fi 200, GNSS 95, Bluetooth 275, sensors 340. Other is neutral (C 0.015).
- They appear only on power-component marks: swatches in a breakdown, Timeline tracks, density spans.
  Every coloured item has a direct text label.
- Light data hues reach 3.30 to 3.69:1 on `bg`.

### Values that stay out of the token set
The lab uses these, and the port derives them in code rather than as colour tokens:
- **State layers:** hover is `text-primary` at 8%, press 10% (Material 3).
- **Scrim shadow:** `text-primary` at 16% alpha in light, black at 50% in dark.
- **Chart tints**, all mixed from a token: cone, interval band and the level area at 14%; density spans,
  light Doze and alternate top-app spans at 20%; the charge track at 28%; discharge current at 30%; charge
  current at 45% of `accent`. Each sits on a solid edge (see Charts).

### Named Rules
**The Hue Names a Component Rule.** A data hue means one power component. Doze, top app, signal,
temperature and wear stress are states, so they use neutral ink: `text-secondary` solid or a 20% tint
with a 1 dp outline.

**The Narrow Copper Rule.** Copper marks the bracket, charging power and the selection. As emphasis it
appears at most once per region. Charging drawn in a chart is a data encoding and counts once per chart.
Discharging uses neutral ink.

**The Chosen Value Rule.** `accent-fill` marks a chosen value: a window, a target, a filter chip, the
navigation pill. A view tab, such as Drain's lens, switches views of the same data and selects with
`surface-raised` and a check, in ink (ADR-0025 §2).

**The Ink on Fills Rule.** Any fill that carries text or an icon uses a fill token and its on-colour at
4.5:1 or more: `accent-fill` with `on-accent-fill`, status fills with their `on-` inks.

## Typography

**Display Font:** Hanken Grotesk, variable (system sans fallback in the lab only)
**Label/Mono Font:** Spline Sans Mono, variable, for raw identifiers only

**Character:** a plain grotesk set like a timetable: tabular figures that never jitter, firm weights for
values, regular weight for units. The mono face is a tool, not a voice.

Both faces are OFL and ship as variable fonts in `res/font`. Each weight sets its `wght` axis. Both cover
Turkish (SPEC 11.3).

### Hierarchy
- **Display** (600, 56/60, tracking −2%): one hero number per screen: the level on Now, the measured total
  on Drain, capacity on Health, charging power on Charging. Its unit uses **Display unit** (400, 28/34,
  `text-secondary`).
- **Headline** (600, 28/34): screen titles on expanded layouts. Compact screens in the lab do not use it.
- **Title** (600, 20/26): app bar titles, section titles, the onboarding title on compact widths, figures
  in a figure strip, the Timeline readout.
- **Body** (400, 16/24): story sentences, the verdict, descriptions, switch labels, definition lists.
  **Body name** (500) for row names. **Body subhead** (600) for subheads and insight-card titles. Prose
  runs 40–70 characters per line.
- **Data** (500, 15/20): table values, row values, the ETA line, checklist items. **Data lead** (600) for
  a bold lead word in a Data line.
- **Label** (500, 13/18, tracking +1%): column headers, figure-strip headings, chips, selection controls,
  list-group headings, the stepper label. Selected chips, choices and tabs stay at 500. **Label button**
  (600) for buttons and text buttons.
- **Caption** (400, 12/16): units beside small figures, row details, share percentages, chart captions,
  footnotes, the window note. **Caption emphasis** (600) for the emphasised figure in chart text and
  chart keys. **Caption nav** (500) for navigation bar labels, the current one included.
- **Mono** (400, 13/18): wakelock tags, package names, kernel names, commands.

### Named Rules
**The Timetable Rule.** Every text style sets `tnum` and `lnum`, not only numbers in tables
(`fontFeatureSettings = "tnum,lnum"`; ADR-0022 §5, ADR-0024 §6).

**The No Fourteen Rule.** The roles above are the only sizes. 14 sp is not a role, and a new size needs a
SPEC edit, not a local style.

**The Fixed Weight Rule.** Each role keeps its weight in every state. Selection never changes font weight;
it shows by fill, check or indicator (ADR-0023 §3, ADR-0025 §3).

**The Unbreakable Figure Rule.** A number, its unit and its provenance mark never break apart. Prose
wraps "pretty", so no word sits alone on a last line; headings balance. Names and identifiers break
only between words, never mid-word.

## Layout

- **Grid.** 4 dp. Spacing steps: 4, 8, 12, 16, 24, 32, 48, 64.
- **One gutter per screen.** 16 dp on compact widths, 24 dp on medium and up. Every element on a screen
  shares that gutter. Content that runs to the edge (the window selector's row) uses negative margins and
  pads back to the same gutter.
- **Targets.** 48 dp everywhere. Material buttons and window choices are 40 dp high and extend their hit
  area to 48. A section head's text button keeps its 48 dp target without adding height to the head.
- **Vertical rhythm.** Sections are 24 dp apart on compact widths and 32 dp on medium and up (SPEC 11.5,
  ADR-0025 §1). The 24 dp gap keeps all three of Now's top drains clear of the navigation bar at
  412 × 915. A section head sits 12 dp above its first row. App rows are at least 72 dp, switch rows
  56 dp. Insight cards stack 12 dp apart.
- **Hero.** On Now the hero is at least 30% of the first viewport (31.9% in the lab). It holds the
  reading, its scale and the verdict sentence. No action sits in the hero.
- **Shell.** Small top app bar (64 dp) with the title on the left and up to three icon buttons on the
  right. A long title wraps to a second line; it never truncates. Bottom navigation bar of four
  destinations (80 dp plus the gesture area) on `surface` with a hairline above.
- **Depth order.** Verdict, Story, Evidence, Raw: each one tap from the last (SPEC 10).

### 200% text and reflow
Android scales text non-linearly at 2.0. Layouts reflow and never clip (SPEC 11.3). As built:
- The hero's side text drops under the reading. Figure strips wrap: three columns, then two and one.
- App rows wrap names and details; values keep their column.
- Window choices wrap to a second line instead of scrolling off the edge.
- Under a container query in em, which follows text size, lens segments stack and table rows put the name
  above its values, each value led by its column's name in `text-secondary` ("Time 9 min"). The query
  never applies at 1.0 or 1.3.
- The floating toolbar scrolls its ranges internally; the layers button and Now stay in reach.
- Provenance glyphs stay 12 dp.
- **Names break only between words.** At large text the name column takes width from the value column;
  a word never splits mid-word.
- **Text fits its container.** A count in a fixed tile, such as the remainder row's "+22", grows the tile
  or moves out of it.
- **Chart labels are sp text and re-lay out.** Each chart draws its labels at native sp size and re-lays
  them as text grows.
- **App bar titles wrap** to a second line instead of truncating.

## Elevation & Depth

The system is flat. Depth comes from tonal steps (`bg`, `surface`, `surface-raised`) and hairlines, not
shadows. Surface tint is off in the Material mapping.

### Shadow Vocabulary
- **Floating** (`0 2px 6px` and `0 8px 24px`, scrim shadow): the Timeline floating toolbar, the only
  element that floats over content.
- **Hover lift** (`0 1px 3px`, scrim shadow): a filled button under a pointer.

### Named Rules
**The Hairline, Not Card Rule.** Group with space and 1 dp hairlines. A card holds only content that
moves as a unit, such as an insight or a session, and cards never nest.

## Shapes

- **Radius scale:** 6 (chips, the window picker, inner corners of connected groups), 12 (rows on press,
  inputs, insight cards, stacked segments), 20 (sheets), 28 (large surfaces, the floating toolbar). No
  other radius exists.
- **Pills:** buttons, text buttons, toolbar ranges, the navigation pill and switches are fully round:
  half their height (20, 24, 16).
- **Connected groups:** the window selector's inner corners take the 6 dp chip radius and its outer ends
  are fully round; the chosen window morphs to fully round. The view tabs have fully round outer ends and
  square inner joins.
- **App icons** are drawn as the system provides them. No radius token applies.
- **Bars:** share bars and stepper segments are 4 dp high with 2 dp ends. Paired bars are 6 dp high and
  fully round.
- **Provenance marks** are drawn shapes in a 12 dp box with a 1.5 dp round-capped stroke (see Components).
  Their four shapes are reserved for provenance.

## Components

### Provenance marks
The four marks, drawn in `text-secondary` at 12 dp, sit after the figure they qualify (SPEC 6.7,
ADR-0023 §1):
- **Measured:** solid dot. A direct counter from hardware, kernel or framework (Coulomb delta, cycle
  count, wakelock time).
- **Modeled:** ring. The output of a model Coulomb does not own (per-app mAh from Android's power model;
  the fuel gauge's level, state of health and capacity). The battery level is Modeled.
- **Estimated:** bracket, an interval bar with end stops. Coulomb's statistical estimate (capacity, time
  remaining, wear stress).
- **Derived:** half-filled dot. Arithmetic on the above (unattributed = measured − modeled, a scaled share).

A Modeled value names its source beside it ("Android", "Battery fuel gauge"). Where a table or chart has
one source, one mark on its header covers every value. The last onboarding step teaches the marks in a
key, one line each. Long-press explains a mark; TalkBack gets an "Explain provenance" action.

### The bracket on a graduated scale (signature)
- A hairline scale with tinted gridlines at quarter intervals and units on the axis.
- The estimate is a copper bracket (3 dp stroke, round caps) spanning the interval on the axis, labelled
  with the range in Data lead. The level line ends where it meets the "now" rule, with no dot; a point
  estimate inside the bracket is a tick. On Now, a 14% copper cone with dashed 1 dp edges narrows from the
  present reading into the bracket. On Charging, the bracket sits beside the target tick.
- On Now the scale starts at the unplug, so the line covers the same span as the verdict sentence, and
  the bracket stays at the right end. Level history that does not reach back to the unplug leaves a gap,
  never an invented line (ADR-0026 §2).
- The same range is printed in text beside the reading, with the Estimated mark.

### Rows with share bars
- **Shape:** a three-column grid: 40 dp icon, name column, value column; 16 dp gaps; at least 72 dp tall,
  12 dp vertical padding; a hairline between rows and above the first.
- **Content:** name in Body name; detail in Caption, `text-secondary`; value in Data, right-aligned with
  its provenance mark; share percentage in Caption under the value.
- **Share bar:** 4 dp, `hairline` track, `text-secondary` fill, spanning the name and value columns 8 dp
  below them. Shares are neutral; hue appears only in a component breakdown.
- **Breakdown:** an expanded row lists components as a 10 dp data-hue swatch, the name in Label and the
  mAh in `text-secondary`, with no container and no tap target. Energy with no component ("Not broken
  down") takes a hollow swatch, a 1 dp `text-secondary` outline, and a hollow segment at a stacked bar's
  end. A 48 dp expand button in `text-secondary` sits at the row's end and turns over when open; once
  any row of a list has one, every row keeps that end slot, empty where there is no breakdown, so the
  figures stay in one column.
- **Paired bars (Compare, ADR-0038 §8):** a row comparing two periods has two 6 dp bars on one scale, the
  list's largest rate filling the width, with no track. The first period's bar is hollow (a 1 dp
  `text-secondary` outline), the second's filled `text-secondary`; a bar above zero is never shorter than
  it is high. Each bar's amount sits in Caption at its end, in one column shared by both labels. The
  value column holds the signed change in Data. A key under the list caption shows a 16 dp swatch of
  each bar beside its period. The bars and labels are decoration: TalkBack reads the row as one
  sentence with both rates and the direction of the change.
- **Selected (list-detail, SPEC 10.10):** the row whose App detail is open beside the list sits on
  `surface-raised`, 8 dp wider than the row on each side, with 12 dp corners, and carries selected
  semantics. No copper: the chosen window stays the region's one copper selection.
- **Remainder and gap:** a truncated list ends with a remainder row ("22 more apps") whose icon slot is a
  hairline-outlined tile. Unattributed energy has its own group with a dashed tile. The list caption names
  the denominator ("Shares are of the 1,204 mAh measured"). The remainder count fits its tile at every
  text size.

### Figure strip
Three timetable columns divided by hairline rules: heading in Label, `text-secondary`; figure in Title 600
with its unit in Label and its mark; a Caption line under it. Columns are at least 7.5 em and wrap.

### Window selector (connected button group)
- Three preset choices form a radio group (Since unplugged, Today, Last 24 h). **More** sits outside the
  group, because it opens a sheet.
- Choices are 40 dp on `surface-raised`, Label, 12 dp side padding, 2 dp apart; they wrap with 8 dp
  between lines. Inner corners are 6 dp; the group's outer ends are fully round.
- The chosen window is `accent-fill` with `on-accent-fill`, a leading check icon and a fully round shape,
  at the same Label weight. It is the region's one copper selection.
- A Caption note below states the window ("5 h 12 min, from 100% to 74%").
- Where a page has no room, a single window picker (32 dp, hairline border, 6 dp radius) stands in.

### View tabs (segmented button)
- Drain's lens (Apps, Components, Wakeups) switches views of the same data. Equal segments in a hairline
  outline, 40 dp, Label. A long label widens only its own segment.
- The selected tab is `surface-raised` with a check icon, in ink, at Label's 500 weight. It never uses
  `accent-fill`: the window above is the region's copper selection (SPEC 11.4, ADR-0025 §2).
- Tabs stack when the container is narrower than 14 em.

### Switches
Neutral ink, never copper. Track 52 × 32 dp.
- Checked: track `text-primary`, thumb `bg` at 24 dp (16.0 to 17.6:1).
- Unchecked: track `surface-raised` with a 2 dp `text-secondary` outline, thumb `text-secondary` at 16 dp.
- The label is Body, in a 56 dp row.

### Insight cards
- `surface`, 12 dp radius, padding 16 16 8. Title in Body subhead; text in Body, `text-secondary`.
- The head holds the severity chip and an overflow button (Snooze, Dismiss).
- Actions wrap with 8 dp gaps: a filled primary button that opens the action preview sheet in place
  (SPEC 13), and "Why am I seeing this?".

### Reconcile bar
- One bar, two parts: the modeled share solid in `text-secondary`, and the unattributed gap hatched in
  1.5 dp `text-secondary` lines with a 1 dp outline.
- Its labels are HTML text under the bar, not chart text, so they carry provenance and wrap: "Modeled
  1,062 mAh" with the Modeled mark and "Unattributed 142 mAh" in `text-primary` 600 with the Derived mark.
- The parts sum to the Display total above it (1,062 + 142 = 1,204 mAh).

### Stepper
Four equal segments, 4 dp high, 4 dp apart. Done and current steps fill with `text-primary`; steps ahead
are `hairline`. "Step 4 of 4" in Label, `text-secondary`, sits 8 dp below.

### Buttons
Three kinds only: filled, outlined and text. There is no tonal button; a raised fill on `surface` does not
reach 3:1.
- **Filled:** `accent-fill` with `on-accent-fill`, Label button, 40 dp, 20 dp side padding. One per region.
- **Outlined:** 1 dp `hairline` border, `text-primary`. The secondary action.
- **Text button:** Label button, 48 dp, 12 dp side padding, fully round state layer. Section heads and
  "View as table".
- **Hover / press:** a state layer (8% / 10% `text-primary`); the filled button gains the hover lift.
  Focus is a 2 dp copper ring at 2 dp offset.

### Chips
- 28 dp, 6 dp radius, Label, 4/10 padding, never wrapping.
- Severity: Act is `critical`, Watch `caution`, Good `good`, each with its on-colour; Info is
  `text-primary` on `surface-raised`.

### Navigation
- **Bottom bar:** four destinations (Now, Drain, Timeline, Health), icons over Caption nav labels. The
  current one has a 64 × 32 dp `accent-fill` pill; its label stays Caption nav.
- **Top app bar:** Title on the left, wrapping to a second line when long; Action Center, Privileges
  and Settings as 48 dp icon buttons in `text-secondary`.
- **Floating toolbar (Timeline):** `surface-raised`, 28 dp radius, floating shadow; range buttons 48 dp,
  the chosen one `accent-fill` with a check icon at the same weight; a hairline separator; a "Now" text
  button.
- Icons are Material Symbols Rounded, one weight and optical size. App icons are the apps' own and are
  never tinted.

### Charts
- Custom Canvas drawing. Direct labels instead of legends; units on every axis; `hairline` gridlines at
  quarter intervals; axes in `text-secondary`.
- Discharge plots below the zero axis and charging above, so direction reads without colour.
- Chart text is drawn in sp at native size (Caption, `text-secondary`; emphasis in `text-primary` 600).
  The plot width follows the container; labels never scale with it.
- A translucent fill never carries meaning alone: every area and band has a solid edge or outline at 3:1
  or more. Lighter states are a 20% tint with a 1 dp outline; stronger states are solid. Interval bands,
  such as Health's capacity band, take a solid edge too.
- Event marks closer than 3 dp become a density span: a 20% tint of the track colour with a 1 dp stroke,
  and the rate in the caption ("12 wakeups an hour").
- The four provenance shapes never serve as chart markers. Markers are crosses or ticks; Health draws each
  qualifying window as a cross. The current point is where the line meets the "now" rule, with no dot, and
  a point estimate is a tick.
- Scrubbing shows a hairline crosshair and a readout in Data; values snap to samples.
- Every evidence chart has a visible "View as table" text button under its caption, and every chart,
  hero scales included, has the same semantics action.

### Motion
SPEC 11.6, held as tokens in `lab.css` and ported as `CoulombMotion`:
- **Spatial springs:** damping ratio 0.95.
- **Effects springs:** damping ratio 1.0.
- **Stiffness:** fast 1400, default 700, slow 300.
- **Reduced motion:** when animator scale is 0 or "Remove animations" is on, every transition becomes a
  150 ms opacity crossfade, and nothing else moves.

What moves: digits roll in place, list reorders animate, charts draw in once on first appearance, an app
row's icon tile moves into App detail's header on the spatial spring, and predictive back is seekable.
The icon is shared only when the detail covers the list; beside the list, in list-detail, the row stays
in view and nothing moves between the panes. CSS has no spring, so the lab
tweens with an ease-out quart curve at 150 and 250 ms; that curve is a lab stand-in, not a token.

**The Still Screen Rule.** Nothing animates while idle: no shimmer, pulse or loop. The live current chart
redraws at most once a second. The wavy level is allowed only on Charging while plugged in.

## Do's and Don'ts

### Do:
- **Do** draw every estimate as a range: a copper bracket on its scale, and the range in text with the
  Estimated mark.
- **Do** put a provenance mark on every figure, or one mark on the header of a single-source table or
  chart.
- **Do** keep the verdict, the reading and its scale inside the first viewport on Now, with the hero at
  30% or more.
- **Do** end a truncated share list with a remainder row and state the denominator, so the visible rows
  sum to the whole.
- **Do** label every coloured item directly, and give every chart a "View as table" alternative.
- **Do** use one gutter per screen (16 dp compact, 24 dp medium) and 48 dp targets everywhere.
- **Do** name the charging phase in plain words ("full speed", "slowing down near full").
- **Do** keep a number, its unit and its mark on one line, and wrap names only between words.

### Don't:
- **Don't** draw a ring gauge, a big percentage on a coloured card or score tiles.
- **Don't** set text in copper, or put text on a copper fill other than `accent-fill`.
- **Don't** use a data hue for a state (Doze, top app, signal, temperature, wear stress).
- **Don't** use a dot, ring, bracket or half dot as a chart marker.
- **Don't** let a translucent fill carry meaning without a solid edge.
- **Don't** wrap sections in cards or nest cards.
- **Don't** add a size outside the type roles; 14 sp is not a role.
- **Don't** change font weight to show selection.
- **Don't** use a tonal button, or a radius outside 6, 12, 20 and 28 other than a full pill.
- **Don't** use `text-secondary` on any coloured fill.
- **Don't** animate while idle, bounce, or overshoot.
- **Don't** scale chart labels with the plot, or break a word mid-word to fit a column.

### Where the lab differs
The lab stays as reviewed; the port follows the rule (ADR-0025 §3, ADR-0026).
- Row names and mono text break anywhere, so "Unattributed" splits at 200%. Port: words break only
  between words (SPEC 11.3).
- Drain's "+22" spills out of its 40 dp tile at 200%. Port: text fits its container (SPEC 11.3).
- Chart labels sit in fixed SVGs and do not scale. Port: sp text, re-laid out per chart (SPEC 11.8,
  ADR-0022 §4).
- A solid dot marks the current point on Now, Health, Charging and Timeline. Port: the line meets the
  "now" rule with no dot; a point estimate is a tick (SPEC 11.8, ADR-0023 §10).
- Health's capacity band is a 14% copper fill with no edge. Port: a solid edge at 3:1 or better
  (ADR-0022 §4).
- Selected navigation labels, window choices, lens tabs and the toolbar range go to weight 600. Port:
  weights stay fixed per role (ADR-0023 §3).
- Now's "Open battery protection" is a tonal button. Port: outlined (SPEC 11.4).
- The window group's inner corners are 8 dp, and App detail's icon has a 16 dp radius. Port: inner
  corners 6 dp; app icons as the system provides them (SPEC 11.5).
- The app bar title truncates with an ellipsis. Port: it wraps to a second line (SPEC 11.3).
- Now's chart starts two hours back, while the verdict covers the whole session. Port: the scale starts
  at the unplug, with the bracket at the right end (ADR-0026 §2).
- App detail's story has no anomaly sentence and no insight card. Port: an open Act insight ends the
  story and its card follows, before the evidence; Actions stay last (SPEC 10.4, ADR-0026 §3).
