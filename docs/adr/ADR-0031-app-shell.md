# ADR-0031: The app shell

- Status: accepted
- Date: 2026-11-08
- Phase: P4

## Context

SPEC 10.1 asks for navigation through `NavigationSuiteScaffold`: a bottom bar on compact widths, a rail
from medium widths. DESIGN.md (Shell, Navigation) fixes the look:
- a 64 dp top app bar whose title wraps instead of truncating;
- an 80 dp bar on `surface` with a hairline above;
- a 64 × 32 dp `accent-fill` pill on the current destination;
- labels in Caption nav, at the same weight whether selected or not.

Facts about `material3` 1.5.0-rc01 (ADR-0030):
- `NavigationBarItem` draws a 56 dp pill (`NavigationBarVerticalItemTokens.ActiveIndicatorWidth`).
- `NavigationSuite` draws its `NavigationBar` type as a `ShortNavigationBar`.
- `NavigationSuiteScaffoldLayout` places any bar or rail and draws nothing itself.

## Decisions

1. **Coulomb draws its own bar and rail, and Material's `NavigationSuiteScaffoldLayout` places them**
   (`:core:designsystem` `Navigation.kt`). R: no Material item draws DESIGN.md's 64 dp pill, and the
   layout keeps SPEC 10.1's scaffold family.
2. **The window width alone picks the layout:** below 600 dp the bar, from 600 dp the rail. R: this is
   SPEC 10.1's wording. Material's default also gives a compact-height window a bar, but a landscape
   phone has width to spare and little height.
3. **The width class comes from `LocalWindowInfo.containerSize`**, the source adaptive 1.3.0 reads.
   The gutter follows the same class (`currentWindowWidth`, `currentGutter`). R: the shell and the
   gutter switch at Material's breakpoints without adding a dependency to `:core:designsystem`.
4. **Each destination has its own Navigation 3 back stack**, each decorated on its own with
   `rememberDecoratedNavEntries`. Back from a destination's first screen goes to Now; choosing the
   current destination again returns to its first screen. R: a destination keeps its place, saved
   state and ViewModels while another is shown. The ViewModel decorator's documentation names this API
   for exactly that.
5. **Material's inset focus ring is on, in `accent` over a `bg` band** (`LocalRippleThemeConfiguration`,
   `RippleConfiguration.Focus.InsetRing`). R: DESIGN.md makes the focus ring copper, and the band
   keeps it visible on a filled control.
6. **Material's `MotionScheme` is set to `CoulombMotion`'s springs.** Screens cross-fade on the effects
   spring, and predictive back stays seekable. R: Material components then move like Coulomb's own
   (SPEC 11.6).
7. **The window background is `bg`, in light and night variants.** R: launch shows no flash of another
   colour before Compose draws.
8. **Screenshot tests run Roborazzi on Robolectric** (SDK 36, native graphics) through the
   `coulomb.android.screenshot` convention.
   - `recordRoborazziDebug` writes images to `src/test/screenshots`; plain test runs compose every
     screen without writing images.
   - R: the build server renders screens without a device, and SPEC 15.3 lists Roborazzi.

## Consequences

- Feature screens fill the shell's content slot. Each one adds its states to a screenshot test with
  the same convention, for its `/impeccable` gate.
- CI does not compare images yet. That waits until the screens settle, so a moving design does not
  fail every push.
