# ADR-0026: The P3 direction review

- Status: accepted (edits SPEC 10.4)
- Date: 2026-11-07
- Phase: P3

## Context

SPEC 17 makes one human review of the design direction the P3 gate. The owner reviewed the seven lab
screens, the finish review's open item, eleven critique questions with their defaults, and ADR-0022 to
ADR-0025. The review page was private; this ADR is the record.

## Decisions

### 1. The direction is approved

The bracket on a graduated scale, cool slate with one copper accent, the fourteen type roles and the
four provenance marks carry into P4 as `DESIGN.md` states them. ADR-0022 to ADR-0025 stand, including
ADR-0025's SPEC 11.4 and 11.5 edits.

### 2. Now's chart covers the same span as the verdict

- In the lab, Now's chart starts two hours back (13:00), but the verdict sentence covers the whole
  session since unplugged (5 h 12 min).
- In the port, the scale starts at the unplug that opens the "Since unplugged" window, so the line and
  the sentence describe the same span. The estimate bracket stays at the right end.
- When the level history does not reach back to the unplug, the axis still starts there and the missing
  span is a gap. Coulomb never draws a line it did not record.

This is a `DESIGN.md` rule (the signature section), not a SPEC change: SPEC 10.2 does not describe the
chart.

### 3. App detail leads with the open insight (SPEC 10.4)

The critique asked whether Actions should come before Evidence for an app rated Act. The default was
"no, evidence first". The owner chose a middle path:

- The story ends with its 9.3 anomaly sentence, which is the app's highest-severity open insight. For
  Halcyon in the lab: it wakes the phone every 5 minutes with the screen off, and its daily use has
  doubled since its 3 November update.
- When that insight is rated Act, its card follows the story directly. It is the same card as on Now,
  with its action button, which opens the SPEC 13 preview sheet in place (ADR-0023 §8).
- The evidence sections come next. The full Actions section stays last.

One insight has one state (SPEC 9.4), so snoozing or dismissing it on either screen removes it from
both.

### 4. The lab closes as it is

- The finish review ended at its two-round budget with disposition **fix**: seven fixes resolved, one
  partial (200% text). The owner chose to ship the lab and not to fund a third round.
- The 200% defects are fixed in P4, where every screen's screenshots run at font scale 2.0 (SPEC 11.12,
  ADR-0025 §3 items 1–3).
- The lab is not edited again; its differences from the port are listed in `DESIGN.md`.

### 5. The critique questions

The owner accepted ten defaults. The eleventh, Actions before Evidence on App detail, is answered by
§3.

| Screen | Question | Answer |
| --- | --- | --- |
| Now | Which carries the answer, the bracket or the sentence? | Both: the bracket says how long is left, the sentence says why |
| Now | Can the level appear without a mark? | No: it is the fuel gauge's output, so it carries Modeled (ADR-0023) |
| Drain | Is Unattributed an app row? | Both, kept apart: its own group under the apps, and the second segment of the reconciliation bar |
| App detail | Does the page say whether Coulomb's earlier action worked? | Not yet: it needs the Action Center's before-and-after log (SPEC 13) |
| App detail | Actions before Evidence for an app rated Act? | §3 |
| Health | Does the cross-check come before the trend? | Yes |
| Charging | Level or power in the Display role? | Power |
| Charging | Is the target tick draggable? | No: the 80/85/90/100% group sets it |
| Timeline | Is density the basic mark at 24 h? | Yes: ticks appear on zoom |
| Timeline | Does Timeline get a Display numeral? | No |
| Onboarding | Does the grants step say the grants stay after Shizuku stops? | Yes |

### 6. INTERACT_ACROSS_USERS

The owner asked why the onboarding step lists INTERACT_ACROSS_USERS, and to use a narrower path if one
exists. No narrower path keeps the same data at the same cost. ADR-0010's 2026-11-07 amendment records
the platform check, the paths ruled out, and the onboarding copy P4 uses.

## Consequences

- SPEC 10.4 gains the insight card between the story and the evidence.
- `DESIGN.md` gains the Now chart rule, and its list of lab differences gains the chart span and the
  App detail order.
- P4 builds Now's chart and App detail to §2 and §3. The per-screen critique checks both.
- The P3 gate passes: detector zero, critique and audit without major findings, and this review.
