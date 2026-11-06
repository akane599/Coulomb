# Coulomb

Coulomb is a battery app for Android 16 and 17. It reads the phone's coulomb counter and the system's own
battery statistics to show where the charge went, how fast it drains and how healthy the battery is.
With [Shizuku](https://shizuku.rikka.app/) or a compatible manager it can read the system's deeper battery
dumps. Without one it falls back to a basic mode and says so.

Status: in development and not released yet. Progress by phase is in [`docs/progress.md`](docs/progress.md).

## No network access

Coulomb has no network access. It does not declare `android.permission.INTERNET`, and it has no analytics,
ads or crash reporting. Everything it measures stays on your devices. Export happens only when you ask for
it, through the system file picker.

The build enforces this. For every build type, a Gradle check (`verify*ManifestPermissions`, run in CI)
fails if the merged manifest contains `INTERNET` or any permission missing from
[`app/permissions-allowlist.txt`](app/permissions-allowlist.txt). It also fails if a library asks for
`INTERNET` on its own.

## Building

JDK 21 and the Android SDK (API 37) are required.

```bash
./gradlew :app:assembleDebug   # debug APK
tools/ci/check.sh all          # everything CI runs
```

The requirements are in [`SPEC.md`](SPEC.md); decisions are in [`docs/adr/`](docs/adr/).

## License

Coulomb is free software under the [GNU General Public License v3.0 or later](LICENSE)
(`GPL-3.0-or-later`). Each file states its license in an SPDX header or in [`REUSE.toml`](REUSE.toml),
following the [REUSE](https://reuse.software) specification. The Gradle wrapper is Apache-2.0. Protocol
buffer definitions taken from the Android Open Source Project keep their Apache-2.0 license and are
credited in `NOTICE` once they are added.
