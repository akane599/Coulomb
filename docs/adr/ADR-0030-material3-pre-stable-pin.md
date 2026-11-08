# ADR-0030: material3 1.5.0-rc01 for the Expressive components

- Status: accepted
- Date: 2026-11-08
- Phase: P4

## Context

SPEC 11.7 puts four Expressive pieces in scope:
- the connected button group, as the window selector;
- the floating toolbar, on Timeline;
- `LoadingIndicator`, only for privileged waits over 400 ms;
- shape morph, only for the charging state glyph.

SPEC 11.6 also wants a custom `MotionScheme`. The stable material3 1.4.0, in Compose BOM 2026.09.00,
has none of these as public API (ADR-0024 §6). SPEC 11.7 allows a pre-stable `material3`: pin the
newest one, isolate it in `:core:designsystem` and record an ADR.

On Google Maven on 2026-11-08, the newest `material3` was 1.5.0-rc01, published 2026-11-04, with no
stable 1.5.0 yet. Its sources jar shows:
- **Public, with no opt-in:** `ButtonGroup`, `ToggleButton`, `HorizontalFloatingToolbar`,
  `LinearWavyProgressIndicator`, `MaterialExpressiveTheme`, `MotionScheme` with `standard()` and
  `expressive()`, and the `MaterialTheme` overload that takes a `motionScheme`.
- **Still `@ExperimentalMaterial3ExpressiveApi`:** `LoadingIndicator`.
- **Dependencies:** Compose runtime, ui and foundation 1.12.0. The BOM already pins 1.12.1, so no
  other Compose artifact moves.

## Decisions

1. `material3` and `material3-adaptive-navigation-suite` are pinned to 1.5.0-rc01, over the BOM. It is
   the newest pre-stable release, as SPEC 11.7 asks, and a release candidate's API is frozen (R: the
   alternative, hand-built copies of four components, would drift from Material and cost more than a
   version bump).
2. The Expressive APIs are used only in `:core:designsystem`. Features use its wrappers: the window
   selector, the toolbar, the loading indicator and the charging glyph.
   - A detekt `ForbiddenImport` rule fails the build on those imports anywhere else.
   - It was proven with a probe file: the imports failed in `:core:ui` and passed in
     `:core:designsystem`.
3. `LoadingIndicator`'s opt-in stays inside the designsystem file that wraps it.
4. The exit plan:
   - Move to 1.5.0 when it is stable; the version line in `gradle/libs.versions.toml` says so.
   - If a release-candidate bug blocks a screen, return to 1.4.0 and let the wrappers draw a plain
     version. The import rule keeps that change to one module.

## Consequences

- The window selector, the floating toolbar, the charging glyph and the custom motion scheme can be
  built on Material's components.
- One dependency line is pre-stable until 1.5.0 ships; the next dependency check looks for it.
