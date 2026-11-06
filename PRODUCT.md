# Product

<!-- impeccable:product-schema 1 -->

Source: the owner's written brief, `SPEC.md` (sections 1, 2, 3, 9.3, 11.2, 14). SPEC 11.1 directs that
`/impeccable init` be answered from that document, so no separate interview was held.

## Platform

android

## Users

- **Android power users** who read wakelock names, kernel wakeup sources and `dumpsys` output, and want
  the evidence behind every number. They live in the Evidence and Raw layers.
- **People who just want the verdict**: what is draining the battery and what to do about it, readable
  in three seconds. They live in the Verdict and Story layers.
- Primary device: the owner's Galaxy S25 Ultra on One UI 9 (Android 17). Distribution is personal:
  signed APKs on GitHub Releases, tracked with Obtainium. Turkish and English from day one.

## Product Purpose

Coulomb answers one question within three seconds of opening: what is draining my battery, and what
should I do about it? Every answer is backed by measured or system-sourced data and carries an honest
confidence label. Success means: verdict on a cold start in under 800 ms (p50), capacity within ±3 %
after three qualifying charges, per-app attribution within ±1 point of the system, screen-off drain
within ±0.1 %/h, and Coulomb itself costing at most 0.3 % of capacity per day with zero screen-off wakeups.

## Positioning

A battery instrument, not a "battery booster". It reconciles the platform's modeled per-app power with
the fuel gauge's measured coulombs and shows the gap instead of hiding it; every metric carries its
provenance (Measured, Modeled, Estimated, Derived). It gets deep system data through Shizuku or Sui,
keeps working from durable grants when Shizuku stops, and never wakes the phone to measure it. It matches
GSam, BetterBatteryStats, AccuBattery, Battery Guru, BatStats and Batt feature for feature (SPEC 2) and adds
an on-device battery timeline and a deterministic plain-language Drain Story.

## Operating Context

- Opened in short glances: after a night (Night Report), when the battery fell faster than expected,
  while charging (the Charging screen takes over Now), and when an insight notification arrives.
- Four destinations: Now, Drain, Timeline, Health. Settings, Privileges and the Action Center open from
  the top app bar. Content follows four depth layers: Verdict, Story, Evidence, Raw, each one tap away.
- Runs on phones, foldables, tablets, DeX and external displays; split screen and freeform windows.
- Privileged access tiers: unprivileged (T0), durable grants (T1), Shizuku/Sui worker (T2), root (T3,
  optional). Every feature shows available, degraded (with reason) or unavailable (with fix).

## Capabilities and Constraints

- No network permission and no network-capable SDK, no analytics, no ads. Export is always user-initiated.
- Never wakes the device while the screen is off in its default mode; no wakeup alarms; wakelocks only
  while charging, at most 2 s.
- Every privileged write is previewed, logged with its previous value and revertible in one tap; system
  battery stats are never reset without two confirmations.
- Numbers never show more precision than the data supports (SPEC 6.7 rounding); estimates are shown as
  ranges with intervals.
- Product UI is Jetpack Compose with Material 3, adaptive across window sizes. Key screens are first
  prototyped as static HTML/CSS at 412 × 915 CSS px in `design-lab/` (SPEC 11.12) so the detector can run.
- Terminology: wakelock, kernel wakelock / wakeup source, wakeup reason, deep sleep, Doze (light, deep),
  standby bucket, `BatteryUsageStats`, coulomb counter, power rail, CC/CV, state of health, provenance
  (SPEC 19.3). Technical terms appear only in the Evidence layer, with a glossary tooltip.
- Decided by the owner (ADR-0007): the name is Coulomb; the icon comes from the design lab; the Wear OS tile
  is optional in P6 and never live; root-only charge control is out of v1 scope.

## Brand Commitments

- Name **Coulomb** (renamed from Joule), application id `io.github.akane599.coulomb`.
- Voice: **precise, calm, candid**. Specific, neutral, no blame words, no exclamation marks, never
  "optimize" or "boost". Sentence case. Errors say what happened and what to do next; buttons name the action.
- Drain Story sentences are template-generated, at most 22 words each, numbers locale-formatted with units
  (Turkish: "0,8 %/sa", "1.240 mAh").
- Anti-references (binding): neon "battery booster" apps, cleaner apps with fake scores, gamified badges,
  emoji in UI copy. References for feel, not to copy: lab instruments, well-set financial tables, railway
  timetables.
- Binding visual constraints already set by the owner live in SPEC 11 (typefaces, OKLCH color tokens with a
  single copper accent, spacing, critically damped motion, no idle animation). They are recorded there, not
  expanded here; new-work must honor them.

## Evidence on Hand

- Real redacted dumps from the S25 Ultra (One UI 9, build S938BXXSDDZJ1) and the API 37 emulator in
  `fixtures/`, with shell-level capability reports in `docs/capabilities/`.
- No accuracy results yet: protocols A–G (SPEC 16.6) have not run. Do not state accuracy, battery-cost or
  performance claims in any surface until their protocol passes.
- No testimonials, users, reviews, press or store listing exist; never fabricate them.

## Product Principles

1. Answer first, detail on demand: Verdict → Story → Evidence → Raw, always in that order.
2. Honest numbers: provenance on every metric, ranges for estimates, the unattributed gap shown when it matters.
3. Zero-cost observer: Coulomb must not cause the drain it measures.
4. Explain, then act: every insight links to a reversible action or says plainly that none exists.
5. Shizuku-native, never Shizuku-dependent: full power with it, clearly labelled degradation without it.

## Accessibility & Inclusion

- WCAG 2.2 AA contrast (4.5:1 text, 3:1 large text and UI parts); touch targets ≥ 48 dp.
- Legible at 200 % font scale with Android's non-linear scaling; layouts reflow, never clip.
- Color is never the only signal: direct labels on every color-coded item, charts readable without color
  (discharge below the axis), data palette checked for deuteranopia, protanopia and tritanopia.
- Every chart has a semantics summary and a "View as table" alternative; TalkBack script covers onboarding,
  Now, Drain and an action with revert. Pane titles and live regions instead of accessibility announcements.
- Reduced motion turns transitions into 150 ms cross-fades. Turkish and English; RTL sanity checked.
