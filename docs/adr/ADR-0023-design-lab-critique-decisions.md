# ADR-0023: Decisions from the design lab critique

- Status: accepted (edits SPEC 6.7, 10.1, 10.3, 10.6, 10.9, 11.3, 11.4, 11.8 and 14.1; supersedes ADR-0022 decision 3)
- Date: 2026-11-07
- Phase: P3

## Context

The P3 critique (SPEC 11.12, ADR-0022 §6) reviewed the seven lab screens in two independent passes:
- A: design review against Nielsen's heuristics, with five personas;
- B: detector and browser evidence.

B found nothing to fix. A found one Major issue (P1) and a set of Minor ones (P2). The per-screen files are `docs/design-gates/2026-11-07-critique-<screen>.md`.

Most fixes stayed inside the lab. These did not, because each one changes what SPEC says or settles something it leaves open:

- **Provenance of model outputs.**
  - Health marked Android's state of health and the fuel gauge's capacity as Measured (ADR-0022 §3).
  - A pointed out that both are estimates made by a model, and Coulomb's own estimate beside them is marked Estimated.
  - So the system's figure looked more certain than Coulomb's, for no reason.
- **Reading the marks.** Four provenance shapes, with no key on screen. SPEC 10.1 explains a mark only on long-press, which people who just want the verdict never find.
- **Dense events.** At 24 h, a 5-minute alarm loop draws ticks about 1 px apart. They merge into a solid band that reads as "awake continuously". That is the opposite of what the data says. A rated it P1 (measurement honesty, SPEC 0.2 rank 2).
- **Window selector.** SPEC 10.3 lists six windows. At 412 dp, three of them are clipped or off screen.
- **Type roles.** The lab used 14 px in ten places and a 16/22 subhead, neither of which is in SPEC 11.3. Button labels and Display units had no stated weight or size.
- **Accent on controls.** The checked switch used `accentFill`, which reaches only 1.39:1 against `bg` in light mode. The navigation pill holds an icon, not text, so it falls outside ADR-0022's "fills that carry text".
- **Ring as a chart marker.** Health drew each capacity window as a ring, which is the Modeled mark.

## Decision

### 1. Measured and Modeled (SPEC 6.7)

| Provenance | Meaning | Examples |
| --- | --- | --- |
| Measured | A direct counter, read from the hardware, the kernel or the framework | Coulomb delta, power rail energy, cycle count, wakelock time, network bytes |
| Modeled | The output of a model Coulomb does not own | Per-app mAh from Android's power model; the fuel gauge's level, state of health and capacity; Android's state of health |
| Estimated | Coulomb's statistical estimate (unchanged) | Capacity, time remaining, wear stress |
| Derived | Arithmetic on the above (unchanged) | Scaled-to-measured app share |

- Even the battery level is Modeled: the fuel gauge computes it from voltage, current and its own cell model.
- A Modeled value still names its source beside it ("Android", "Battery fuel gauge"), as ADR-0022 §3 required.
- This supersedes ADR-0022 §3. Values Coulomb reads unchanged are not automatically Measured; what counts is whether the value is a count or a model's output.

### 2. Teaching and explaining the marks (SPEC 10.1, 14.1)

- The last onboarding step teaches the four marks once, in a short key with one line per mark.
- Long-press on a figure still explains its mark. A tap is not used, because the rows already take the tap for navigation.
- Every figure with a mark gets a TalkBack custom action, "Explain provenance".
- Coverage is uniform. Where a table or chart has one source, a single mark on its header covers every value in it, as "Wear stress by session" already does.

### 3. Type roles (SPEC 11.3)

The scale keeps its eight roles. What changes is which weights each role allows, and two placements:

| Role | Size / line height | Weights |
| --- | --- | --- |
| Display numeral | 56 / 60 | 600; its unit is set at Headline size (28 / 34), weight 400 |
| Title | 20 / 26 | 600; also the onboarding title on compact widths |
| Body | 16 / 24 | 400 for prose; 500 for row names; 600 for subheads and card titles |
| Data | 15 / 20 | 500; a bold lead word in a Data line uses 600 |
| Label | 13 / 18 | 500 for column headers, chips and selection controls; 600 for buttons and text buttons |
| Caption | 12 / 16 | 400; navigation bar labels use 500, as Material 3 does |

