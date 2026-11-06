#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Turn a raw capture (tools/fixtures/capture.sh) into a committed fixture directory.

    python3 -I tools/fixtures/redact.py <raw-dir> <device-label> [--repo .]
    python3 -I tools/fixtures/redact.py --in-place <fixture-dir> --system-packages <file>

The second form only applies the package pseudonymisation to an existing fixture directory.

Personal identifiers are masked with same-length replacements so text offsets and protobuf
framing stay valid for the parser golden tests (ADR-0004):
  - e-mail local parts (and non-public domains)
  - account names: sync tags (*sync*/<authority>/<type>/<name>), Account {name=...}, accountName=...
  - Wi-Fi SSIDs (SSID: "...") and the Samsung battery pack QR/serial
  - international phone numbers (+ followed by 8-15 digits)
  - MAC addresses and the Wi-Fi IP address in WifiInfo dumps
  - the device serial numbers recorded by capture.sh
Third-party package names are replaced by pseudonyms (ADR-0008): one mapping per package, kept outside
the repository (default ~/.config/coulomb-fixtures/pseudonyms.json) so every capture and every file use
the same fake name. Each dotted segment gets a random segment of the same length and character classes;
common segments (com, org, io, android, ...) stay. UIDs are not changed. A package counts as third-party
when it is not in the device's `pm list packages -s` output (.packages-system in the raw capture); the
owner's own apps (--keep-prefix) keep their names.
In batterystats.proto.bin only string payloads found by walking the wire format are touched.
A post-redaction scan prints anything that still looks personal, for a human look.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import secrets as secrets_rng
import shutil
import sys
from pathlib import Path

EMAIL = re.compile(rb"([A-Za-z0-9._%+\-]+)@((?:[A-Za-z0-9\-]+\.)+[A-Za-z]{2,})")
PUBLIC_DOMAINS = {
    b"gmail.com", b"googlemail.com", b"google.com", b"outlook.com", b"hotmail.com", b"live.com",
    b"icloud.com", b"me.com", b"yahoo.com", b"samsung.com", b"proton.me", b"protonmail.com",
    b"yandex.com", b"yandex.ru", b"mail.ru",
}
# JobScheduler namespaces look like "<namespace>@<package>" (e.g. androidx.work.systemjobscheduler@
# com.google.android.dialer). A "domain" starting with a reverse-DNS top label is a package name.
REVERSE_DNS_TOP = {
    b"com", b"org", b"net", b"io", b"android", b"androidx", b"app", b"dev", b"de", b"me", b"tr", b"uk",
    b"co", b"edu", b"gov", b"info", b"us", b"eu", b"ru", b"jp", b"cn", b"kr", b"fr", b"it", b"nl", b"in",
    b"br", b"ch", b"se", b"at", b"be", b"vendor", b"samsung", b"sec", b"google",
}
SYNC = re.compile(rb"(\*sync\*/[^/\s,\"]+/[^/\s,\"]+/)([^\s,\"}\]\x00-\x1f\x7f-\xff]+)")
ACCOUNT = re.compile(rb"(Account ?\{name=)([^,}]+)(, type=)")
ACCOUNT_NAME = re.compile(rb"(accountName=)([^\n]+?)(, accountType=)")
# Samsung battery pack QR/serial (dumpsys battery): "QrData efsValue:<id> ,authValue:<id>"
BATTERY_QR = re.compile(rb"(QrData efsValue:)(\S+)( ,authValue:)(\S+)")
SSID = re.compile(rb"((?i:ssid)[:=] ?\")([^\"\n]*)(\")")
PHONE = re.compile(rb"(?<![\w+])\+\d{8,15}(?!\d)")
# Wi-Fi address inside WifiInfo dumps ("IP: /192.0.2.10", an RFC 5737 example); digits masked, dots kept.
WIFI_IP = re.compile(rb"(IP: /)(\d{1,3}(?:\.\d{1,3}){3})")
MAC = re.compile(rb"(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:])")

TEXT_FILES_SKIP = {".secrets", ".packages-system", ".packages-third-party", "status.tsv"}

