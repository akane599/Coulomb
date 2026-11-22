# Automation API, version 1

For people who write automations against Coulomb (SPEC 12.7). The decisions behind it are in
`docs/adr/ADR-0065-automation-api.md`; the exact names and numbers are pinned in
`docs/automation-api-v1.json`. Version 1 only ever gains names. A change of meaning would be a
version 2 beside it.

Every name starts with the application id. For the released app that is
`io.github.akane599.coulomb`, used in the examples below.

## Before anything works

1. In Coulomb, turn on **Settings → Advanced → Automation API**. Until then the API's components
   are disabled and Android delivers nothing to them.
2. The calling app must hold the permission `io.github.akane599.coulomb.permission.AUTOMATE`. It
   is a runtime ("dangerous") permission that Coulomb defines: the app names it in its manifest
   with `<uses-permission>`, asks for it, and you allow it. Coulomb's Settings lists the apps you
   have allowed.
3. Root does not need the permission. The adb shell user cannot hold it and is refused.

An automation app whose manifest does not name the permission cannot be granted it. With root you
can still call the API from such an app's shell action, as in the examples.

## Actions

Send an **ordered** broadcast with Coulomb's package set. The answer comes back only as the
broadcast's result; an unordered broadcast is carried out and gets no answer.

| Action | Extra | What it does |
| --- | --- | --- |
| `io.github.akane599.coulomb.action.SNAPSHOT_NOW` | none | Takes a snapshot, as pull-to-refresh does. |
| `io.github.akane599.coulomb.action.SET_CHARGE_TARGET` | `io.github.akane599.coulomb.extra.CHARGE_TARGET_PCT`: int 50–100, or the same as text | Sets the charge target of Coulomb's charge alarm. |
| `io.github.akane599.coulomb.action.APPLY_DOZE_PROFILE` | `io.github.akane599.coulomb.extra.DOZE_PROFILE`: `stock`, `balanced` or `aggressive` | Re-applies a Doze profile you have applied in the Action Center, or returns to stock. |

### The answer

| Result code | Meaning |
| --- | --- |
| 1 | Done, and for a change to the phone, read back. |
| 2 | Accepted: the snapshot was taken, unless one was taken in the last minute. The answer waits for it, up to six seconds, so the figures you read next are new. A snapshot that fails answers 13 (`failed`); one not done within the six seconds answers 14 (`timeout`), and is still finished. |
| 3 | Unchanged: it was already so. |
| 10 | Invalid: an extra is missing or outside its range. Nothing was changed. |
| 11 | Rate limited. The result extra `io.github.akane599.coulomb.extra.RETRY_AFTER_S` (long) says how many seconds to wait. |
| 12 | Blocked. The result data says why. |
| 13 | Failed. |
| 14 | Unverified: the command ran and the value could not be read back. |

A result code of 0 means nobody answered: the API is off, or the sender does not hold the
permission. The result data is a reason word, and the result extra
`io.github.akane599.coulomb.extra.API_VERSION` (int) is 1.

| Reason | When |
| --- | --- |
| `ok` | With codes 1, 2 and 3. |
| `off` | The Automation API was switched off while the call was on its way. |
| `bad_target`, `bad_profile` | The extra is missing or not one of the values above. |
| `rate_limited` | See the limits below. |
| `screen_off` | A snapshot was asked for with the screen off and the phone on battery. |
| `needs_first_apply` | You have not applied this profile yourself, from the values the phone holds now. |
| `profile_active` | Another profile is applied. Send `stock` first. |
| `action_center_off` | The Action Center is off in Coulomb. |
| `drifted` | Something else changed the Doze setting since Coulomb set it, so Coulomb leaves it. |
| `failed`, `unverified` | With codes 13 and 14. |
| `timeout` | With code 14: the answer was due before the work ended. Coulomb's action log has the outcome. |
| `no_worker`, `unreadable`, `managed_externally`, `malformed`, `nothing_to_change`, `in_force`, `not_active` | The Action Center's own reason for not applying or reverting a profile, as its screen would give it. |

