# Critique: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `critique`, design review only. The detector does not apply to a native
  screen. One fresh read-only agent read:
  - SPEC 1.1–1.2, 3, 4.4–4.6, 10.1, 10.11, 11 and 14.1–14.4;
  - PRODUCT.md, DESIGN.md, ADR-0028 and ADR-0047;
  - the sources and strings in both languages, and 13 goldens.
- Scores are the agent's, on the screens before the fixes below.

## Scores

| Heuristic | Onboarding | Privileges | Settings |
|---|---|---|---|
| Visibility of system status | 2 | 2 | 3 |
| Match with the real world | 3 | 2 | 2 |
| User control and freedom | 4 | 3 | 4 |
| Consistency and standards | 2 | 2 | 3 |
| Error prevention | 3 | 3 | 3 |
| Recognition rather than recall | 3 | 3 | 3 |
| Flexibility and efficiency | 3 | 4 | 3 |
| Aesthetic and minimalist design | 3 | 3 | 4 |
| Error recovery | 2 | 2 | n/a |
| Help and documentation | 3 | 3 | 2 |
| **Total** | **28/40** | **27/40** | **27/36** |

**Strengths.**
- The result step's summary comes from the Capability Matrix, and the rails sentence ends on its
  own mark.
- Privileges is an evidence surface: each capability shows its probe key and reason, and the
  issue comes first with its fix in place.
- Settings states the cost of Component breakdown plainly.

## Findings and dispositions

| Sev | Screen | Finding | Disposition |
|---|---|---|---|
| Critical | Onboarding | A failed probe keeps "Coulomb can see where your battery goes" as its title | **Fixed.** Its own title, "Coulomb couldn't check this phone yet". The lede says when it retries. |
| Major | Both | App usage reads "Not granted" after the Settings route allows it, which turns on only the op | **Fixed.** App usage is the op (`GET_USAGE_STATS`). `PACKAGE_USAGE_STATS` joins DUMP under System service reports, which need both (DumpUtils). |
| Major | Onboarding | Changes of Shizuku stage are silent for TalkBack | **Fixed.** The stage container is a polite live region. |
| Major | Privileges | Fix hints from the stored probe contradict the live facts ("Needs Shizuku" while it runs) | **Fixed.** A fix the live state already meets reads "Changed since this check. Re-run probes to update it." (`FixAction.doneIn`). |
| Major | Privileges | After a failed run, stale rows still read "Available" in ink | **Fixed.** A stale matrix shows every state in `text-secondary`, under the failure note. |
| Major | Both | The basic-mode copy promises battery health, which every app can read | **Fixed.** "…per-app drain, wakelocks and kernel wakeups". |
| Major | Settings | "The alert comes with notifications" reads as an alert that is on | **Fixed.** "There is no alert at it yet", as on Charging. |
| Minor | Onboarding | The Android 17 line repeats the hang and missing-manager copy | **Fixed.** Shown only while checking, asking, waiting or stopped. |
| Minor | Onboarding | "Notifications" as the step title and the block heading | **Fixed.** The Shizuku path's step is "One permission first". |
| Minor | Onboarding | "…level and temperature and the current…" | **Fixed.** "the battery level with its temperature", "the battery current". |
| Minor | Both | Turkish buttons "edinin" | **Fixed.** "edin" on every button. |
| Minor | Privileges | The revoked copy talks about the screen it is on | **Fixed.** "Coulomb's permissions were removed. … Grant them again through Shizuku, or once over ADB." |
| Minor | Privileges | "through Shizuku" offered when Shizuku cannot grant | **Fixed.** An ADB-only sentence when the bootstrap cannot run. |
| Minor | Privileges | Busy buttons look tappable | **Fixed.** Disabled while busy (new `enabled` on the outlined and filled buttons). |
| Minor | Privileges | "Level" beside battery level | **Fixed.** "Access level", "Erişim düzeyi". |
| Minor | Settings | The scale note doesn't say what the switch does | **Fixed.** "Scales each app's modeled drain so the total matches the measured drop. …" |
| Minor | Settings | Notes are separate TalkBack items; rows crowd at 200% | **Fixed.** `CoulombSwitchRow` takes a note inside the toggleable row, with 8 dp padding. |
| Nit | Privileges | Turkish "bazında" against "başına"; "Kullanılamaz" against "Kullanılamıyor" | **Fixed.** "başına" and "Kullanılamıyor" throughout. |
| Nit | Privileges | "Needs root" under the helper group | **Fixed** in the fixture: the prober records such a source at the root tier. |
| Nit | Onboarding | One UI's Turkish "Hiç uyumayan uygulamalar" unverified | **Open** in `docs/questions.md`: to check on the phone. |
| Nit | Onboarding | "Step 1 of 4" before the path is chosen | **Accepted.** The Shizuku path is the default, and the count changes the moment basic is chosen. |
| Nit | Settings | No note on Show system apps | **Accepted.** The switch says what it does. |

## Result

The critical finding and all majors are fixed and verified in the goldens. The open nit waits for
the phone.