# --- package pseudonymisation (ADR-0008) ---
DEFAULT_MAP = Path.home() / ".config/coulomb-fixtures/pseudonyms.json"
DEFAULT_KEEP_PREFIXES = ("io.github.akane599.", "com.akane.")
# Segments that say nothing about which app it is; they stay so pseudonyms still look like packages.
KEEP_SEGMENTS = {
    "com", "org", "net", "io", "dev", "app", "apps", "me", "de", "fr", "eu", "ru", "tr", "uk", "co", "cn",
    "jp", "in", "android", "github", "chromium", "webapk",
}
PACKAGE = r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+"
UID_MAP_LINE = re.compile(rb"^9,0,i,uid,(\d+),(" + PACKAGE.encode() + rb")\s*$", re.M)
APK_LINE = re.compile(rb"^9,(\d+),l,apk,\d+,(" + PACKAGE.encode() + rb"),", re.M)
HISTORY_PKG = re.compile(rb"(?:top|fg|pkginst|pkgunin|active)=[^:\s]+:\"(" + PACKAGE.encode() + rb")[\"/:]")
FIRST_APP_UID = 10000


def mask(b: bytes) -> bytes:
    out = bytearray(b)
    for i, c in enumerate(out):
        if 48 <= c <= 57:
            out[i] = ord("0")
        elif 65 <= c <= 90:
            out[i] = ord("X")
        elif 97 <= c <= 122 or c >= 0x80:
            out[i] = ord("x")  # bytes of multi-byte UTF-8 too, so no partial name survives
    return bytes(out)


class Redactor:
    def __init__(self, secrets: list[bytes], pseudo: "Pseudonymiser | None" = None):
        self.secrets = [s for s in secrets if len(s) >= 6]
        self.pseudo = pseudo
        self.counts: dict[str, int] = {}

    def _count(self, kind: str, n: int) -> None:
        if n:
            self.counts[kind] = self.counts.get(kind, 0) + n

    def text(self, data: bytes) -> bytes:
        def email(m: re.Match) -> bytes:
            domain = m.group(2)
            if domain.split(b".", 1)[0].lower() in REVERSE_DNS_TOP:
                return m.group(0)  # namespace@package, not an address
            return mask(m.group(1)) + b"@" + (domain if domain.lower() in PUBLIC_DOMAINS else mask(domain))

        for kind, rx, fn in (
            ("email", EMAIL, email),
            ("sync-account", SYNC, lambda m: m.group(1) + mask(m.group(2))),
            ("account", ACCOUNT, lambda m: m.group(1) + mask(m.group(2)) + m.group(3)),
            ("account-name", ACCOUNT_NAME, lambda m: m.group(1) + mask(m.group(2)) + m.group(3)),
            ("ssid", SSID, lambda m: m.group(1) + mask(m.group(2)) + m.group(3)),
            ("battery-qr", BATTERY_QR, lambda m: m.group(1) + mask(m.group(2)) + m.group(3) + mask(m.group(4))),
            ("phone", PHONE, lambda m: m.group(0)[:1] + mask(m.group(0)[1:])),
            ("mac", MAC, lambda m: b"02:00:00:00:00:00"),
            ("wifi-ip", WIFI_IP, lambda m: m.group(1) + mask(m.group(2))),
        ):
            before = data
            data = rx.sub(fn, data)
            self._count(kind, sum(1 for a, b in zip(rx.finditer(before), rx.finditer(data)) if a.group(0) != b.group(0)))
        for s in self.secrets:
            n = data.count(s)
            data = data.replace(s, b"X" * len(s))
            self._count("serial", n)
        if self.pseudo is not None:
            data = self.pseudo.apply(data)
        return data

    # --- protobuf: walk the wire format, redact printable string payloads in place ---
    def proto(self, data: bytes) -> bytes:
        buf = bytearray(data)
        spans: list[tuple[int, int]] = []
        self._walk(buf, 0, len(buf), spans, depth=0)
        # Innermost spans first; every replacement keeps the length, so outer spans stay aligned.
        for start, end in sorted(set(spans), key=lambda s: s[1] - s[0]):
            red = self.text(bytes(buf[start:end]))
            assert len(red) == end - start
            buf[start:end] = red
        return bytes(buf)

    @staticmethod
    def _varint(buf: bytearray, i: int, end: int) -> tuple[int, int] | None:
        shift = val = 0
        while i < end and shift < 64:
            b = buf[i]
            val |= (b & 0x7F) << shift
            i += 1
            if not b & 0x80:
                return val, i
            shift += 7
        return None

    def _walk(self, buf: bytearray, i: int, end: int, spans: list, depth: int) -> bool:
        """Returns False if [i, end) is not a well-formed message."""
        found: list[tuple[int, int]] = []
        while i < end:
            tag = self._varint(buf, i, end)
            if tag is None or tag[0] >> 3 == 0:
                return False
            key, i = tag
            wt = key & 7
            if wt == 0:
                v = self._varint(buf, i, end)
                if v is None:
                    return False
                i = v[1]
            elif wt == 1:
                i += 8
            elif wt == 5:
                i += 4
            elif wt == 2:
                ln = self._varint(buf, i, end)
                if ln is None:
                    return False
                n, i = ln
                if i + n > end:
                    return False
                payload = bytes(buf[i:i + n])
                # A payload can be both printable and a valid nested message (a one-string message
                # starts with "\n" and a printable length byte). Keep both readings: the nested string
                # leaves give clean word boundaries, the whole span catches anything in between.
                nested: list[tuple[int, int]] = []
                if depth < 40 and n and self._walk(buf, i, i + n, nested, depth + 1):
                    found.extend(nested)
                if n and all(32 <= c < 127 or c in (9, 10, 13) for c in payload):
                    found.append((i, i + n))
                i += n
            else:
                return False
            if i > end:
                return False
        spans.extend(found)
        return True


