# ADR-0058: Widgets and the tile read once per snapshot

- Status: accepted
- Date: 2026-11-21
- Phase: P5 (SPEC 12.4, 12.5)
- Builds on: ADR-0057 (notifications follow the collector's snapshots)

## Context

SPEC 12.4 asks for four Glance widgets updated from snapshot events with no periodic interval,
themed, with generated previews, responsive, and with text that never truncates mid-number. SPEC
12.5 asks for a Quick Settings tile refreshed from the latest light snapshot only. A launcher draws
a widget from `RemoteViews`: it cannot use the app's bundled fonts or its canvas drawing, and
Glance cuts text that does not fit at a character, not at a word.

## Decisions

1. **One read per snapshot.** `WidgetStore` holds what the widgets show. The app's snapshot
   observer calls `refresh` last; a light snapshot re-reads the level alone, a heavy one every part
   whose widget is placed. Nothing is read while no widget is placed, and only a widget whose part
   changed is drawn again. A widget composed after a process start reads its own part once. The
   widgets observe the store, not the repositories, so a live Glance session does not re-read on
   each sample.
2. **No schedule.** `updatePeriodMillis` is 0 and no work is enqueued. Glance's own session worker
   runs only when the launcher or `updateAll` asks.
3. **System face.** Widgets use DESIGN.md's sizes and colours with the system sans: `RemoteViews`
   text cannot take the bundled Hanken Grotesk. Glance offers weights 400, 500 and 700; the 600
   roles take 700.
4. **Provenance marks** are four vector drawables with `ProvenanceShape`'s geometry, tinted
   `text-secondary`. Each figure's row is read by TalkBack as "figure, provenance". The top
   drains' shares carry one Derived mark on the heading.
5. **Never cut.** A sentence is shown only where an estimate on the wide side says all of it fits
   (`WidgetFit`); otherwise it is left out. So the 2×1 level widget shows the level and its state,
   and the verdict appears when the widget is made taller or wider. Figures are single short lines.
   Top drains shows as many of its three rows as the height holds.
6. **Level and verdict.** The verdict is the Drain Story's one line, built from the same input as
   Now's. On the charger there is no story; the state says "Charging", "Full" or "Plugged in".
7. **Last night.** The latest screen-off stretch of an hour or more by its end. The hero is its
   rate; below the three points a rate needs it is the level lost, and on the charger it says so
   (ADR-0056 §4).
8. **Health.** The capacity estimate, health against the design capacity, and the platform's
   cycle count (Measured) or, without one, Coulomb's own count (Derived).
9. **Previews** are the real layouts with made-up figures, so the picker never shows the owner's
   data. They are handed to the launcher once per app version and language, because the platform
   limits how often an app may set them.
10. **Colours.** The Coulomb palette, light and dark by the launcher's mode. The wallpaper-colour
    option of SPEC 12.4 follows the Theme setting, which P6 builds.
11. **The tile.** Label: the level. Subtitle: on battery, the screen-on rate (the state the phone
    is in while the panel is open), else the screen-off rate, else the state; on the charger, the
    state. It is lit only while charging. `onStartListening` reads the stored state and asks for
    no sample. A tap opens Now; a long press opens Settings through the activity's
    `QS_TILE_PREFERENCES` filter.
12. **Taps.** A widget, the tile or a notification puts an `OpenRequest` in the launch intent;
    the activity shows that screen (`AppNavigator.show`).

## Consequences

- With the screen off and unplugged no snapshot is stored, so a widget shows the level of the
  last snapshot until the next one; level changes are light snapshots, so it is at most a point
  behind while the phone is awake.
- At 200% text the level widget may drop its verdict and top drains a row; nothing is cut.

## Not verified yet

- The widgets and the tile on One UI 9's launcher and panel (sizes of a 2×1 and a 4×2 cell, the
  tile's subtitle). Pending real device. The unit tests render Glance's `RemoteViews` at fixed
  sizes.

## Reverting

Remove the receivers, the tile service and the `refresh` call. Nothing is stored.

## Gate amendments (2026-11-21)

From the P5 per-screen gates (`docs/design-gates/2026-11-21-gates-wake-source-insight-cards-widgets.md`).

1. **Every widget picks its lines by fit.** Text widths are estimated from the character count,
   the text size and the font scale; a line that would not fit is left out or takes its short
   form, in a fixed order per widget. Nothing is cut at the declared minimum or at 200% text.
   Level's minimum height is 50 dp.
2. **Health shows the estimate's range**: the low and high bounds, or "± N" where they do not
   fit, before health and cycles.
3. **The first layout is static**: the app's ground colour, light and dark, in place of Glance's
   spinner.
4. **Top drains is titled "Share of drain since unplugged"**, and the night's end always carries
   its weekday, since a widget is not redrawn at midnight.
