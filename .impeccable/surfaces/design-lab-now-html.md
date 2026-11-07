---
version: 1
slug: "design-lab-now-html"
primary_target: "design-lab/now.html"
related_targets: ["design-lab/drain.html","design-lab/app-detail.html","design-lab/timeline.html","design-lab/health.html","design-lab/charging.html","design-lab/onboarding.html"]
---

## Scope

Coulomb's design lab (SPEC 11.12): static HTML and CSS at 412 × 915 CSS px, light and dark, for seven
screens: Now (primary target), Drain (Apps lens), App detail, Timeline, Health, Charging and one
onboarding step. Mode: **Operate**. Build path: code-led (Operate surfaces build code-led; no image
generation). The lab locks tokens for the Compose port; it is not shipped.

## Audience, job, constraints

- People who want the verdict: open, read level, time left and the verdict in three seconds, leave.
- Power users: drill one tap into rows, tags, charts and raw identifiers.
- Content order per SPEC 10 (Verdict, Story, Evidence, Raw); SPEC 11 pins type, color, space, motion.
- Material 3 shell: top app bar, bottom navigation bar (Now, Drain, Timeline, Health), 48 dp targets.
  Cards only for an insight or a session; never nested.
- Data is synthetic and app names are invented.

## Direction contract

THESIS: A battery instrument read off graduated scales, where every estimate is drawn as a bracket
spanning its interval, never as a point. It refuses the category default: a ring gauge and a big
percentage on a colored card, with score tiles beneath.

OWN-WORLD: Cool slate grounds tinted to hue 255, divided by hairlines instead of cards. Hanken Grotesk
with tabular figures sets numbers like a timetable column; Spline Sans Mono appears only for raw
identifiers. One copper accent marks the reading that matters: the bracket, the charging power, the
selection. Data hues appear only on component marks, each with a direct label. Provenance uses four
drawn marks: solid dot (measured), ring (modeled), bracket (estimated), half dot (derived).

STORY: The visitor knows in three seconds how much is left and why. They believe it because every
figure shows its provenance and the gap between measured and modeled is visible. They tap one cause to
see its evidence.

FIRST VIEWPORT (Now, 412 × 915):
- status bar, then a small top app bar: "Now" on the left; Action Center, Privileges and Settings on the
  right;
- hero block of at least 30% of the viewport:
  - "74%" as the Display numeral on the left, with the Modeled mark (the fuel gauge models the level; ADR-0023);
  - to its right, "5 h 50 min – 7 h 20 min" over "left" and "Discharging · 410 mA";
  - below, a full-width clock-time scale from 13:00 to 00:00 (now is 14:20): the level line so far, a
    cone narrowing into a copper bracket that spans 20:10–21:40 on the axis;
- the verdict sentence (Body);
- the session strip: three timetable columns split by hairline rules;
- "Top drains": three ruled rows (icon, name, mAh, share bar);
- bottom navigation bar.

No action sits in the hero; actions live in the insight cards below the fold.

FORM: Code-led Material 3 Operate screens; position 1 of 1, because SPEC 11 pins the world and no
roll ran.
- Signature move: the bracket on a graduated scale. It carries time remaining (Now), the capacity
  interval (Health), and time to target beside the target tick (Charging).
- Signature interaction: scrubbing a chart shows a hairline crosshair and a readout in the Data style;
  values snap to samples.
- Motion grammar: critically damped springs, digits roll in place, charts draw in once, nothing idles.
- seed: none (unavailable)

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

## Open decisions

- Resolved in ADR-0022: light data hues at L 0.62 (3:1 on `bg`); `accentFill` for text-bearing
  fills; hue names a component, states use neutral ink; chart rules for sp text, edged fills and
  remainder rows.
- Resolved in ADR-0023 (from the critique): Measured means a direct counter and Modeled a model Coulomb
  does not own; marks taught at onboarding; type weights per role; Drain's three presets plus More;
  density spans for dense events; neutral switches; `accentFill` for text- or icon-bearing fills; the
  four provenance shapes reserved.
- Open for the P3 direction review: the critique questions in `docs/design-gates/2026-11-07-critique-*.md`.