SUSPICIOUS = [
    (re.compile(rb"[A-Za-z0-9._%+\-]+@[A-Za-z0-9\-]+\.[A-Za-z]{2,}"), "email-like"),
    (re.compile(rb"Account \{name="), "Account"),
    (re.compile(rb"\+\d{8,}"), "phone-like"),
    (re.compile(rb"accountName=(?!x|X|0)[^,]{1,40}"), "accountName"),
    (re.compile(rb"(?i)ssid[:=] ?\"(?![x0X_\-. ]*\")[^\"]{1,40}\""), "ssid"),
]


def scan(path: Path) -> list[str]:
    data = path.read_bytes()
    hits = []
    for rx, label in SUSPICIOUS:
        for m in rx.finditer(data):
            frag = m.group(0)
            if label == "email-like" and re.fullmatch(rb"[x0X._%+\-]+@.*", frag):
                continue
            if label == "email-like" and frag.split(b"@", 1)[1].split(b".", 1)[0].lower() in REVERSE_DNS_TOP:
                continue
            if label == "Account":
                continue  # masked names stay behind "Account {name=" by design
            hits.append(f"{path.name}: {label}: {frag[:60]!r}")
    return hits


class Pseudonymiser:
    """Consistent same-length pseudonyms for third-party package names (ADR-0008)."""

    def __init__(self, system: set[str], keep_prefixes: tuple[str, ...], map_path: Path):
        self.system = system
        self.keep_prefixes = keep_prefixes
        self.map_path = map_path
        data = json.loads(map_path.read_text()) if map_path.exists() else {}
        self.prefixes: dict[str, str] = data.get("prefixes", {})  # real dotted prefix -> pseudonym
        self.token_map: dict[str, str] = data.get("tokens", {})  # brand word -> same-length pseudonym
        tokens_file = map_path.with_name("tokens.txt")
        # Brand words seen outside package names (class names, tags). The list itself names apps, so it
        # lives next to the mapping, outside the repository.
        self.tokens = [t.strip() for t in tokens_file.read_text().splitlines()
                       if t.strip() and not t.startswith("#")] if tokens_file.exists() else []
        self.targets: dict[bytes, bytes] = {}
        self.namespace: dict[bytes, bytes] = {}
        self.count = 0
        self._rx: re.Pattern | None = None
        self._ns_rx: re.Pattern | None = None
        self._token_rx: re.Pattern | None = None

    def is_target(self, pkg: str) -> bool:
        return pkg not in self.system and not any(pkg.startswith(p) or pkg + "." == p for p in self.keep_prefixes)

    def discover(self, files: list[Path], third_party: set[str]) -> None:
        found = set(third_party)
        for f in files:
            data = f.read_bytes()
            for m in UID_MAP_LINE.finditer(data):
                if int(m.group(1)) >= FIRST_APP_UID:
                    found.add(m.group(2).decode())
            for m in APK_LINE.finditer(data):
                if int(m.group(1)) >= FIRST_APP_UID:
                    found.add(m.group(2).decode())
            for m in HISTORY_PKG.finditer(data):
                found.add(m.group(1).decode())
        already = set(self.prefixes.values())  # a re-run over converted fixtures must not pseudonymise twice
        for pkg in sorted(p for p in found if self.is_target(p) and p not in already):
            self.targets[pkg.encode()] = self._pseudonym(pkg).encode()
        # A target's own namespace (com.example.app.push.Service, com.example.sync) is replaced too,
        # through its dotted prefixes, unless a system or kept package shares that prefix.
        protected = self.system | {p for p in found if not self.is_target(p)}
        prefixes: dict[bytes, bytes] = {}
        for real, fake in self.prefixes.items():
            if real.count(".") < 1 or real == fake or real.encode() in self.targets:
                continue
            if any(p == real or p.startswith(real + ".") for p in protected):
                continue
            if any(real.startswith(k) for k in self.keep_prefixes):
                continue
            prefixes[real.encode()] = fake.encode()
        self.namespace = prefixes
        # Longest first, so com.example.app.pro is replaced before com.example.app.
        alts = b"|".join(re.escape(k) for k in sorted(self.targets, key=len, reverse=True))
        self._rx = re.compile(rb"(?<![A-Za-z0-9_.])(" + alts + rb")(?![A-Za-z0-9_])") if alts else None
        ns = b"|".join(re.escape(k) for k in sorted(prefixes, key=len, reverse=True))
        self._ns_rx = re.compile(rb"(?<![A-Za-z0-9_.])(" + ns + rb")(?![A-Za-z0-9_])") if ns else None
        self._token_rx = None
        tokens = {t.lower() for t in self.tokens if len(t) >= 5}
        clash = {t for t in tokens if any(t in p.lower() for p in protected)}
        for t in sorted(clash):
            print(f"warning: token '{t}' also occurs in a system or kept package name; not replaced", file=sys.stderr)
        tokens -= clash
        for t in sorted(tokens):
            if t not in self.token_map:
                self.token_map[t] = self._random_segment(t).lower()
        if tokens:
            self._token_rx = re.compile(b"(" + b"|".join(re.escape(t.encode()) for t in sorted(tokens, key=len, reverse=True)) + b")", re.I)

    def _pseudonym(self, pkg: str) -> str:
        segs = pkg.split(".")
        out: list[str] = []
        for i in range(1, len(segs) + 1):
            real = ".".join(segs[:i])
            if real not in self.prefixes:
                seg = segs[i - 1]
                fake = seg if seg.lower() in KEEP_SEGMENTS else self._random_segment(seg)
                taken = set(self.prefixes.values()) | self.system
                while ".".join(out + [fake]) in taken and seg.lower() not in KEEP_SEGMENTS:
                    fake = self._random_segment(seg)
                self.prefixes[real] = ".".join(out + [fake])
            out = self.prefixes[real].split(".")
        return ".".join(out)

    @staticmethod
    def _random_segment(seg: str) -> str:
        rng = secrets_rng.SystemRandom()
        chars = []
        for i, c in enumerate(seg):
            if c.isdigit() and i > 0:
                chars.append(rng.choice("0123456789"))
            elif c.isupper():
                chars.append(rng.choice("ABCDEFGHIJKLMNOPQRSTUVWXYZ"))
            elif c == "_":
                chars.append(c)
            else:
                chars.append(rng.choice("abcdefghijklmnopqrstuvwxyz"))
        return "".join(chars)

    def apply(self, data: bytes) -> bytes:
        if self._rx is not None:
            data, n = self._rx.subn(lambda m: self.targets[m.group(1)], data)
            self.count += n
        if self._ns_rx is not None:
            data, n = self._ns_rx.subn(lambda m: self.namespace[m.group(1)], data)
            self.count += n
        if self._token_rx is not None:
            def token(m: re.Match) -> bytes:
                fake = self.token_map[m.group(1).decode().lower()]
                return bytes((ord(f.upper()) if 65 <= r <= 90 else ord(f)) for r, f in zip(m.group(1), fake))
            data, n = self._token_rx.subn(token, data)
            self.count += n
        return data

    def save(self) -> None:
        self.map_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        tmp = self.map_path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"prefixes": self.prefixes, "tokens": self.token_map}, indent=1, sort_keys=True))
        os.chmod(tmp, 0o600)
        tmp.replace(self.map_path)


