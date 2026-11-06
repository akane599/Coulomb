#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Shell-level capability report (SPEC 4.6) from a redacted fixture directory.

    python3 -I tools/capabilities/report.py fixtures/<device>/<build> docs/capabilities/<device>-<build>.md

The probes ran as the adb shell user (uid 2000), the same identity as the T2 Shizuku worker, so
this is what T2 will see. Probes that need Coulomb's own process (BatteryManager properties,
SystemHealthManager, NotificationManager) are listed as pending until the in-app suite (P1).
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

PENDING = "PENDING"


def read(p: Path) -> str:
    return p.read_text(errors="replace") if p.exists() else ""


def main() -> int:
    fx, out = Path(sys.argv[1]), Path(sys.argv[2])
    status = {}
    for line in read(fx / "status.tsv").splitlines()[1:]:
        f, cmd, rc, size, err = (line.split("\t") + [""] * 5)[:5]
        status[f] = (cmd, int(rc or 1), int(size or 0), err.strip())
    props = read(fx / "probe/getprop.txt")

    def prop(name: str) -> str:
        m = re.search(r"\[" + re.escape(name) + r"\]: \[([^\]]*)\]", props)
        return m.group(1) if m else "?"

    def ok(f: str) -> bool:
        s = status.get(f)
        return bool(s) and s[1] == 0 and s[2] > 0 and "Can't find service" not in read(fx / f)[:200]

    probed_at = read(fx / "captured_at.txt").strip()
    rows: list[tuple[str, str, str, str]] = []

    def add(cap: str, state: str, tier: str, reason: str) -> None:
        rows.append((cap, state, tier, reason))

    # batterystats flags
    help_text = read(fx / "batterystats.help.txt")
    flags = sorted(set(re.findall(r"(?m)^\s*(--?[a-z][a-z0-9-]*)", help_text)))
    add("`dumpsys batterystats` flags (`-h`)", "AVAILABLE" if flags else "UNAVAILABLE", "T2",
        ", ".join(f"`{f}`" for f in flags) or "help output empty")
    for f, cap, src in (
        ("batterystats.proto.bin", "Proto output (`--proto`)", "S5"),
        ("batterystats.checkin.txt", "Checkin text (`-c`, ADR-0005)", "S6"),
        ("batterystats.history.txt", "Battery history (`--history`)", "S7"),
        ("alarm.txt", "Alarm dump", "S10"),
        ("deviceidle.txt", "Doze state dump", "S11"),
        ("power.txt", "Power dump (live wakelocks)", "S12"),
        ("jobscheduler.txt", "Job scheduler dump", "S13"),
        ("thermal.txt", "Thermal service dump", "S19"),
        ("battery.txt", "Battery service dump", "S21"),
    ):
        s = status.get(f)
        if ok(f):
            add(f"{src} {cap}", "AVAILABLE", "T2", f"{s[2]:,} bytes")
        else:
            add(f"{src} {cap}", "UNAVAILABLE", "T2", f"exit {s[1] if s else '?'}: {s[3] if s else 'not run'}")

    # Kernel wakelocks without root
    sc = read(fx / "suspend_control.txt")
    if ok("suspend_control.txt"):
        n = len(re.findall(r"(?im)^\s*(?:wakelock|name)", sc))
        add("S8 Kernel wakelocks via `dumpsys suspend_control_internal`", "AVAILABLE", "T2",
            f"{status['suspend_control.txt'][2]:,} bytes; {n} name/wakelock lines")
    else:
        s = status.get("suspend_control.txt")
        add("S8 Kernel wakelocks via `dumpsys suspend_control_internal`", "UNAVAILABLE", "T2",
            f"{s[3] if s else 'not run'}; S9 sysfs (root) is the fallback")

    wake = read(fx / "probe/wakeup.txt")
    count = re.search(r"count=(\d+)", wake)
    n_wake = int(count.group(1)) if count else 0
    if "denied" in wake.lower():
        add("S9 `/sys/class/wakeup`", "UNAVAILABLE", "T3",
            f"SELinux denies the shell user ({n_wake} entries listed); needs root (T3)")
    else:
        add("S9 `/sys/class/wakeup`", "AVAILABLE" if n_wake else "UNAVAILABLE", "T2", f"{n_wake} wakeup sources readable at shell level")

    # Power monitors
    ps = read(fx / "probe/powerstats.txt")
    if ok("probe/powerstats.txt"):
        channels = re.findall(r"ChannelName:\s*([^,\s]+)", ps)
        add("S16 On-device power rails (`dumpsys powerstats`)", "AVAILABLE" if channels else "DEGRADED", "T2",
            (f"{len(channels)} channels: " + ", ".join(f"`{c}`" for c in channels[:12]) + ("…" if len(channels) > 12 else ""))
            if channels else "service present, no channel list parsed; confirm with SystemHealthManager in P1")
    else:
        listed = bool(re.search(r"(?m)^\s*powerstats\s*$", read(fx / "probe/dumpsys-services.txt")))
        add("S16 On-device power rails (`dumpsys powerstats`)", "UNAVAILABLE", "T2",
            ("service is registered but its dump is empty: no rails exposed" if listed else "service not registered")
            + "; confirm with SystemHealthManager in P1")

    # sysfs power_supply
    values = read(fx / "probe/power_supply-values.txt").splitlines()
    readable = [l for l in values if "=" in l and "denied" not in l.lower() and "<error>" not in l]
    denied_n = len(values) - len(readable)
    nodes = sorted({m.group(1) for l in values if (m := re.match(r"/sys/class/power_supply/([^/]+)/", l))})
    ls_err = status.get("probe/power_supply-ls.txt", ("", 0, 0, ""))[3]
    if "denied" in ls_err.lower():
        add("S23 `/sys/class/power_supply`", "UNAVAILABLE", "T3", "SELinux denies listing the directory to the shell user; needs root (T3)")
    else:
        add("S23 `/sys/class/power_supply` reads at shell level",
            "AVAILABLE" if readable else "UNAVAILABLE", "T2",
            f"nodes: {', '.join(f'`{n}`' for n in nodes) or 'none'}; {len(readable)} readable, {denied_n} denied")

    def sysfs(name: str) -> str | None:
        for l in readable:
            if l.startswith(f"/sys/class/power_supply/battery/{name}="):
                return l.split("=", 1)[1].strip()
        return None

    cur, volt, cc = sysfs("current_now"), sysfs("voltage_now"), sysfs("charge_counter")
    add("`CURRENT_NOW` sanity (sysfs view)", "AVAILABLE" if cur else PENDING, "T2",
        f"current_now={cur}, voltage_now={volt}, charge_counter={cc}; unit and sign calibration per SPEC 6.1 runs in-app"
        if cur else "sysfs not readable at shell; BatteryManager probe in P1")
    for key in ("cycle_count", "charge_full", "charge_full_design", "health", "batt_capacity_max"):
        v = sysfs(key)
        if v is not None:
            add(f"sysfs `battery/{key}`", "AVAILABLE", "T2", v)

    # Samsung settings keys
    sk = [l for l in read(fx / "probe/settings-battery.txt").splitlines() if l.strip()]
    protect = [l for l in sk if re.search(r"protect", l, re.I)]
    add("S22 Battery-related settings keys", "AVAILABLE" if sk else "UNAVAILABLE", "T2",
        f"{len(sk)} keys matched; protection candidates: " + (", ".join(f"`{l}`" for l in protect[:8]) or "none"))

    # In-app probes
    for cap in (
        "S2 BatteryManager counters (`CURRENT_NOW`, `CHARGE_COUNTER`, ...)",
        "S3 Health properties (state of health, manufacture/first-use date)",
        "S16 `SystemHealthManager.getSupportedPowerMonitors`",
        "Live Update promotion (`canPostPromotedNotifications`)",
        "Unknown-field counts per parser",
    ):
        add(cap, PENDING, "T0/T1", "needs Coulomb's own process: in-app probe suite (P1) or parsers (P2)")

    services = read(fx / "probe/dumpsys-services.txt")
    wanted = ["batterystats", "battery", "alarm", "deviceidle", "power", "jobscheduler", "thermalservice",
              "suspend_control_internal", "powerstats", "usagestats", "netstats", "batteryproperties"]
    present = {w: bool(re.search(rf"(?m)^\s*{re.escape(w)}\s*$", services)) for w in wanted}

    md = [
        f"# Capability report: {prop('ro.product.model')} / {prop('ro.build.version.incremental')}",
        "",
        f"Probed {probed_at} as the adb shell user (`{read(fx / 'probe/id.txt').strip()}`), the identity of the T2",
        "Shizuku worker. Generated by `tools/capabilities/report.py` from",
        f"`{fx.as_posix()}`. The in-app probe suite (SPEC 4.6) replaces the PENDING rows in P1.",
        "",
        "| Property | Value |",
        "| --- | --- |",
        f"| Fingerprint | `{prop('ro.build.fingerprint')}` |",
        f"| Android / SDK | {prop('ro.build.version.release')} / {prop('ro.build.version.sdk')} |",
        f"| Security patch | {prop('ro.build.version.security_patch')} |",
        "",
        "| Capability | State | Tier | Reason / detail |",
        "| --- | --- | --- | --- |",
        *[f"| {c} | {s} | {t} | {r} |" for c, s, t, r in rows],
        "",
        "## Services (`dumpsys -l`)",
        "",
        ", ".join(f"`{w}` {'yes' if v else '**no**'}" for w, v in present.items()),
        "",
    ]
    if sk:
        md += ["## Battery-related settings keys (values as read)", "", "```", *sk, "```", ""]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(md))
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
