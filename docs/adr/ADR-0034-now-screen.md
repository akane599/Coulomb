# ADR-0034: The Now screen in Compose

- Status: accepted
- Date: 2026-11-08
- Phase: P4

## Context

P4 builds Now (SPEC 10.2) from the reviewed lab (`design-lab/now.html`, ADR-0026) and the read
models of ADR-0032. The spec leaves several details open. Each decision below has one line of
reason (ADR-0027). The SPEC 11.12 gate outcomes are in `docs/design-gates/2026-11-08-*-now.md`.

## Decisions

1. **The live chart samples every 5 s while Now is open.** `MainActivity` sets the visible screen
   to Coulomb on resume and to none on pause; the collector's sampler policy (SPEC 7.3) then reads
   the current every 5 s with the screen on. The caption says "Updates while this screen is open."
   - R: SPEC 10.2 caps updates at once a second; the existing 5 s visible-screen rate stays inside
     that cap and inside the self-drain budget (SPEC 0.2 ranks budget above the spec's detail).
2. **The verdict is an interim template until the Drain Story (P5).** "Since unplugged 5 h 12 min
   ago, the battery fell 26%; Halcyon used 33% of it": the share is of the window's measured total.
   With no stored window it names no app; with no level change it says so.
   - R: SPEC 17 puts the insight engine and the Drain Story in P5; a template states only figures
     the screen already shows.
3. **Plugged in, Now shows the hero, the state, the last discharge's top drains and the live
   current.** Time left, the scale and the strip need a discharge; the rows carry the caption "The
   last time on battery, until {clock}." The Charging screen replaces this in P4.
   - R: an empty screen while charging answers nothing; the last discharge is the nearest fact.
4. **Pull to refresh asks for a heavy snapshot and shows its indicator until the next stored window
   or 5 s.** The collector debounces such snapshots to one a minute (SPEC 7.2).
   - R: a spinner with no end would promise data the debounce may not deliver.
5. **Time-left ends round outward.** Steps of 10 min from 1 h up and 1 min below; the shorter end
   rounds down, the longer up, and the ends never meet.
   - R: SPEC 6.7 rounds for display; outward rounding never narrows the stated uncertainty (SPEC
     0.2, measurement honesty).
6. **Low confidence is caption text.** A figure with quality below 0.5 appends "Low confidence" to
   its caption ("for 3 h 20 min · Low confidence").
   - R: DESIGN.md has no glyph for it, and a word needs no legend.
7. **An app with no icon shows its initial on a quiet tile.** System UIDs and unlabeled UIDs have no
   package icon.
   - R: an empty 40 dp gap reads as a loading fault.
8. **Now's top three include system UIDs.** The rows rank everything the window measured, named by
   ADR-0033 §8's rule; Drain's apps lens keeps SPEC 10.3's toggle.
   - R: Now has no toggle, and hiding Android System would make the three rows disagree with the
     verdict's share.
9. **The level line is compressed and read incrementally.** A run of equal levels keeps its first
   and last sample, a boot change starts a new segment, and each new sample reads only the samples
   since the last one (`LevelTrail`, `LevelTrailCache`).
   - R: a day of 5 s samples is ~17,000 rows; the line needs a few hundred points and the screen
     must not re-read the day on every sample.
10. **Strip columns are measured in scaled ems.** The minimum column is 7.5 × the scaled size of a
    16 sp em, not a fixed sp count.
    - R: Android 14+ scales large sp values non-linearly, so a 120 sp minimum barely grew at 200%
      text and words broke mid-letter.
11. **Text resolves its direction from its first strong character.** Every type role sets
    `TextDirection.Content`, TextView's default heuristic, and a number with its unit is one
    paragraph (`FigureText`).
    - R: Compose otherwise forces the layout direction, and under an RTL locale Coulomb's English
      fallback strings drew as ".yet" and "h/% 0.8". Separate number and unit texts were mirrored
      apart.
12. **Charts keep time running left to right under RTL.** Labels resolve as text does (§11).
    - R: SPEC 11.12 asks for RTL sanity and Coulomb ships no RTL translation; mirroring time axes is
      a design choice for the translation that brings one.
13. **The app shell takes its screens through a slot (`AppScreens`).** Shell screenshot tests pass
    empty screens.
    - R: the real screens need Hilt view models, which a plain-Application Robolectric test lacks.
14. **Now's three rows end with "Everything else".** It is the window's total minus the three rows
    (Derived), with a "+" tile, and opens Drain. The lab had no such row.
    - R: SPEC 11.8 requires a remainder row on a truncated list of shares. Beyond the other apps it
      holds the device's own drain and the unattributed part, so "22 more apps" would misname it.
15. **On expanded widths Now is one column, capped at 720 dp and aligned to the start.**
    - R: SPEC 10.10 says nothing about Now there. A cap removes the 880 dp gaps between a row's name
      and its figure without a second layout to test. Two panes are a question for the owner
      (`docs/questions.md`).
16. **The scale stays hidden until time left exists.** The verdict and the strip still show.
    - R: before that there is nothing to project. The one-hour minimum span left a mostly empty chart
      that read as missing history. Once a range exists it spans hours, so the minimum rarely
      applies.
17. **The level names its source under the numeral ("Battery fuel gauge").** The drains caption
    names Android ("Shares are of the 1,220 mAh that Android modeled"). The Modeled explanation
    names both models.
    - R: SPEC 6.7 and DESIGN.md require a Modeled value to name its source beside it. The lab left the
      source out, and SPEC ranks above the lab.
18. **Glossary terms are tappable text (`CoulombTerm`) with a dotted underline.** A tap shows a
    persistent tooltip; TalkBack reads the term as a button with a "Show meaning" action. Now has
    one term, "Deep sleep", with SPEC 19.3's definition.
    - R: SPEC 9.3 asks technical terms to carry a glossary tooltip. Compose expands the touch area
      of small pointer targets to 48 dp (`NodeCoordinator.hitNear`, ui-android 1.12.1), so the term
      keeps its line height.
19. **Motion follows SPEC 11.6, and nothing moves while idle.**
    - Digits roll in place, slot by slot from the last digit, on the fast spatial spring
      (`RollingText`, used by the level and the live current).
    - Charts reveal their data from the left once per saved state, on the slow spatial spring
      (`DrawIn`).
    - Rows keyed by UID slide to a new rank on the default spatial spring (`animatePlacement`).
    - Under reduced motion, every one of them is a 150 ms crossfade instead. It runs on a real clock
      (`crossfadeTo`), because Compose ends every animation at once when the animator scale is 0.
    - R: that is the SPEC 11.6 list. The first value of anything appears still, so a cold start
      does not animate.
20. **A minus sign and "<1%" are wrapped in bidi isolates (LRI…PDI).**
    - R: U+2212 and "<" are neutral characters. Under an RTL paragraph they moved to the wrong end
      ("1,000−", "1%>").
21. **Axis hours use the locale's best "h" or "Hm" pattern ("12 PM", "14").** The bracket label keeps
    the full clock.
    - R: "h:mm a" labels collided and were dropped, leaving three labels on an English axis.
22. **The Display unit is `text-secondary`.**
    - R: DESIGN.md says so. The lab's `.pct` was `text-primary`, and DESIGN.md is the locked
      record.
23. **The error state's retry is an outlined button.**
    - R: it is the only action on the screen. A text button read as a footnote.
24. **Live current switches to A at 1 A, in its head and on its axis.** The axis names its unit once,
    on the zero tick.
    - R: SPEC 6.7 rounds current to 0.01 A above 1 A. One unit per axis keeps its ticks comparable.
25. **One `BatteryReading` per sample.** The repository reads the battery, the discharge, the level
    line and the live window in one pass.
    - R: four flows each emitted per sample, so Now recomposed up to four times every 5 s and could
      pair a new reading with an old window. SPEC 0.2 ranks the self-drain budget above the
      functional spec.
26. **Prose breaks its lines over the whole paragraph.** Body and Caption use `LineBreak.Paragraph`;
    Title and Headline use `LineBreak.Heading`.
    - R: DESIGN.md asks prose to wrap "pretty". Android offers no orphan control, so this is the
      closest platform setting.
27. **A clock time names its weekday once it leaves today ("Mon 9:30 PM").** A midnight tick on the
    scale shows the weekday instead of "12 AM". This covers the bracket label, the scale summary,
    the last-discharge caption and the level table.
    - R: an estimate 31 to 59 hours out read "9:30 PM–1:20 AM", which looks like tonight, and the
      axis showed "12 PM" twice. The pattern is the locale's best "EEE h:mm" or "EEE H:mm".
28. **The deep-sleep share waits for 15 minutes of screen-off time and carries a quality score.**
    The score is the sessions' time-weighted completeness (a half for one cut short by a reboot or
    a gap), times the window's length up to an hour. Below 0.5 the share says "Low confidence".
    - R: SPEC 6.7 gives every metric a quality score built from sample count and window length, and
      SPEC 6.2 shows a rate only after 15 minutes. Before this change a minute of screen-off time
      gave a confident share. The independent review found that completeness alone never falls
      below 0.5, so the window length is part of the score.