def read_package_list(path: Path) -> set[str]:
    return {line.split(":", 1)[1].strip() for line in path.read_text().splitlines() if line.startswith("package:")}


def build_label(raw: Path) -> str:
    props = (raw / "probe/getprop.txt").read_text(errors="replace")
    m = re.search(r"\[ro\.build\.version\.incremental\]: \[([^\]]+)\]", props)
    return re.sub(r"[^A-Za-z0-9._-]", "_", m.group(1)) if m else "unknown-build"


def readme(raw: Path, device: str, build: str, counts: dict[str, int]) -> str:
    props = (raw / "probe/getprop.txt").read_text(errors="replace")

    def prop(name: str) -> str:
        m = re.search(r"\[" + re.escape(name) + r"\]: \[([^\]]*)\]", props)
        return m.group(1) if m else "?"

    battery = (raw / "battery.txt").read_text(errors="replace") if (raw / "battery.txt").exists() else ""
    power = (raw / "power.txt").read_text(errors="replace") if (raw / "power.txt").exists() else ""

    def field(text: str, rx: str) -> str:
        m = re.search(rx, text)
        return m.group(1).strip() if m else "?"

    uptime_s = (raw / "probe/uptime.txt").read_text().split()[0] if (raw / "probe/uptime.txt").exists() else "?"
    try:
        h = float(uptime_s) / 3600
        uptime = f"{h:.1f} h"
    except ValueError:
        uptime = "?"
    rows = []
    for line in (raw / "status.tsv").read_text().splitlines()[1:]:
        f, cmd, rc, size, err = (line.split("\t") + [""] * 5)[:5]
        if f.startswith("probe/"):
            continue
        state = "ok" if rc == "0" and int(size or 0) > 0 else f"UNSUPPORTED (exit {rc}, {size} B)"
        rows.append(f"| `{f}` | `adb shell {cmd}` | {state} | {size} | {err.strip()[:80]} |")
    red = ", ".join(f"{k}: {v}" for k, v in sorted(counts.items())) or "none found"
    return f"""# Fixtures: {device} / {build}

Captured {(raw / 'captured_at.txt').read_text().strip()} with `tools/fixtures/capture.sh` (read-only, SPEC 16.2).

| Property | Value |
| --- | --- |
| Model | {prop('ro.product.manufacturer')} {prop('ro.product.model')} |
| Fingerprint | `{prop('ro.build.fingerprint')}` |
| Android / SDK | {prop('ro.build.version.release')} / {prop('ro.build.version.sdk')} |
| Security patch | {prop('ro.build.version.security_patch')} |
| Uptime at capture | {uptime} |
| Battery level | {field(battery, r'level: (\\d+)')} % |
| Status / plugged | status {field(battery, r'status: (\\d+)')}, AC {field(battery, r'AC powered: (\\w+)')}, USB {field(battery, r'USB powered: (\\w+)')}, wireless {field(battery, r'Wireless powered: (\\w+)')} |
| Screen | {field(power, r'mWakefulness=(\\w+)')} |

## Files

| File | Command | State | Bytes | stderr |
| --- | --- | --- | --- | --- |
{chr(10).join(rows)}

`batterystats.checkin.txt` comes from `-c`, not `--checkin`: on API 36/37 `--checkin` deletes the
system's saved checkin file and dumps the previous cycle (ADR-0005).

## Redaction (ADR-0004)

Same-length masks keep offsets and protobuf framing valid. Replacements made: {red}.
`probe/` holds the shell-level capability probe outputs used for `docs/capabilities/`.
"""


