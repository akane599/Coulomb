# Clarify: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `clarify`, in the same read-only agent run as `harden` (inputs there).

## Checked, nothing to change

- No serial comma, including the list sentence the app builds.
- Turkish buttons use "edin", "ver" and "aç"; the body text uses "siz".
- No suffix follows a placeholder; no exclamation marks.
- The power-rails sentence carries its own mark.
- Stale fixes say "Changed since this check".

## Findings and dispositions

| Sev | Screen | Finding | Disposition |
|---|---|---|---|
| Major | Privileges | The helper-only hang copy (see harden) | **Fixed** there. |
| Minor | Both | Below Android 17, "Shizuku didn't answer" claimed a fork was needed for this Android version, and offered no retry | **Fixed.** "Open Shizuku and check that it is running, then try again. You can also grant access once over ADB.", with "Try again" (`hang-16` on both screens). The fork and Sui links stay for Android 17. |
| Minor | Both | One Android setting under three names (usage access, app usage access, allow app usage) | **Fixed.** "Usage access" / "Kullanım erişimi" throughout: the checklist row, the fix line, the Settings button and step 2. |
| Minor | Privileges | "Needs the grants above." is wrong in two columns, where Grants sits beside the matrix | **Fixed.** "Needs the permissions under Grants." |
| Nit | Privileges | "Version mismatch" and "it stopped repeatedly" didn't say when the helper comes back | **Fixed.** "Not available until Coulomb restarts: …" |

## Result

Every finding is fixed. The Turkish copy was reviewed with the English.
