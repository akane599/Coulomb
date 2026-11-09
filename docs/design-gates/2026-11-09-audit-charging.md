# Audit: Charging (Compose, `:feature:charging`)

- Date: 2026-11-09, phase P4 (SPEC 11.12, 17).
- Method: impeccable 4.7.1 `audit` (native reference). One fresh read-only agent read the code, the
  strings, the data path and the goldens. No device was used.
- Score is the agent's, before the fixes below: **16/20, Good** (accessibility 3, performance 3,
  theming 4, platform conformance 3, adaptivity 3).

## Findings and dispositions

| Pri | Finding | Disposition |
|---|---|---|
| P1 | "Unplugged." flashes on open; a later charge can open on an earlier one's figures | **Fixed.** `ChargingRepository.now` is a cold flow; the model starts at Loading and returns to it once stopped (`replayExpirationMillis = 0`), so the skeleton shows until the first read. |
| P1 | The error state cannot be reached; a database exception in the shared flow would end the process | **Fixed** for Charging: the cold flow's exception reaches the model's `catch`, and "Try again" collects afresh. The same shared-flow pattern in `BatteryRepository` and `DrainRepository` is recorded in `docs/progress.md` for the whole-app pass. |
| P2 | The curve table is rebuilt every second while open | **Fixed.** Both tables are snapshots taken when the sheet opens, formatted once. |
| P2 | The scale's spoken text drops "estimated" | **Fixed.** "…estimated to be reached at …". |
| P2 | The "·" separator and the dashes are focus stops | **Fixed.** The separator has no semantics; the dashes became "Calibrating". |
| P2 | Turkish suffixes after placeholders | **Fixed** (see the critique). |
| P2 | Work each second: new actions object, curve mapping on the main thread | **Partly fixed.** The actions are remembered and `@Immutable`, so Target skips. The curve's `map` and the chart's thinning stay on the main thread: O(n) over at most a few thousand points, well under a millisecond per sample. |
| P3 | The repository copies the sample list and recomputes over all samples each second; a second query per sample | **Accepted.** O(n) on IO at 1 Hz only while the screen shows; the KDoc and ADR now say two queries. |
| P3 | Two stacked 5 s stop timeouts | **Fixed.** One, in the model. |
| P3 | Charger line built from pieces; the owner's name not isolated; "·" could start a line | **Fixed.** One resource, an FSI/PDI isolate around the name, a no-break space before "·". |
| P3 | Literal "%" in plain strings | **Fixed.** `formatted="false"` in both languages. |
| P3 | Overnight curve summary has no weekday | **Fixed.** The summary uses the same relative clock as "Since". |
| P3 | The hairline track is 1.37:1 | **Accepted.** The level is carried by the copper wave and the spoken description; the track is context. |
| P3 | One blank frame on cold start before "plugged" is known | **Accepted.** The first sample is read in a frame or two, and both screens show their skeletons after it. |

## Verified fine (agent)

- The 1 Hz flag follows the lifecycle: ON_RESUME after `MainActivity.onResume`, ON_PAUSE before `onPause`.
- Touch targets are 48 dp or more, and the target group is a radio group with its selected state.
- Contrast passes in light, dark and black. Text reflows to 200%. The scale mirrors by hand in RTL.
- `RollingText` respects reduced motion. The chart thins to 800 points and draws in once.

## Result

No P0. Both P1s are fixed and the P2s are fixed or reduced, with the reasons recorded above.