def make_pseudonymiser(args, system_file: Path | None, third_party_file: Path | None) -> Pseudonymiser:
    if system_file is None or not system_file.exists():
        sys.exit("refusing to continue without the device's system package list (pm list packages -s); "
                 "third-party package names would leak (ADR-0008)")
    third = read_package_list(third_party_file) if third_party_file and third_party_file.exists() else set()
    return Pseudonymiser(read_package_list(system_file), tuple(args.keep_prefix or DEFAULT_KEEP_PREFIXES), args.map)


def pseudonym_note(pseudo: Pseudonymiser) -> str:
    return (f"Third-party package names replaced by consistent pseudonyms (ADR-0008): "
            f"{len(pseudo.targets)} packages, {pseudo.count} occurrences; UIDs unchanged.")


def in_place(args) -> int:
    out: Path = args.in_place
    pseudo = make_pseudonymiser(args, args.system_packages, args.third_party_packages)
    files = [p for p in sorted(out.rglob("*")) if p.is_file() and p.name != "README.md"]
    pseudo.discover(files, read_package_list(args.third_party_packages) if args.third_party_packages else set())
    red = Redactor([], pseudo)
    for f in files:
        data = f.read_bytes()
        new = red.proto(data) if f.suffix == ".bin" else (data if f.name == "status.tsv" else red.text(data))
        if new != data:
            assert len(new) == len(data), f
            f.write_bytes(new)
    readme_path = out / "README.md"
    text = readme_path.read_text()
    if pseudo.count:  # a re-run over converted fixtures keeps the first run's note
        text = re.sub(r"\nThird-party package names replaced.*\n", "\n", text)
        marker = "`probe/` holds"
        text = text.replace(marker, pseudonym_note(pseudo) + "\n" + marker, 1)
    if red.counts:
        text = text.replace("Replacements made: ", "Replacements made: " + ", ".join(
            f"{k}: {v}" for k, v in sorted(red.counts.items())) + " (in-place pass), ", 1)
    readme_path.write_text(text)
    pseudo.save()
    report(out, red, pseudo)
    return 0


