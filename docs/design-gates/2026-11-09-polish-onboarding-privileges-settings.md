# Polish: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `polish`, an independent review of the code and strings in both languages
  and of all 53 goldens at full size. It ran after the other seven gates; this is the last one.

## What holds

- **Layout:** one gutter (16 dp compact, 24 dp wide) and a 720 dp column. On wide windows the
  onboarding footer ends with the column; the Privileges columns centre at 1200 dp, the matrix lined
  up with Access.
- **Copper:** only on one filled button per region and on the chosen target chip. Switches are ink,
  granted checks `good`. Dark matches light on every screen.
- **Stepper:** as DESIGN.md has it (4 dp segments 4 dp apart, label 8 dp below), mirrored in RTL, as
  are the back arrows and switch thumbs.
- **Rows:** Fact and capability rows align by baseline; mono values and the uid stay left to right in
  Arabic.
- **200% text:** titles, checklist, matrix, footer buttons and the choice group wrap; nothing clips.
- **States:** every SPEC 4.5 issue state, the busy state and the failed state has a golden. Title for
  sections, Body subhead for blocks, Caption for notes, throughout.
- **Copy:** no exclamation marks and no Turkish suffix after a placeholder.

## Findings and dispositions

| # | Sev | Screen | Finding | Disposition |
|---|---|---|---|---|
| 1 | Major | All three | 35 Turkish strings used the formal "siz"; Now, Drain, Health and Charging use "sen". The shared privilege strings also show on other screens. | **Fixed.** Every Turkish string in onboarding, settings and `strings_privilege` rewritten to "sen" by hand, and Now's error body with them. |
| 2 | Minor | Onboarding | The denied block repeated the step's lede almost word for word. | **Fixed.** Onboarding's own body: allow when Shizuku asks again, or grant once over ADB with the commands below. |
| 3 | Minor | Privileges | The ADB block sat about 4 dp under the issue buttons; onboarding leaves 24 dp. | **Fixed.** 24 dp. |
| 4 | Minor | Privileges | 36 dp from the Capability Matrix title to its first group, more than the 24 dp between groups. | **Fixed.** `Subhead` takes a `top`; the first group sits at the head's gap unless a status note stands between. |
| 5 | Minor | Privileges | A wrapped Fact value sat at the row's end with its lines start-aligned, a ragged edge. | **Fixed.** Aligned to the layout's end. An absolute side, since `TextAlign.End` follows the text's own direction and would flip for Latin values in RTL. |
| 6 | Minor | Privileges | In RTL, wrapped evidence moved to the left edge while one-line evidence sat right. | **Fixed.** Full width, aligned to the layout's start. |
| 7 | Nit | Onboarding | A serial comma in the basic-mode body. | **Fixed.** |
| 8 | Nit | Shared | "Tekrar dene" where every other screen says "Yeniden dene". | **Fixed.** |
| 9 | Nit | All three | The buttons have no vertical padding, so at 200% the label nearly touches the border. | **Accepted for now.** It changes every screen's buttons; it goes with the whole-app pass at the P4 gate, with its own goldens. |
| 10 | Nit | Privileges | No golden showed the Re-run and Export buttons or "Report saved." under a full matrix. | **Fixed.** `exported` is captured at 3100 dp. |

## Result

The major finding and every minor are fixed; one nit waits for the whole-app pass with its reason.
23 goldens re-recorded; `tools/ci/check.sh all` passes.

## The eight gates for these screens

| Gate | Result | Record |
|---|---|---|
| critique | 28 / 27 / 27 (Onboarding / Privileges / Settings) | `2026-11-09-critique-onboarding-privileges-settings.md` |
| audit | 16/20 | `2026-11-09-audit-onboarding-privileges-settings.md` |
| harden | 3 majors fixed | `2026-11-09-harden-onboarding-privileges-settings.md` |
| clarify | minors fixed | `2026-11-09-clarify-onboarding-privileges-settings.md` |
| animate | minors fixed | `2026-11-09-animate-onboarding-privileges-settings.md` |
| adapt | 1 major fixed | `2026-11-09-adapt-onboarding-privileges-settings.md` |
| optimize | 1 major fixed | `2026-11-09-optimize-onboarding-privileges-settings.md` |
| polish | 1 major (Turkish register) fixed | this file |
