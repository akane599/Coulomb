# ADR-0022: Design lab token additions, chart rules and the P3 process on impeccable 4.7.1

- Status: accepted (edits SPEC 11.1, 11.4, 11.8 and 11.12); decision 3 superseded and the `accentFill` use widened by ADR-0023
- Date: 2026-11-07
- Phase: P3

## Context

The P3 design lab (SPEC 11.12) built seven static screens on SPEC 11's tokens.

### Gaps in SPEC 11's tokens

Measured with the WCAG 2.x formulas on the OKLCH tokens converted to sRGB, three gaps showed up.

**Light data hues are too light.**
- At L 0.66 the eight light-mode data hues reach only 2.84 to 3.15:1 on `bg`.
- SPEC 11.4 asks for 3:1 for UI parts, and colour-coded marks are UI parts.

**Text cannot sit on solid copper.**
- `textPrimary` on light `accent` is 4.17:1, and white is 4.15:1. Both are below the 4.5:1 that SPEC 11.4 and 11.11 require for text on coloured fills.
- In dark mode the accent is light (L 0.74). Light text on it reaches only 2.01:1.

**On-colours are missing.** SPEC 11.11 says text on coloured fills uses `textPrimary` or white, but it names no token per fill.

### Differences between SPEC 11 and impeccable 4.7.1

The installed impeccable (plugin 4.7.1) differs from what SPEC 11.1 and 11.12 describe in four ways.

**Reference files.**
- SPEC 11.1 lists seven reference files: typography, color-and-contrast, spatial-design, motion-design, interaction-design, responsive-design and ux-writing.
- 4.7.1 has no files with those names. The same domains live in its command references: `typeset`, `colorize`, `layout`, `animate`, `operate` plus `harden`, `adapt` and `clarify`.
- Its quality floor, `craft-floor`, applies before any UI edit.

**`concept-seed`.**
- For new work, 4.7.1 runs a `concept-seed` roll that deals competing directions.
- It also says a direction pinned by the brief beats the roll.
- SPEC 11 pins the world: palette, faces, scale, shape and motion.

**`live`.**
- `live` is a browser session in which a person picks elements and variants. It needs the owner at the page.
- SPEC 0.6 makes P3 autonomous until the direction review.

**Detector version.**
- SPEC 11.12 runs `npx impeccable detect`. On 2026-11-07 the newest stable npm release was 4.8.0 (`latest`), while the plugin is 4.7.1.
- Both ship the same 62-rule catalog and returned identical results on every lab page.

### Findings from the lab itself

Building and auditing the lab surfaced rules that SPEC 11 leaves implicit:

- **Provenance of platform-reported values.**
  - The Health screen shows values read from the platform as they are: Android's battery state of health, the kernel fuel gauge's figure and the cycle count.
  - SPEC 6.7 defines Measured as a "direct hardware counter" and Modeled as "platform power model output". Neither fits a figure produced by an OEM method that Coulomb reads unchanged.
- **Data hues on non-component states.**
  - The first Timeline draft used a data hue for Doze.
  - That broke the palette's one meaning: a hue names a power component.
- **Translucent area fills.**
  - The Timeline current areas and the light Doze band were translucent fills of about 2:1, with nothing else carrying the shape.
- **Chart text at narrow widths.**
  - The lab draws charts as fixed-viewBox SVG, so at 360 px every chart label shrinks by 14% (10 px to 8.6 px).
- **Truncated share lists.**
  - The first Drain list summed to 87% of the measured total: seven apps and the unattributed row.
  - The reconcile bar above it summed to 100%.

## Decision

### 1. Token additions and one change (SPEC 11.4)

| Token | Light | Dark | Use |
| --- | --- | --- | --- |
| Data palette lightness | L **0.62** (was 0.66) | L 0.74 (unchanged) | 3.30 to 3.69:1 on `bg` in light |
| `accentFill` | oklch(0.88 0.06 55) | oklch(0.38 0.08 55) | Fills that carry text: filled button, selected chip, navigation pill, text selection |
| `onAccentFill` | `textPrimary` | `textPrimary` | 11.90:1 light, 8.62:1 dark |
| `onGood`, `onCaution` | `textPrimary` | `bg` | Status chip text |
| `onCritical` | `bg` | `bg` | Status chip text |

- `accent` itself stays for marks without text: the bracket, the cone, the charging numeral (Display size, which counts as large text), the wavy level bar and charge areas.
- True black inherits the dark values. Only `bg` changes, as SPEC 11.4 already says.