### Limits

- A snapshot is refused while the screen is off and the phone is on battery. At most four are
  accepted in any two hours, and Coulomb takes at most one snapshot a minute whoever asks.
- `APPLY_DOZE_PROFILE` never does what you have not done yourself: apply the profile once in
  Coulomb's Action Center, and from then on a call can repeat exactly that change.
- More than ten calls in a minute are refused, and so is anything beyond 100 logged calls a day.
- Every call that is carried out or refused for its content is in Coulomb's action log, marked as
  asked for through the Automation API. Coulomb stores the call's name, your package where you
  shared it, and values that are the API's own words or numbers; text of your own is not kept.
- A call is answered within eight seconds. Extras that cannot be read get no answer.

### Examples (root shell)

```sh
am broadcast -p io.github.akane599.coulomb \
  -a io.github.akane599.coulomb.action.SNAPSHOT_NOW

am broadcast -p io.github.akane599.coulomb \
  -a io.github.akane599.coulomb.action.SET_CHARGE_TARGET \
  --ei io.github.akane599.coulomb.extra.CHARGE_TARGET_PCT 80

am broadcast -p io.github.akane599.coulomb \
  -a io.github.akane599.coulomb.action.APPLY_DOZE_PROFILE \
  --es io.github.akane599.coulomb.extra.DOZE_PROFILE balanced
```

`am broadcast` prints the result code and data. From an app, use
`sendOrderedBroadcast` with a result receiver; to have your package named in Coulomb's log, send it
with `BroadcastOptions.setShareIdentityEnabled(true)`.

## Reading

`content://io.github.akane599.coulomb.automation/v1/<path>`, read-only, same permission.

| Path | Rows (`metric`) |
| --- | --- |
| `v1/info` | `api_version`, `app_version`, `monitoring_mode` (`full` or `lite`) |
| `v1/level` | `level`, `power_state` (`on_battery`, `charging`, `full`, `plugged`) |
| `v1/rates` | `rate_screen_on`, `rate_screen_off`, `time_left` |
| `v1/night` | `duration`, `level_start`, `level_end`, `rate`, `deep_sleep`, `deep_doze_reached`, `deep_doze_after` |
| `v1/drains` | three `drain_share` rows, `rank` 1 to 3 |

Every path answers with the same columns, one row per figure, always in this order. Projection,
selection and sort order are ignored.

| Column | Content |
| --- | --- |
| `metric` | The figure's name. |
| `rank` | 1 to 3 for the drains, 0 elsewhere. |
| `subject` | Null in version 1; kept for a later one. |
| `subject_label` | For a drain: the app's name. Null for an app of another user or profile. |
| `value` | The figure, as a number. |
| `low`, `high` | For a figure that is a range (`time_left`); `value` is null then. |
| `text` | For a figure that is a word (`power_state`, `deep_doze_reached`: `yes` or `no`). |
| `unit` | `pct`, `pct_per_h`, `min`, or `pct_of_window` (a drain's share of everything used since the phone was unplugged). |
| `provenance` | `measured`, `modeled`, `derived` or `estimated`: how Coulomb knows it. |
| `quality` | 0 to 1 where the figure has one; below 0.5 Coulomb marks it as low confidence. |
| `state` | `ok`, `collecting` (not enough recorded yet), `unavailable`, or `not_applicable` (a drain rate on the charger). |
| `from_wall_ms`, `to_wall_ms` | The span the figure covers, where it has one. |
| `as_of_wall_ms` | When Coulomb read it. Figures are as of Coulomb's last reading, not of your query. |

A figure Coulomb does not have is still a row, with a null `value` and a `state` other than `ok`.
Check `state` before using a number. The figures are the ones Coulomb's own screens show, rounded
the same way.

From a root shell:

```sh
content query --uri content://io.github.akane599.coulomb.automation/v1/level
```

## Not checked on a device yet

The API is tested against stand-ins. Calls from a real automation app, from root and from the
shell have not been run on a phone; `docs/questions.md` has the list.
