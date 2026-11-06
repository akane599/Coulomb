# Fixtures: avd-pixel-api37 / 16571990

Captured 2026-11-06T04:14:01Z with `tools/fixtures/capture.sh` (read-only, SPEC 16.2).

| Property | Value |
| --- | --- |
| Model | Google sdk_gphone64_x86_64 |
| Fingerprint | `google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.051.A3/16571990:userdebug/dev-keys` |
| Android / SDK | 17 / 37 |
| Security patch | 2026-08-05 |
| Uptime at capture | 0.5 h |
| Battery level | ? % |
| Status / plugged | status ?, AC ?, USB ?, wireless ? |
| Screen | ? |

## Files

| File | Command | State | Bytes | stderr |
| --- | --- | --- | --- | --- |
| `batterystats.help.txt` | `adb shell dumpsys batterystats -h` | ok | 2210 |  |
| `batterystats.proto.bin` | `adb shell dumpsys batterystats --proto` | ok | 38126 |  |
| `batterystats.checkin.txt` | `adb shell dumpsys batterystats -c` | ok | 64655 |  |
| `batterystats.history.txt` | `adb shell dumpsys batterystats --history` | ok | 32452 |  |
| `alarm.txt` | `adb shell dumpsys alarm` | ok | 47847 |  |
| `deviceidle.txt` | `adb shell dumpsys deviceidle` | ok | 3040 |  |
| `power.txt` | `adb shell dumpsys power` | ok | 103319 |  |
| `jobscheduler.txt` | `adb shell dumpsys jobscheduler` | ok | 625075 |  |
| `suspend_control.txt` | `adb shell dumpsys suspend_control_internal` | UNSUPPORTED (exit 0, 0 B) | 0 | Can't find service: suspend_control_internal |
| `battery.txt` | `adb shell dumpsys battery` | ok | 673 |  |
| `thermal.txt` | `adb shell dumpsys thermalservice` | ok | 1004 |  |

`batterystats.checkin.txt` comes from `-c`, not `--checkin`: on API 36/37 `--checkin` deletes the
system's saved checkin file and dumps the previous cycle (ADR-0005).

## Redaction (ADR-0004)

Same-length masks keep offsets and protobuf framing valid. Replacements made: ssid: 1.
`probe/` holds the shell-level capability probe outputs used for `docs/capabilities/`.