def report(out: Path, red: Redactor, pseudo: Pseudonymiser) -> None:
    hits = [h for p in sorted(out.rglob("*")) if p.is_file() for h in scan(p)]
    print(f"fixtures written: {out}")
    print(f"replacements: {red.counts or 'none'}")
    print(f"pseudonymised packages: {len(pseudo.targets)}, occurrences: {pseudo.count}")
    print(f"post-scan hits: {len(hits)}")
    for h in hits[:50]:
        print("  " + h)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("raw", type=Path, nargs="?")
    ap.add_argument("device", nargs="?")
    ap.add_argument("--repo", type=Path, default=Path("."))
    ap.add_argument("--in-place", type=Path, help="pseudonymise an existing fixture directory")
    ap.add_argument("--system-packages", type=Path, help="pm list packages -s output (default: <raw>/.packages-system)")
    ap.add_argument("--third-party-packages", type=Path, help="pm list packages -3 output (default: <raw>/.packages-third-party)")
    ap.add_argument("--map", type=Path, default=DEFAULT_MAP, help="pseudonym mapping, kept outside the repository")
    ap.add_argument("--keep-prefix", action="append", help="package prefix that keeps its name (repeatable)")
    args = ap.parse_args()
    if args.in_place:
        return in_place(args)
    if args.raw is None or args.device is None:
        ap.error("raw and device are required unless --in-place is given")
    raw: Path = args.raw
    secrets = []
    if (raw / ".secrets").exists():
        secrets = [s.strip().encode() for s in (raw / ".secrets").read_text().splitlines() if s.strip()]
    third_file = args.third_party_packages or raw / ".packages-third-party"
    pseudo = make_pseudonymiser(args, args.system_packages or raw / ".packages-system", third_file)
    sources = [p for p in sorted(raw.rglob("*")) if p.is_file() and p.name not in TEXT_FILES_SKIP]
    pseudo.discover(sources, read_package_list(third_file) if third_file.exists() else set())
    build = build_label(raw)
    out = args.repo / "fixtures" / args.device / build
    if out.exists():
        shutil.rmtree(out)
    red = Redactor(secrets, pseudo)
    for src in sources:
        rel = src.relative_to(raw)
        dst = out / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        data = src.read_bytes()
        dst.write_bytes(red.proto(data) if src.suffix == ".bin" else red.text(data))
    shutil.copy(raw / "status.tsv", out / "status.tsv")
    text = readme(raw, args.device, build, red.counts)
    (out / "README.md").write_text(text.replace("`probe/` holds", pseudonym_note(pseudo) + "\n`probe/` holds", 1))
    pseudo.save()
    report(out, red, pseudo)
    return 0


if __name__ == "__main__":
    sys.exit(main())
