# ADR-0024: Porting the design tokens to `:core:designsystem`

- Status: accepted (no SPEC change; resolves how SPEC 11.4 and 11.12 step 5 are met)
- Date: 2026-11-07
- Phase: P3

## Context

SPEC 11.12 steps 4 and 5 lock the lab's tokens in `DESIGN.md`, port them 1:1 to `:core:designsystem`
and require a unit test that every token in `DESIGN.md` exists in code with the same value. SPEC 11.4
says the OKLCH tokens are "converted to sRGB by a build-time script that writes `Color.kt`". It does not
say how `DESIGN.md` names light and dark values, how out-of-gamut tokens convert, or whether the
generated file is committed.

## Decisions

### 1. `DESIGN.md` frontmatter is the token source, with fixed keys

impeccable's `DESIGN.md` frontmatter has one flat `colors` map. Coulomb's keys are:
- **Colours:** `<token>-light`, `<token>-dark` and, only where true black differs from dark, `<token>-black`. Token names are SPEC 11.4's in kebab case (`surface-raised`, `on-accent-fill`, `data-wifi`). Values are the lab's `oklch(L C H)` strings. OKLCH is normative, as SPEC 11.4 says.
- **True black:** it differs from dark in four tokens: `bg`, and the three status inks that follow `bg` (`on-good`, `on-caution`, `on-critical`).
- **Typography:** one key per SPEC 11.3 role and weight variant (ADR-0023 §3):
  - `display`, `display-unit`, `headline`, `title`;
  - `body`, `body-name`, `body-subhead`;
  - `data`, `data-lead`;
  - `label`, `label-button`;
  - `caption`, `caption-nav`;
  - `mono`.

  Sizes and line heights are in px, which read as sp; tracking is in em.
- **`rounded`:** `chip`, `row`, `sheet`, `large`.
- **`spacing`:** `space-4` to `space-64`, `gutter-compact`, `gutter-medium` and `touch-target`.

### 2. A committed generated file, checked by the build

`tools/design/tokens.py` (stdlib Python, like the other tools) reads `DESIGN.md` and writes `ColorTokens.kt`. The file holds three `CoulombColors` values: `LightColors`, `DarkColors` and `BlackColors`.
- The output is committed, and `--check` reports when it is stale.
- Generating on every Gradle build would add a code-generation task to a module that every screen depends on, for a file that changes only when `DESIGN.md` does.
- A committed file is reviewable in diffs and simple to revert. The build still enforces it, through decision 4.

Type, spacing and radii are few and do not need conversion, so they are written by hand in `Type.kt` and `Dimens.kt`. Decision 4 checks them too.

### 3. The conversion

- Björn Ottosson's OKLab matrices in double precision, the sRGB transfer function, then 8-bit rounding half up.
- A colour outside sRGB is clipped per channel. That is what CSS Color 4 gamut mapping returns when the clipped colour is within the just-noticeable difference (deltaEOK 0.02). It is also how the lab rendered.
- A token further out stops the script: the token itself must change.
- Two light tokens are clipped today, `caution` (deltaEOK 0.0053, #CE9200) and `data-wifi` (0.0048, #0099A0). Both are a quarter of the JND.
- Chrome paints 36 of the 39 distinct lab colours identically. The other three differ by one step in one channel (`surface` light, `caution` dark, `data-screen` light). CSS Color 4's own double-precision matrices give this script's values, so the step comes from Chrome's single-precision pipeline. The contrast figures in the audits used this conversion.

### 4. The parity test converts independently

`DesignTokensTest` reads `DESIGN.md` through a test system property.
- It converts every colour through XYZ with the CSS Color 4 matrices, not the script's direct matrices, and compares the result with the Kotlin values.
- Explicit name tables map each token to its property. A token in `DESIGN.md` with no property fails, and so does a property with no token.
- Typography, radii and spacing are compared field by field.
- A one-digit change to a lightness in `DESIGN.md` fails the build.

### 5. Material 3 mapping

Coulomb's own components read `CoulombTheme.colors` and `CoulombTheme.typography`. The Material theme gets a mapping, so that Material components used unstyled stay inside SPEC 11.
- **Colour slots:** every slot that Material draws text or icons in is ink. `primary` is `textPrimary` and `outline` is `textSecondary`, because `accent` never carries text. The container slots that Material uses for its navigation pill, selected chip and segment, and filled tonal button are `accentFill` with `onAccentFill`. Surface tint is off.
- **Typography slots:** each slot takes the nearest Coulomb role. `bodyMedium` is Body 16, `labelLarge` is the button label, and `labelMedium` is the navigation caption. Nothing falls back to 14 sp.
- **Shapes:** `extraSmall` and `small` are 6, `medium` 12, `large` 20 and `extraLarge` 28.

### 6. Fonts, figures and motion

- **Fonts:** Hanken Grotesk and Spline Sans Mono ship as variable TTFs in `res/font`. Each weight sets its `wght` variation.
- **Figures:** every style sets `fontFeatureSettings = "tnum,lnum"`. minikin splits the string at commas and hands each tag to HarfBuzz (frameworks/minikin `libs/minikin/FontFeatureUtils.cpp:23-37`, identical at android-16.0.0_r4 and android-17.0.0_r1).
- **Motion:** in the stable material3 1.4.0 (Compose BOM 2026.09.00), `MotionScheme` and the `MaterialTheme` overload that takes it are Kotlin-internal. None of SPEC 11.7's Expressive components are present either: the button group, floating toolbar, loading indicator and wavy progress.
  - The SPEC 11.6 springs are therefore tokens and builders in `CoulombMotion`.
  - The custom `MotionScheme` and the Expressive components wait for the pre-stable pin that SPEC 11.7 allows. Its ADR is due when P4 first needs one of those components.

### 7. One lint exemption

The Compose rule set rejects new CompositionLocals. The theme's two locals, for colours and type, are the case the rule exempts by design.
- The editorconfig allowlist did not take effect through Spotless.
- `core/designsystem/build.gradle.kts` therefore exempts `Theme.kt` alone, with Spotless's `suppressLintsFor`.

## Consequences

- To change a colour, edit `DESIGN.md`, run the script and commit both files. The build fails if either is forgotten.
- `DESIGN.md` key names are part of the code's contract. Renaming one means changing the test's table.
- P4 opens with the material3 pin ADR before the window selector, the floating toolbar or the charging indicator.