### 2. Colour roles (SPEC 11.4, 11.8)

- **A hue names a power component, nothing else.**
- **Non-component states use neutral ink.** These are Doze, top app, signal, temperature and wear stress, drawn in `textSecondary` at full strength or as an outlined tint.
- **Charging keeps the accent,** as SPEC 11.4 already says.
- **"At most once per region" counts emphasis:** the primary action, the hero state and the selection.
  - Charging's accent in a chart is a data encoding that 11.4 assigns. It counts as one use per chart, not one per mark.

### 3. Provenance of platform-reported values (superseded by ADR-0023 §1)

This decision marked every value read unchanged from the platform as Measured. The critique showed that this made model outputs, such as Android's state of health and the fuel gauge's capacity, look more certain than Coulomb's own estimate beside them.

ADR-0023 §1 replaces it:
- a direct counter (hardware, kernel or framework) is Measured;
- the output of a model Coulomb does not own is Modeled.

The source is still named beside every platform value: "Android", "Battery fuel gauge" or "Kernel power supply report".

### 4. Chart rules (SPEC 11.8)

- **Chart text is drawn in sp at native size.** The plot width follows the container, so labels never scale with the plot. The lab's viewBox scaling is a lab artifact, not a rule.
- **A translucent fill never carries meaning alone.** Every area and band has a solid edge or outline at 3:1 or better.
  - Lighter states, such as light Doze, are an outline with a 20% tint.
  - Stronger states, such as deep Doze, are solid.
- **A truncated list of shares ends with a remainder row** (for example "22 more apps", with its own mAh and share), so the visible rows sum to the whole.

### 5. Figures and wrapping

- Tabular lining figures are the default for all text, not only for numbers in tables. This is the simplest way to meet SPEC 11.3's "all numbers use tabular figures".
- Prose wraps "pretty", so no single word sits alone on a last line, and headings balance.

### 6. The P3 process on impeccable 4.7.1 (SPEC 11.1, 11.12)

**Reference domains.**
- The seven domains in SPEC 11.1 map to the 4.7.1 references:

  | SPEC 11.1 domain | 4.7.1 reference |
  | --- | --- |
  | typography | `typeset` |
  | color-and-contrast | `colorize` |
  | spatial-design | `layout` |
  | motion-design | `animate` |
  | interaction-design | `operate` and `harden` |
  | responsive-design | `adapt` |
  | ux-writing | `clarify` |

- `craft-floor` is read before every UI edit.

**`concept-seed` is not run.**
- SPEC 11 pins the world, and 4.7.1 lets a pinned direction beat the roll.
- The direction contract records `seed: none (unavailable)` and "position 1 of 1".

**`live` is not run in the autonomous part of P3.** Variants come from critique rounds instead. The owner sees the screens at the P3 direction review (SPEC 0.6), where `live` can be used if they want to pick variants.

**Pinned detector.**
- The detector is `npx -y impeccable@4.8.0 detect design-lab/ --json`, pinned per SPEC 0.3.2.
- The plugin launcher's `detect` must agree with it.
- The gate also runs a rendered scan at 412×915 per page (`detect <url> --viewport 412x915`), because some rules need real text layout. One example is the "straggler" rule: a single word alone on the last line.

**Critique.**
- `critique` runs as one dual-agent pass over all seven screens:
  - A is the design review, with a per-screen section;
  - B is the detector and browser evidence.
- It is written out as one file per screen, `docs/design-gates/<date>-critique-<screen>.md`.
- `audit` runs per screen into `docs/design-gates/<date>-audit-<screen>.md`.

**Questions from the critique protocol** are folded into the P3 direction review instead of being asked mid-run (SPEC 0.6).

## Consequences

- SPEC 11.4 gains the rows above, and the light data palette lightness changes to 0.62.
- SPEC 11.8 gains the chart rules, 11.1 gains the reference mapping, and 11.12 pins the detector and the rendered scan.
- `DESIGN.md` records these values when P3 locks the tokens. The `:core:designsystem` parity test then checks them.
- The light data hues are 0.04 darker than SPEC 11.4 first said. Deuteranopia, protanopia and tritanopia separation must be rechecked on the Compose screenshots (SPEC 11.4); a lightness shift at equal chroma keeps the hue spacing.
- Reverting is a token edit plus the SPEC rows. No data or schema depends on it.
