# ADR-0054: The insight rules where the spec is silent

- Status: accepted (decided under ADR-0027 without a second opinion: rule code and one bundled
  JSON file, no stored format changes)
- Date: 2026-11-21
- Phase: P5

## Context

SPEC 9.2 gives each rule one line: what fires it, its severity and the action it offers. Writing
the 20 rules as code needed answers the table does not give. SPEC 0.3.5 says to take the more
accurate, cheaper, easier-to-revert option and record it. Every number below lives in
`insights.json` (SPEC 9.1), so changing one needs no code change.

## Decisions

1. **A rule returns a list of findings.** SPEC 9.1 writes `evaluate(ctx): InsightCandidate?`. A
   rule about apps can find several apps in one run, so the return type is a list. Device rules
   return at most one.
2. **Session rules are about the device, not about one session.** R03, R04 and R17 judge the latest
   session that qualifies and have no subject. R15 and R19 do the same for the latest charge and the
   latest window. Reason: a subject per session would open one card per bad night, and Now shows two
   insights at most (SPEC 10.2). The session's id is in the evidence, so the card still opens it.
3. **An open insight takes newer evidence.** When a rule finds its subject again while the insight
   is `NEW` or `SEEN`, the stored evidence is replaced and the state stays. Reason: with decision 2,
   a card would otherwise describe the first bad night for as long as it stays open. A snoozed or
   dismissed insight is not touched (SPEC 9.1, 9.4).
4. **An unknown input is never read as a finding.**
   - R15 needs battery protection to be off. Where the setting was not read, it finds nothing.
   - R16 needs the last launch. An app with no launch on record is skipped.
   - R17 needs three sessions known not to have reached deep idle. One unknown session stops it.
   - R04 needs a personal median. With fewer than 5 sessions in the 14 days before, it finds nothing.
5. **R09 counts every sensor except GNSS.** The spec says "wakeup sensor". The stored counters give
   sensor time per app and do not say which sensors are wakeup sensors. The copy says "a sensor",
   not "a wakeup sensor". Telling them apart is listed in `docs/questions.md`.
6. **Floors against noise.** Each keeps a rule from firing on a quantity too small to mean anything:

   | Rule | Floor | Why |
   | --- | --- | --- |
   | R04 | The judged night lost at least 3 level points | Fewer are within the gauge's rounding |
   | R05 | 1 h of screen-off time in the day | A rate over a few minutes swings widely |
   | R07 | 2 h of signal time | Same |
   | R11 | Readings more than 120 s apart count for no time | Nothing is known about the gap |
   | R14 | The two days compared are at least 7 days apart | One day's estimate moves by itself |
   | R18 | 10 mAh a day; 5 baseline days; whole days only | Twice a tiny median is still tiny; the day of the update mixes both versions |
   | R19 | 50 mAh measured | Below that the share is mostly rounding |
   | R20 | 20 h of data, scaled to 24 h | A short span is not "per day" |

7. **R08 judges the busier radio, by its busiest app.** Wi-Fi and Bluetooth scan times are not
   added: they can overlap, and the spec says "Wi-Fi or Bluetooth". Per-app scan timers overlap too,
   so the loader passes the busiest single app's time: the most that is certain. The exact radio time
   is in the history's scan flags; reading it there is a later refinement.
8. **Severity is the table's.** No rule raises its own severity yet. The lifecycle already handles a
   rise (SPEC 9.1), so a later threshold for it needs no structural change.
9. **R13 compares a new charger with the usual one.** A charge is matched to a charger profile
   only within 25% of its baseline (ADR-0043), so a charge below 70% of a baseline never matches
   and becomes a new profile. Such a session is compared with the profile of the same plug type
   seen last before it. A slower charger the user owns fires once and is then its own profile.
10. **Screen-off sessions count only inside one discharge.** On the charger the phone does not
    sleep, so a session that touches a charge is left out of R03, R04 and R17.
11. **Deep idle per session is read from the windows.** `Session.deepIdleReached` is not filled
    yet; the loader reads the device's `deep_doze_count` over the session's chain of windows.
12. **App rules look at installed apps only.** A UID below the first application UID (the system,
    the phone process, shared system UIDs) is left out of R01, R05, R06, R09, R10 and R18: their
    actions restrict an app, and the user cannot restrict the system.

## Consequences

- The rules are pure functions of `RuleContext`. What loads that context from the database is a
  separate step, and so are the collector additions some rules need (job stop reasons, the battery
  protection setting, standby buckets).
- Until those additions land, R15 and R16 find nothing and R10 sees job counts only. That is the
  honest result of decision 4, not a defect in the rules.