29. **A drain row moves its value under the name when the name would get less than 7.5 em.** The
    value stays at the end, so figures still line up down the list.
    - R: at 200% text a six-digit figure left the name about 133 dp, narrower than "Application".
      DESIGN.md forbids breaking a word.
30. **Sections are 24 dp apart on compact widths and 32 dp from medium up (`currentSectionGap()`).**
    - R: SPEC 11.5 and ADR-0025. Now hard-coded 24 dp at every width.
31. **The live current fills charging (above zero) with copper at 45%.** Discharge (below zero)
    keeps the 14% neutral tint. This replaces the critique's "no change" on the live tint.
    - R: SPEC 11.4 says charging uses the accent, and DESIGN.md sets charge current at 45% of
      `accent` (lab `.cur-in`). The 14% tint stays for discharge, as the lab's Now chart drew it.
32. **Figures use the locale of the language the strings resolved to.** That is the user's first
    locale in that language, which keeps its region, or the language alone. Chart labels are laid
    out on one line, never wrapped.
    - R: on an Arabic system without Arabic strings, the English words sat beside Arabic-Indic
      digits. Android under-measured those lines in a right-to-left layout and broke them mid-word
      ("−١٠ mi/n"). A figure in another script than its sentence also reads poorly. Each
      translation names its language in `format_language`.
33. **Time left is one translatable sentence ("%1$s left").** The range is set in Title inside it,
    and the mark sits inline.
    - R: "left" was a separate fragment after the range, so a language with another word order
      could not translate it.
34. **Before calibration the hero says "Discharging · Calibrating".** Plugged in but still draining,
    it says "Discharging while plugged in".
    - R: SPEC 6.1 shows "Calibrating" instead of a current until calibration completes.
      `BATTERY_STATUS_DISCHARGING` on a charger means the charger is too weak for the load, which
      "Not charging" misstates.
35. **The screen fades in once per saved state, with `CompositingStrategy.ModulateAlpha`.**
    - R: the fade replayed on every tab return, rotation and theme change, while the charts kept
      their saved draw-in. The modulated alpha avoids an offscreen buffer the size of the screen.
36. **In Top drains the issue comes first, then the captions 4 dp apart, then the rows.**
    - R: the captions explain the rows, so they sit right above them, at an even spacing.
