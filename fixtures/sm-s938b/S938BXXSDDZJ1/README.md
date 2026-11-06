# Fixtures: sm-s938b / S938BXXSDDZJ1

Captured 2026-11-06T04:14:54Z with `tools/fixtures/capture.sh` (read-only, SPEC 16.2).

| Property | Value |
| --- | --- |
| Model | samsung SM-S938B |
| Fingerprint | `samsung/pa3qxtur/pa3q:17/CP2A.260605.016/S938BXXSDDZJ1_OXMDDZJ1:user/release-keys` |
| Android / SDK | 17 / 37 |
| Security patch | 2026-10-05 |
| Uptime at capture | 12.5 h |
| Battery level | ? % |
| Status / plugged | status ?, AC ?, USB ?, wireless ? |
| Screen | ? |

## Files

| File | Command | State | Bytes | stderr |
| --- | --- | --- | --- | --- |
| `batterystats.help.txt` | `adb shell dumpsys batterystats -h` | ok | 2210 |  |
| `batterystats.proto.bin` | `adb shell dumpsys batterystats --proto` | ok | 272968 |  |
| `batterystats.checkin.txt` | `adb shell dumpsys batterystats -c` | ok | 915716 |  |
| `batterystats.history.txt` | `adb shell dumpsys batterystats --history` | ok | 2358445 |  |
| `alarm.txt` | `adb shell dumpsys alarm` | ok | 288789 |  |
| `deviceidle.txt` | `adb shell dumpsys deviceidle` | ok | 23197 |  |
| `power.txt` | `adb shell dumpsys power` | ok | 102889 |  |
| `jobscheduler.txt` | `adb shell dumpsys jobscheduler` | ok | 2293392 |  |
| `suspend_control.txt` | `adb shell dumpsys suspend_control_internal` | UNSUPPORTED (exit 0, 0 B) | 0 | Can't find service: suspend_control_internal |
| `battery.txt` | `adb shell dumpsys battery` | ok | 11863 |  |
| `thermal.txt` | `adb shell dumpsys thermalservice` | ok | 1496 |  |

`batterystats.checkin.txt` comes from `-c`, not `--checkin`: on API 36/37 `--checkin` deletes the
system's saved checkin file and dumps the previous cycle (ADR-0005).

## Redaction (ADR-0004)

Same-length masks keep offsets and protobuf framing valid. Replacements made: wifi-ip: 1 (in-place pass), account-name: 13, battery-qr: 1, email: 16, ssid: 1.
Third-party package names replaced by consistent pseudonyms (ADR-0008): 157 packages, 5681 occurrences; UIDs unchanged.
`probe/` holds the shell-level capability probe outputs used for `docs/capabilities/`.