- No other size exists. In particular 14 sp is not a role.
- Secondary notes under figures and lists use Caption. Switch labels and definition lists use Body.

### 4. Drain window selector (SPEC 10.3)

- Three presets: Since unplugged · Today · Last 24 h.
- A **More** button opens a sheet with Last night (screen off), Custom range and Compare two periods.
- "Reference vs reference" is renamed **Compare two periods**.
- The presets form a radio group. More sits outside it, because it opens a sheet rather than selecting a window.

### 5. "View as table" (SPEC 11.8)

- Every evidence chart has a visible "View as table" text button under its caption.
- Every chart, including the hero scales, has a semantics custom action with the same name.

### 6. Dense events (SPEC 10.6, 11.8)

- When event marks on a track would sit closer than 3 dp, the run is drawn as a **density span**. A span is a 20% tint of the track's colour, with a 1 dp stroke in the same colour.
- The chart caption or the readout gives the rate (for example "12 wakeups an hour").
- Individual ticks appear again once zooming spreads them 3 dp or more apart.
- The stroke meets 3:1 against the track background. Measured on the lab tokens it is 3.10 to 5.95:1, the lowest being the light-mode mobile-radio hue.

### 7. Switches and `accentFill` (SPEC 11.4)

- Switches use neutral ink:
  - checked: the track is `textPrimary` and the thumb `bg` (16.0 to 17.6:1);
  - unchecked: the outline is `textSecondary`.
- `accentFill` is for **fills that carry text or an icon**: filled button, selected chip or segment, navigation pill, text selection.
- The Info severity chip is neutral: `textPrimary` on `surfaceRaised` (14.5:1 light, 13.4:1 dark).

### 8. Insight cards (SPEC 9.4, no change)

- Each card has an overflow button in its head, with Snooze and Dismiss.
- Each card has "Why am I seeing this?".
- The primary button opens the SPEC 13 action preview sheet in place. It never only navigates.
- SPEC 9.4 already requires all three; the lab now shows them.

### 9. Charging phase (SPEC 10.9)

- The hero names the phase in plain words, such as "full speed" or "slowing down near full".
- The terms constant current and constant voltage stay in the glossary (SPEC 19.3) and the Evidence layer.

### 10. Chart markers (SPEC 11.8)

- The four provenance shapes are reserved: solid dot, ring, bracket and half dot.
- Chart markers use other shapes. For example, Health draws each qualifying window as a cross.

### 11. Onboarding step 4 (SPEC 14.1)

The step reads in this order:
1. The capability summary.
2. "Keep the monitor running" (14.2), when the OEM needs it.
3. The grants checklist, saying that the grants stay after Shizuku stops or the phone restarts.
4. The key to the marks.

The footer button is "Show my battery", with one line saying when the first figures appear.

### 12. No change needed

- **Charging's accent.** It is compliant with ADR-0022 §2: the hero numeral is the one emphasis, and the wavy level bar is a chart encoding.
- **Action Center icon.** It becomes `fact_check`, because `history` read as Timeline. SPEC names no icon, so no SPEC edit.

## Consequences

- SPEC changes:
  - 6.7 gains the definitions above;
  - 10.1 gains the TalkBack action and uniform coverage;
  - 10.3 gains the new selector;
  - 10.6 and 11.8 gain density spans, "View as table" and the reserved shapes;
  - 10.9 names the phase in plain words;
  - 11.3 gains the weights;
  - 11.4 gains the switch, `accentFill` and Info rules;
  - 14.1 gains the step order.
- The `Provenance` enum keeps its names, since they are stored by name. Its KDoc follows decision 1.
- The code already matches decision 1:
  - per-app power is `MODELED`;
  - framework counters (`WindowBuilder` stats) are `MEASURED`;
  - level-based drain is `ESTIMATED`.
- The level shown on Now and in widgets will carry the Modeled mark once the UI lands (P4).
- `DESIGN.md` records the type roles and colour rules when P3 locks the tokens, and the `:core:designsystem` parity test checks them.
- Reverting is a SPEC and token edit. No schema depends on it.
