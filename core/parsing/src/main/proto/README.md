# AOSP batterystats protos

Source: the Android Open Source Project, tag `android-17.0.0_r1` (Apache-2.0, credited in `NOTICE`).
The same files at `android-16.0.0_r4` differ only by enum values added in 17, so one set serves
API 36 and 37 (SPEC 5.1). Unknown enum values from either side decode without error.

The only change from the AOSP copies is an `option java_package` in each file, with a comment saying
so. `os/batteryusagestats.proto` (S4, ADR-0035) also drops its import of the `proto_logging` OS enums,
which none of its fields uses. The generated classes move into `io.github.akane599.coulomb.core.parsing.aosp.*`, because the
framework ships hidden classes such as `android.os.BatteryStatsProto` that would shadow them at
runtime.

To refresh: copy the files from both tags, diff them, re-apply the `java_package` lines and run the
golden tests.
