# Optimize: Onboarding, Privileges and Settings (Compose)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `optimize`, in the run with `animate` and `adapt`.

## Checked, nothing to change

- `collectAsStateWithLifecycle` throughout.
- `WhileSubscribed(5 s)`; the root's onboarded flag is eager on purpose.
- Action holders are immutable and remembered.
- The time is read on resume.
- Probes and file writes run on IO.
- Strong skipping is on.

## Findings and dispositions

| Sev | Screen | Finding | Disposition |
|---|---|---|---|
| Major | Privileges | Until Room answered, the screen painted Basic access, every grant crossed and "Not checked yet": a made-up state | **Fixed.** Nothing shows until the stored matrix is read, then one fade-in, as Settings does. |
| Minor | Both | PackageManager calls on the main thread in composition (manager intent, label) | **Accepted.** They run inside a keyed `remember`, once per manager change, not per frame: a few local lookups. |
| Minor | Privileges | The matrix groups and the probe time were recomputed on every recomposition | **Fixed.** Computed once per state. **Accepted:** the evidence string per row is one short join. |
| Minor | Both | The stored matrix's JSON was parsed on the collector's thread, Main | **Fixed.** `flowOn(Dispatchers.Default)` in `CapabilityStore`. |
| Nit | Onboarding | The capability summary ran on every recomposition | **Fixed.** Remembered on the capabilities. |
| Nit | Privileges | The report was built on Main | **Fixed.** Built and written on IO. |

## Result

The major finding and every minor are fixed or accepted with their reason. The cold-start
Macrobenchmark is recorded in `docs/progress.md`.
