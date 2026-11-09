# Harden: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `harden`, native, in the same read-only agent run as `clarify`.
- Inputs:
  - PRODUCT.md, DESIGN.md;
  - SPEC 4.4–4.6, 10.11, 11 and 14.1–14.4;
  - ADR-0028 and ADR-0047;
  - the critique and audit records, the code, the strings in both languages and the goldens.

## Checked, nothing to change

- **Long text, 200% text and RTL:**
  - step titles, facts and capability rows wrap within their half of the row;
  - the footer buttons wrap instead of squeezing;
  - permission names and the ADB block stay left-to-right in Arabic.
- **Loading:**
  - Settings shows nothing until the stored values are read;
  - the matrix and the result step say they are checking.
- **Concurrency:**
  - busy buttons are disabled;
  - the store runs one probe at a time;
  - the catch-up bootstrap runs once.

## Findings and dispositions

| Sev | Screen | Finding | Disposition |
|---|---|---|---|
| Major | Onboarding | After a failed check the result step showed older stored results (from the start-up probe, before the grants) as if they were current | **Fixed.** A failed run always shows the failed head; stored results may predate this run's grants. |
| Major | Privileges | A helper that didn't start after Shizuku allowed Coulomb read "can't authorize apps on Android 17", with every ADB command beside a full checklist | **Fixed.** That case says "Shizuku can't start Coulomb's helper": Coulomb keeps working with the permissions it has, and a fork or Sui can start the helper. The ADB commands show only while a grant is missing (ADR-0047 gate amendment 1). `helper-timeout` now carries the issue. |
| Major | Privileges | "Permissions removed" outranks "Shizuku is stopped", but offered no button when the bootstrap couldn't run, the usual case after an update and a restart | **Fixed.** The issue offers the step the bootstrap waits for: Open Shizuku, or Allow Coulomb in Shizuku (`revoked-stopped`). |
| Minor | Privileges | "Some grants didn't go through. Use the ADB commands below." stayed after the grants arrived over ADB, when no commands are shown | **Fixed.** Only while a grant is missing. |
| Minor | Privileges | A failed first check read "results below are from before" above "Not checked yet." | **Fixed.** "The check failed. Re-run probes to try again." alone (`first-probe-failed`). |
| Minor | Privileges | The probe after a grant was skipped while another run was going | **Fixed.** It queues behind it on the store's lock. |
| Minor | Onboarding | A failed check offered no retry on the step | **Fixed.** "Try again" (`result-failed`). |
| Minor | Settings | A failed write only flipped the switch back | **Fixed.** "Couldn't save that change. Try again." as a polite live region, until a write goes through (`save-failed`). |
| Nit | Privileges | The uid took the locale's digits in Arabic beside Latin values | **Fixed.** Latin digits, like the other technical values. |

## Result

Every major and minor finding is fixed and checked in the goldens. The nit is fixed too.
