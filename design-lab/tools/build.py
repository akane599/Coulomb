# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Fills the design lab's generated blocks in place (SPEC 11.12).

    python3 -I design-lab/tools/build.py

Each page keeps static HTML. Two kinds of block are rewritten between markers:

- ``<!-- sprite -->`` ... ``<!-- /sprite -->``: the SVG symbols the page references with
  ``href="#i-..."`` (Material Symbols Rounded from ``design-lab/icons``), ``#p-...`` (provenance
  marks) or ``#app-...`` (the invented apps' icons).
- ``<!-- chart:NAME -->`` ... ``<!-- /chart -->``: the chart ``NAME`` drawn from seeded synthetic
  data, so a rebuild reproduces it exactly.

Stdlib only. The data is invented: no app, tag or reading comes from a real device.
"""

from __future__ import annotations

import math
import random
import re
import sys
from collections.abc import Sequence
from pathlib import Path

LAB = Path(__file__).resolve().parent.parent
ICONS = LAB / "icons"

# ---------------------------------------------------------------- symbols

PROVENANCE = {
    # 12 x 12 marks, stroked with currentColor by `.prov`.
    "p-measured": '<circle cx="6" cy="6" r="4" fill="currentColor" stroke="none"/>',
    "p-modeled": '<circle cx="6" cy="6" r="3.5"/>',
    "p-estimated": '<path d="M2 3.5v5M10 3.5v5M2 6h8"/>',
    "p-derived": '<circle cx="6" cy="6" r="3.5"/><path d="M6 2.5a3.5 3.5 0 0 1 0 7z" fill="currentColor" stroke="none"/>',
}

INK = "oklch(0.985 0.004 255)"
APPS = {
    # 40 x 40 icons for invented apps, authored for the lab.
    "app-halcyon": ("oklch(0.56 0.15 28)", f'<path d="M11 25a9 9 0 0 1 18 0z" fill="{INK}"/><rect x="8" y="27" width="24" height="2.5" rx="1.25" fill="{INK}"/>'),
    "app-wayline": ("oklch(0.48 0.09 200)", f'<path d="M13 29c0-6 14-4 14-10s-8-6-8-6" fill="none" stroke="{INK}" stroke-width="2.5" stroke-linecap="round"/><circle cx="13" cy="29" r="2.5" fill="{INK}"/><circle cx="19" cy="13" r="2.5" fill="{INK}"/>'),
    "app-system": ("oklch(0.38 0.02 255)", f'<circle cx="20" cy="20" r="7.5" fill="none" stroke="{INK}" stroke-width="2.5"/><circle cx="20" cy="20" r="2.5" fill="{INK}"/>'),
    "app-tally": ("oklch(0.52 0.11 150)", f'<path d="M11 14a3 3 0 0 1 3-3h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3h-8l-5 4v-4a3 3 0 0 1-2-3z" fill="{INK}"/>'),
    "app-driftly": ("oklch(0.45 0.12 300)", f'<g fill="none" stroke="{INK}" stroke-width="2.5" stroke-linecap="round"><path d="M14 26a8 8 0 0 1 12 0"/><path d="M10 21a14 14 0 0 1 20 0"/></g><circle cx="20" cy="29" r="2.5" fill="{INK}"/>'),
    "app-northwind": ("oklch(0.50 0.11 250)", f'<rect x="10" y="13" width="20" height="14" rx="2.5" fill="none" stroke="{INK}" stroke-width="2.5"/><path d="M11 15l9 6 9-6" fill="none" stroke="{INK}" stroke-width="2.5" stroke-linejoin="round"/>'),
    "app-camera": ("oklch(0.30 0.012 255)", f'<circle cx="20" cy="20" r="8" fill="none" stroke="{INK}" stroke-width="2.5"/><circle cx="20" cy="20" r="3" fill="{INK}"/>'),
    "app-play": ("oklch(0.62 0.10 160)", f'<path d="M15 12l14 8-14 8z" fill="{INK}" stroke="{INK}" stroke-width="2" stroke-linejoin="round"/>'),
    "app-home": ("oklch(0.55 0.08 235)", f'<path d="M12 19l8-7 8 7v9h-5v-6h-6v6h-5z" fill="{INK}"/>'),
    "app-coulomb": ("oklch(0.22 0.015 255)", '<path d="M12 27h16" stroke="oklch(0.74 0.13 55)" stroke-width="2.5" stroke-linecap="round"/><path d="M12 21v6M28 21v6" stroke="oklch(0.74 0.13 55)" stroke-width="2.5" stroke-linecap="round"/><circle cx="20" cy="15" r="3" fill="' + INK + '"/>'),
}


def icon_symbol(name: str) -> str:
    """A Material Symbols Rounded icon as a symbol; `name` may end in `_fill1`."""
    svg = (ICONS / f"{name}.svg").read_text()
    paths = re.findall(r'<path d="([^"]+)"', svg)
    if not paths:
        raise SystemExit(f"no path in {name}.svg")
    body = "".join(f'<path d="{d}"/>' for d in paths)
    return f'<symbol id="i-{name}" viewBox="0 -960 960 960">{body}</symbol>'


def symbol(ref: str) -> str:
    if ref.startswith("i-"):
        return icon_symbol(ref[2:])
    if ref in PROVENANCE:
        return f'<symbol id="{ref}" viewBox="0 0 12 12">{PROVENANCE[ref]}</symbol>'
    if ref in APPS:
        bg, glyph = APPS[ref]
        return f'<symbol id="{ref}" viewBox="0 0 40 40"><rect width="40" height="40" rx="12" fill="{bg}"/>{glyph}</symbol>'
    raise SystemExit(f"unknown symbol {ref}")


def sprite(html: str) -> str:
    refs = sorted(set(re.findall(r'href="#((?:i|p|app)-[a-z0-9_]+)"', html)))
    inner = "".join(symbol(r) for r in refs)
    return f'<svg class="sprite" aria-hidden="true" focusable="false">{inner}</svg>'


# ---------------------------------------------------------------- helpers


def f(v: float) -> str:
    """Coordinates to one decimal, without trailing zeros."""
    s = f"{v:.1f}"
    return s[:-2] if s.endswith(".0") else s


def poly(points: Sequence[tuple[float, float]]) -> str:
    return "M" + " L".join(f"{f(x)} {f(y)}" for x, y in points)


def text(x: float, y: float, s: str, anchor: str = "middle", cls: str | None = None) -> str:
    c = f' class="{cls}"' if cls else ""
    return f'<text x="{f(x)}" y="{f(y)}" text-anchor="{anchor}"{c}>{s}</text>'


MINUS = "−"

# ---------------------------------------------------------------- Now


def now_projection() -> str:
    """Level for the last 2 h, then the cone to empty and the bracket where it lands (SPEC 6.8)."""
    w, h, base = 380, 136, 116
    px_h = w / 12
    x = lambda hours: (hours + 2) * px_h  # noqa: E731
    y = lambda level: base - level * (base - 12) / 100  # noqa: E731
    lo, hi = 5 + 50 / 60, 7 + 20 / 60
    rnd = random.Random(7)
    hist = []
    for i in range(41):
        t = -2 + i * 0.05
        level = 74 + (-t) * 5.0 + rnd.uniform(-0.25, 0.25) if i < 40 else 74
        hist.append((x(t), y(level)))
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="proj-t">',
             '<title id="proj-t">Battery fell from 84% to 74% over the last 2 hours. At this rate it reaches empty between 5 h 50 min and 7 h 20 min from now, between 20:10 and 21:40.</title>']
    for level in (25, 50, 75, 100):
        parts.append(f'<path class="grid" d="M0 {f(y(level))} H{w}"/>')
        parts.append(text(w, y(level) - 4, f"{level}%", "end"))
    parts.append(f'<path class="grid" d="M{f(x(0))} {f(y(100))} V{base}" stroke-dasharray="2 3"/>')
    parts.append(f'<path class="axis" d="M0 {base} H{w}"/>')
    parts.append(text(w, base - 4, "0%", "end"))
    # Clock time on the axis, so the ticks and the bracket read in the same convention.
    now_h = 14 + 20 / 60
    for clock_h in range(13, 25):
        parts.append(f'<path class="axis" d="M{f(x(clock_h - now_h))} {base} V{base + 3}"/>')
    for clock_h, label, anchor in ((13, "13:00", "middle"), (now_h, "now", "middle"), (16, "16:00", "middle"),
                                   (18, "18:00", "middle"), (24, "00:00", "end")):
        parts.append(text(x(clock_h - now_h), base + 16, label, anchor))
    cone = [(x(0), y(74)), (x(lo), base), (x(hi), base)]
    parts.append(f'<path class="cone" d="{poly(cone)} Z"/>')
    parts.append(f'<path class="cone-edge" d="{poly([cone[0], cone[1]])}"/>')
    parts.append(f'<path class="cone-edge" d="{poly([cone[0], cone[2]])}"/>')
    parts.append(f'<path class="ink" d="{poly(hist)}"/>')
    parts.append(f'<path class="bracket" d="M{f(x(lo))} {base - 7} V{base} H{f(x(hi))} V{base - 7}"/>')
    parts.append(text((x(lo) + x(hi)) / 2, base + 16, "20:10–21:40", "middle", "lbl-strong"))
    parts.append(f'<circle class="dot" cx="{f(x(0))}" cy="{f(y(74))}" r="4.5"/>')
    parts.append("</svg>")
    return "".join(parts)


def now_current() -> str:
    """The last 10 minutes of current, discharge below the zero axis (SPEC 10.2, 11.8)."""
    w, h, pw = 380, 128, 324
    y = lambda ma: 8 + ma * 0.075  # noqa: E731
    rnd = random.Random(11)
    pts = []
    for i in range(121):
        t = i * 5
        ma = 420 + 60 * math.sin(i / 7) + rnd.uniform(-45, 45)
        if 40 <= i <= 52:
            ma += 560 * math.sin((i - 40) / 12 * math.pi)
        if i == 120:
            ma = 410
        pts.append((t / 600 * pw, y(ma)))
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="cur-t">',
             '<title id="cur-t">Current over the last 10 minutes: mostly 350 to 500 mA, one rise to about 1,000 mA six minutes ago, 410 mA now.</title>']
    for ma in (300, 600, 900, 1200):
        parts.append(f'<path class="grid" d="M0 {f(y(ma))} H{pw}"/>')
    for ma, label in ((0, "0 mA"), (600, f"{MINUS}600"), (1200, f"{MINUS}1,200")):
        parts.append(text(w, y(ma) + 4, label, "end"))
    area = [(0.0, y(0))] + pts + [(float(pw), y(0))]
    parts.append(f'<path class="ink-area" d="{poly(area)} Z"/>')
    parts.append(f'<path class="ink" d="{poly(pts)}"/>')
    parts.append(f'<path class="axis" d="M0 {f(y(0))} H{pw}"/>')
    parts.append(f'<circle class="dot" cx="{f(pts[-1][0])}" cy="{f(pts[-1][1])}" r="4"/>')
    for t, label, anchor in ((0, f"{MINUS}10 min", "start"), (300, f"{MINUS}5 min", "middle"), (600, "now", "end")):
        parts.append(text(t / 600 * pw, 122, label, anchor))
    parts.append("</svg>")
    return "".join(parts)


# ---------------------------------------------------------------- Drain


def drain_reconcile() -> str:
    """Measured total as one bar in two labelled parts: modeled, and the unattributed gap hatched (SPEC 6.5, 10.3).

    Per-app segments are left to the list below: unlabelled segments would claim identities nobody can read.
    """
    w, h = 380, 48
    measured, modeled = 1204, 1062
    x = modeled * w / measured
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="rec-t">',
             '<title id="rec-t">Measured 1,204 mAh. Apps and system account for 1,062 mAh by the power model; 142 mAh, 12%, is unattributed.</title>',
             '<defs><pattern id="hatch" width="5" height="5" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">'
             '<path d="M0 0 V5" class="hatch-line"/></pattern></defs>']
    parts.append(f'<rect class="seg" x="0" y="12" width="{f(x - 2)}" height="14" rx="2"/>')
    parts.append(f'<rect class="gap-fill" x="{f(x)}" y="12" width="{f(w - x)}" height="14" rx="2"/>')
    parts.append(f'<rect class="gap-edge" x="{f(x + 0.5)}" y="12.5" width="{f(w - x - 1)}" height="13" rx="2"/>')
    parts.append(text(0, 44, "Modeled 1,062 mAh", "start"))
    parts.append(text(w, 44, "Unattributed 142 mAh", "end", "lbl-strong"))
    parts.append("</svg>")
    return "".join(parts)


# ---------------------------------------------------------------- App detail

COMPONENTS = [("Screen", 188, "screen"), ("Mobile radio", 121, "radio"), ("CPU", 74, "cpu"), ("Wi-Fi", 12, "wifi"), ("Other", 7, "other")]


def app_components() -> str:
    """One stacked bar of the app's mAh by power component, labelled directly (SPEC 11.8).

    Segments wide enough take their label below their start; the narrow tail shares one label at the
    right end with a leader to its segments.
    """
    w, h = 380, 76
    total = sum(m for _, m, _ in COMPONENTS)
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="comp-t">',
             '<title id="comp-t">Halcyon by component: screen 188 mAh, mobile radio 121, CPU 74, Wi-Fi 12, other 7.</title>']
    x = 0.0
    tail: list[tuple[str, int, float]] = []
    for name, mah, key in COMPONENTS:
        seg = mah / total * w
        parts.append(f'<rect class="comp-seg" x="{f(x)}" y="2" width="{f(max(seg - 2, 1.5))}" height="16" rx="2" style="fill: var(--data-{key})"/>')
        if seg >= 60:
            parts.append(f'<path class="axis" d="M{f(x + 0.5)} 20 V24"/>')
            parts.append(text(x, 38, name, "start", "lbl-strong"))
            parts.append(text(x, 54, f"{mah} mAh", "start"))
        else:
            tail.append((name, mah, x + seg / 2))
        x += seg
    if tail:
        lx = (tail[0][2] + tail[-1][2]) / 2
        parts.append(f'<path class="axis" d="M{f(lx)} 20 V56"/>')
        parts.append(text(w, 72, " · ".join(f"{n} {m}" for n, m, _ in tail) + " mAh", "end"))
    parts.append("</svg>")
    return "".join(parts)


def app_trend() -> str:
    """14 days of the app's mAh, with the update that preceded the rise (SPEC 9 R18, 10.4)."""
    w, h, top, base, plot = 380, 150, 24, 118, 344
    days = [118, 132, 101, 125, 140, 122, 109, 131, 127, 236, 288, 301, 274, 402]
    labels = {0: "25 Oct", 6: "31 Oct", 13: "Today"}
    vmax = 450
    y = lambda v: base - v / vmax * (base - top)  # noqa: E731
    slot = plot / 14
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="trend-t">',
             '<title id="trend-t">Halcyon used 100 to 140 mAh a day until it updated to 8.12 on 3 November; since then 236 to 402 mAh a day.</title>']
    parts.append(text(0, 10, "mAh per day", "start"))
    for v in (150, 300, 450):
        parts.append(f'<path class="grid" d="M0 {f(y(v))} H{plot}"/>')
        parts.append(text(w, y(v) + 4, f"{v}", "end"))
    for i, v in enumerate(days):
        cls = "bar-now" if i == 13 else "bar"
        parts.append(f'<rect class="{cls}" x="{f(i * slot + 3)}" y="{f(y(v))}" width="{f(slot - 6)}" height="{f(base - y(v))}" rx="2"/>')
    parts.append(f'<path class="axis" d="M0 {base} H{plot}"/>')
    mx = 9 * slot
    parts.append(f'<path class="marker" d="M{f(mx)} 2 V{base}"/>')
    parts.append(text(mx - 6, 10, "Updated to 8.12", "end", "lbl-strong"))
    for i, label in labels.items():
        anchor = "start" if i == 0 else "end" if i == 13 else "middle"
        x = i * slot + (3 if i == 0 else slot - 3 if i == 13 else slot / 2)
        parts.append(text(x, base + 18, label, anchor))
    parts.append("</svg>")
    return "".join(parts)


# ---------------------------------------------------------------- timeline

# Synthetic day: the window runs from 14:20 yesterday (t = 0 h) to 14:20 today (t = 24 h).
TL_START = 14 * 60 + 20
TL_X0, TL_X1 = 84.0, 380.0


def clock(hm: str) -> float:
    """Clock time to hours since the window start."""
    h, m = (int(v) for v in hm.split(":"))
    return ((h * 60 + m - TL_START) % 1440) / 60


def tx(t: float) -> float:
    return TL_X0 + t / 24 * (TL_X1 - TL_X0)


def spans(pairs: Sequence[tuple[str, str]]) -> list[tuple[float, float]]:
    out = []
    for a, b in pairs:
        ta, tb = clock(a), clock(b)
        out.append((ta, 24.0 if tb == 0 and ta > 0 else tb))
    return out


SCREEN = spans([("14:20", "15:05"), ("16:30", "17:10"), ("18:40", "19:30"), ("20:15", "21:45"),
                ("22:30", "00:25"), ("07:15", "07:40"), ("09:10", "09:55"), ("11:30", "12:15"),
                ("13:58", "14:20")])
CHARGING = spans([("00:30", "09:08")])
DOZE_LIGHT = spans([("15:10", "15:40"), ("17:15", "17:45"), ("19:35", "20:15"), ("21:50", "22:30"),
                    ("10:00", "11:30"), ("12:20", "13:58")])
DOZE_DEEP = spans([("15:40", "16:30"), ("17:45", "18:40")])
WIFI = spans([("14:20", "16:40"), ("18:30", "09:40")])
GPS = spans([("16:45", "17:10"), ("09:42", "09:55")])
LEVEL = [("14:20", 88), ("15:05", 81), ("16:30", 80), ("17:10", 74), ("18:40", 72.5), ("19:30", 65),
         ("20:15", 64.5), ("21:45", 52), ("22:30", 51.5), ("00:25", 38), ("00:30", 38), ("01:00", 50),
         ("01:30", 62), ("02:00", 73), ("02:30", 82), ("03:00", 88), ("03:30", 92), ("04:00", 95),
         ("04:30", 97.5), ("05:00", 99), ("05:28", 100), ("09:08", 100), ("09:10", 100), ("09:55", 90.6),
         ("11:30", 89.3), ("12:15", 79.9), ("13:58", 78.6), ("14:20", 74)]
SCRUB = clock("10:45")
AX = 424  # time axis y in the timeline chart


def inside(t: float, ranges: Sequence[tuple[float, float]]) -> bool:
    return any(a <= t < b for a, b in ranges)


def wakeups() -> list[float]:
    """Screen-off wakeups: Halcyon's alarm every 5 min today, sparse ones yesterday."""
    out = [clock(hm) for hm in ("15:22", "15:58", "16:14", "17:31", "18:02", "18:25", "19:50", "22:05")]
    for a, b in spans([("09:55", "11:30"), ("12:15", "13:58")]):
        t = a + 5 / 60
        while t < b:
            out.append(t)
            t += 5 / 60
    return sorted(out)


def jitter(t: float) -> float:
    """Deterministic wobble in [-1, 1] so the build is reproducible."""
    return math.sin(t * 7.3) * 0.6 + math.sin(t * 17.9 + 1.3) * 0.4


def current_ma(t: float) -> float:
    if inside(t, CHARGING):
        c0 = clock("00:30")
        if t < c0 + 2.0:
            return 1480 + 40 * jitter(t)
        full = clock("05:28")
        if t < full:
            return 1480 * (full - t) / (full - c0 - 2.0) + 60
        return -60 if inside(t, SCREEN) else 15
    if inside(t, SCREEN):
        return -(640 + 220 * jitter(t))
    return -45 - 10 * jitter(t)


def timeline() -> str:
    w, h = 380, 446
    lv = lambda v: 84 - v * 0.72  # noqa: E731  level panel, y 12..84
    zero, k = 140.0, 0.02  # current panel: +1.5 A at y 110, -1 A at y 160
    cy = lambda ma: zero - ma * k  # noqa: E731
    parts = [f'<svg class="chart timeline" viewBox="0 0 {w} {h}" role="img" aria-labelledby="tl-t">',
             '<title id="tl-t">Last 24 hours. The battery fell from 88% to 38% by 00:25, charged to 100% by 05:28 and stayed there until it was unplugged at 09:08; it is at 74% now. With the screen off today, the phone woke every 5 minutes and never reached deep Doze.</title>']
    # Day grid every 6 h on the clock, across every track.
    ticks = [clock(hm) for hm in ("15:00", "18:00", "21:00", "00:00", "03:00", "06:00", "09:00", "12:00")]
    for t in ticks:
        major = round((t * 60 + TL_START) % 360) == 0
        if major:
            parts.append(f'<path class="grid" d="M{f(tx(t))} 8 V{AX}"/>')
    # Level panel.
    for v in (100, 50, 0):
        parts.append(f'<path class="grid" d="M{TL_X0} {f(lv(v))} H{TL_X1}"/>')
        parts.append(text(78, lv(v) + 4, f"{v}%", "end"))
    parts.append(text(0, lv(75) + 4, "Level", "start", "lbl-strong"))
    parts.append(f'<path class="ink level" d="{poly([(tx(clock(hm)) if i < len(LEVEL) - 1 else TL_X1, lv(v)) for i, (hm, v) in enumerate(LEVEL)])}"/>')
    # Current panel: discharge below the axis, charge above (SPEC 10.6).
    n = 296
    pts = [(TL_X0 + i, current_ma((i + 0.5) / n * 24)) for i in range(n)]
    out = [(x, cy(min(ma, 0))) for x, ma in pts]
    inn = [(x, cy(max(ma, 0))) for x, ma in pts]
    parts.append(f'<path class="cur-out" d="{poly([(TL_X0, zero)] + out + [(TL_X1, zero)])} Z"/>')
    parts.append(f'<path class="cur-in" d="{poly([(TL_X0, zero)] + inn + [(TL_X1, zero)])} Z"/>')
    # Edges carry the 3:1 non-text contrast the translucent fills cannot (WCAG 1.4.11).
    parts.append(f'<path class="cur-out-edge" d="{poly(out)}"/>')
    parts.append(f'<path class="cur-in-edge" d="{poly(inn)}"/>')
    parts.append(f'<path class="axis" d="M{TL_X0} {zero} H{TL_X1}"/>')
    parts.append(text(0, cy(750) + 4, "Current", "start", "lbl-strong"))
    parts.append(text(78, cy(1500) + 4, "+1.5 A", "end"))
    parts.append(text(78, zero + 4, "0", "end"))
    parts.append(text(78, cy(-1000) + 4, f"{MINUS}1 A", "end"))
    # Event bands.
    top, band, pitch, group_gap = 178.0, 12.0, 22.0, 6.0
    wk = wakeups()

    def row(i: int, label: str) -> float:
        # Radios and sensors (from Mobile radio down) sit apart from the phone's states.
        y = top + i * pitch + (group_gap if i >= 6 else 0)
        parts.append(text(0, y + 10, label, "start"))
        parts.append(f'<rect class="trk-bg" x="{TL_X0}" y="{f(y)}" width="{f(TL_X1 - TL_X0)}" height="{band}" rx="3"/>')
        return y

    def fill(y: float, ranges: Sequence[tuple[float, float]], cls: str, inset: float = 0) -> None:
        for a, b in ranges:
            parts.append(f'<rect class="{cls}" x="{f(tx(a))}" y="{f(y + inset)}" width="{f(max(tx(b) - tx(a), 1))}" height="{f(band - 2 * inset)}"/>')

    def ticks_at(y: float, times: Sequence[float], cls: str, dense_cls: str) -> None:
        """One tick per event where events are at least 3 px apart. A closer run of three or more
        becomes one outlined density span: 1 px ticks would merge into a solid band that reads as
        "on the whole time" (SPEC 11.8, ADR-0023)."""
        runs: list[list[float]] = []
        for xv in (tx(t) for t in times):
            if runs and xv - runs[-1][-1] < 3:
                runs[-1].append(xv)
            else:
                runs.append([xv])
        lone = [xv for run in runs if len(run) < 3 for xv in run]
        for run in (r for r in runs if len(r) >= 3):
            parts.append(f'<rect class="{dense_cls}" x="{f(run[0] - 0.5)}" y="{f(y + 0.5)}" width="{f(run[-1] - run[0] + 1)}" height="{band - 1}"/>')
        if lone:
            d = " ".join(f"M{f(xv)} {f(y)} v{band}" for xv in lone)
            parts.append(f'<path class="{cls}" d="{d}"/>')

    y = row(0, "Screen"); fill(y, SCREEN, "trk-screen")
    y = row(1, "Charging"); fill(y, CHARGING, "trk-charge")
    y = row(2, "Doze"); fill(y, DOZE_LIGHT, "trk-doze-light"); fill(y, DOZE_DEEP, "trk-doze-deep")
    y = row(3, "Awake"); fill(y, SCREEN + spans([("00:30", "01:05")]), "trk-awake"); ticks_at(y, wk, "trk-awake-tick", "trk-dense-cpu")
    apps, alt = [], []
    for j, (a, b) in enumerate(SCREEN):
        cut = a + (b - a) * (0.55 if j % 2 else 0.35)
        apps.append((a, cut)); alt.append((cut, b))
    y = row(4, "Top app"); fill(y, apps, "trk-app"); fill(y, alt, "trk-app-alt")
    y = row(5, "Wakelocks"); ticks_at(y, wk, "trk-wl-tick", "trk-dense-cpu"); fill(y, spans([("20:40", "21:30"), ("09:12", "09:30")]), "trk-wl")
    radio = [(a, b) for a, b in SCREEN if not inside(a, WIFI)]
    y = row(6, "Mobile radio"); fill(y, radio, "trk-radio"); ticks_at(y, [t for t in wk if not inside(t, WIFI)], "trk-radio-tick", "trk-dense-radio")
    y = row(7, "Wi-Fi"); fill(y, WIFI, "trk-wifi")
    y = row(8, "GPS"); fill(y, GPS, "trk-gps")
    y = row(9, "Signal")
    bars = [(0, 3), (2.3, 1), (3.0, 2), (4.2, 4), (6.0, 3), (10.5, 3), (19.3, 2), (20.0, 1), (21.4, 2), (23.0, 2), (24, 2)]
    sig = []
    for (t0, b0), (t1, _) in zip(bars, bars[1:]):
        yy = y + band - 1 - b0 / 4 * (band - 2)
        sig += [(tx(t0), yy), (tx(t1), yy)]
    parts.append(f'<path class="trk-line" d="{poly(sig)}"/>')
    y = row(10, "Temperature")
    temps = []
    for i in range(0, n + 1, 4):
        t = i / n * 24
        c = 29.0 + (4.5 if inside(t, SCREEN) else 0) + (6.0 if inside(t, CHARGING) and t < clock("02:30") else 0) + 0.6 * jitter(t)
        temps.append((TL_X0 + i, y + band - 1 - (c - 26) / 14 * (band - 2)))
    parts.append(f'<path class="trk-line" d="{poly(temps)}"/>')
    # Time axis.
    for t in ticks:
        parts.append(f'<path class="axis" d="M{f(tx(t))} {AX} v4"/>')
    for hm in ("18:00", "00:00", "06:00", "12:00"):
        parts.append(text(tx(clock(hm)), AX + 18, hm))
    # Scrub cursor with its readout point.
    sx = tx(SCRUB)
    lvl = 90.6 - (SCRUB - clock("09:55")) / (clock("11:30") - clock("09:55")) * 1.3
    parts.append(f'<path class="scrub" d="M{f(sx)} 4 V{AX}"/>')
    parts.append(f'<circle class="dot" cx="{f(sx)}" cy="{f(lv(lvl))}" r="4"/>')
    parts.append("</svg>")
    return "".join(parts)


# ---------------------------------------------------------------- health

DESIGN_MAH = 5000
CAP_LO, CAP_EST, CAP_HI = 4540, 4610, 4680


def health_scale() -> str:
    """The capacity interval as a bracket on a graduated scale that ends at design capacity (SPEC 6.3)."""
    w, h, x0, x1, sy = 380, 72, 8.0, 372.0, 30.0
    x = lambda mah: x0 + (mah - 4000) / 1000 * (x1 - x0)  # noqa: E731
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="scale-t">',
             '<title id="scale-t">Estimated capacity 4,610 mAh, 95% interval 4,540 to 4,680 mAh, on a scale from 4,000 mAh (80%) to the 5,000 mAh design capacity (100%).</title>']
    parts.append(f'<path class="axis" d="M{x0} {sy} H{x1}"/>')
    for mah in range(4000, 5001, 100):
        major = mah % 500 == 0
        parts.append(f'<path class="axis" d="M{f(x(mah))} {sy} v{8 if major else 4}"/>')
    for mah, pct, anchor in ((4000, "80%", "start"), (4500, "90%", "middle"), (5000, "100%", "end")):
        xx = 0 if anchor == "start" else w if anchor == "end" else x(mah)
        parts.append(text(xx, 54, f"{mah:,}", anchor))
        parts.append(text(xx, 70, pct, anchor))
    parts.append(text(w, 12, "Design", "end"))
    parts.append(f'<path class="bracket" d="M{f(x(CAP_LO))} {sy - 8} V{sy} H{f(x(CAP_HI))} V{sy - 8}"/>')
    parts.append(f'<circle class="dot" cx="{f(x(CAP_EST))}" cy="{sy}" r="4.5"/>')
    parts.append(text(x(CAP_EST), 12, "4,540–4,680", "middle", "lbl-strong"))
    parts.append("</svg>")
    return "".join(parts)


# Qualifying windows since tracking began on 12 August: (day offset, C_i in mAh).
WINDOWS = [(3, 4720), (9, 4610), (14, 4680), (20, 4570), (27, 4650), (33, 4700), (39, 4590), (46, 4630),
           (52, 4660), (58, 4580), (65, 4620), (71, 4640), (78, 4600), (84, 4615)]


def capacity_trend() -> str:
    """Estimator output with its interval band, the raw windows behind it, and design capacity."""
    w, h, top, base, plot, days = 380, 150, 16, 124, 344, 87
    vmin, vmax = 4400, 5050
    x = lambda d: d / days * plot  # noqa: E731
    y = lambda v: base - (v - vmin) / (vmax - vmin) * (base - top)  # noqa: E731
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="cap-t">',
             '<title id="cap-t">Capacity since 12 August: the estimate moved from 4,720 to 4,610 mAh while its interval narrowed from plus or minus 150 to plus or minus 70 mAh, from 14 qualifying windows.</title>']
    for v in (4600, 4800):
        parts.append(f'<path class="grid" d="M0 {f(y(v))} H{plot}"/>')
        parts.append(text(w, y(v) + 4, f"{v:,}", "end"))
    parts.append(text(w, base + 4, "4,400", "end"))
    parts.append(f'<path class="marker" d="M0 {f(y(DESIGN_MAH))} H{plot}"/>')
    parts.append(text(w, y(DESIGN_MAH) + 4, f"{DESIGN_MAH:,}", "end"))
    parts.append(text(0, y(DESIGN_MAH) - 6, "Design capacity", "start"))
    est, lo, hi = [], [], []
    for i, (d, _) in enumerate(WINDOWS):
        recent = sorted(c for _, c in WINDOWS[max(0, i - 9): i + 1])
        m = recent[len(recent) // 2] if len(recent) % 2 else (recent[len(recent) // 2 - 1] + recent[len(recent) // 2]) / 2
        half = max(70, 150 - 12 * i)
        est.append((x(d), y(m))); lo.append((x(d), y(m - half))); hi.append((x(d), y(m + half)))
    parts.append(f'<path class="band" d="{poly(hi + lo[::-1])} Z"/>')
    # Windows are crosses: a ring would read as the Modeled mark and a dot as Measured (SPEC 6.7).
    crosses = " ".join(f"M{f(x(d) - 2.5)} {f(y(c) - 2.5)} l5 5 M{f(x(d) + 2.5)} {f(y(c) - 2.5)} l-5 5" for d, c in WINDOWS)
    parts.append(f'<path class="cross" d="{crosses}"/>')
    parts.append(f'<path class="ink" d="{poly(est)}"/>')
    ex = est[-1][0]
    parts.append(f'<path class="bracket" d="M{f(ex + 6)} {f(y(CAP_HI))} H{f(ex)} V{f(y(CAP_LO))} H{f(ex + 6)}"/>')
    parts.append(f'<path class="axis" d="M0 {base} H{plot}"/>')
    for d, label, anchor in ((0, "12 Aug", "start"), (20, "Sep", "middle"), (50, "Oct", "middle"), (81, "Nov", "middle")):
        parts.append(f'<path class="axis" d="M{f(x(d))} {base} v4"/>')
        parts.append(text(x(d), base + 20, label, anchor))
    parts.append("</svg>")
    return "".join(parts)


# ---------------------------------------------------------------- charging

CH_LEVEL, CH_TARGET = 52, 80


def charge_level() -> str:
    """Level as an M3 Expressive wavy progress indicator, with the target tick (SPEC 10.9)."""
    w, h, ty = 380, 44, 30.0
    x = lambda pct: pct / 100 * w  # noqa: E731
    end = x(CH_LEVEL)
    wave = [(i * 0.5, ty + 3 * math.sin(i * 0.5 / 20 * 2 * math.pi)) for i in range(int(end * 2) - 3)]
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="lvl-t">',
             f'<title id="lvl-t">Level {CH_LEVEL}%, charging to the {CH_TARGET}% target.</title>']
    parts.append(f'<path class="wave" d="{poly(wave)}"/>')
    parts.append(f'<path class="track" d="M{f(end + 6)} {ty} H{w - 2}"/>')
    parts.append(f'<circle class="stop" cx="{w - 2}" cy="{ty}" r="2"/>')
    parts.append(f'<path class="target" d="M{f(x(CH_TARGET))} {ty - 10} V{ty + 10}"/>')
    # The time to target sits at the target tick, with the Estimated mark (a bracket) after it.
    tx_end = x(CH_TARGET) + 62
    parts.append(text(tx_end - 16, 12, f"{CH_TARGET}% at 19:27–19:33", "end", "lbl-strong"))
    parts.append(f'<path class="mark-est" d="M{f(tx_end - 11)} 4.5v5M{f(tx_end - 3)} 4.5v5M{f(tx_end - 11)} 7h8"/>')
    parts.append("</svg>")
    return "".join(parts)


def charging_curve() -> str:
    """Session level and power since plug-in, then the cone to the target and the bracket where it lands."""
    w, h, plot = 380, 196, 344
    t_end, now = 84, 34  # minutes since plug-in at 18:31
    x = lambda m: m / t_end * plot  # noqa: E731
    ly = lambda pct: 104 - pct * 0.96  # noqa: E731  level panel y 8..104
    py0, pk = 176.0, 1.6  # power panel: 0 W at y 176, 30 W at y 128
    py = lambda wt: py0 - wt * pk  # noqa: E731
    lo, hi = now + 22, now + 28
    parts = [f'<svg class="chart" viewBox="0 0 {w} {h}" role="img" aria-labelledby="curve-t">',
             '<title id="curve-t">Plugged in at 18:31 at 21%. Now 52% at 18.4 W. At this rate the battery reaches 80% between 19:27 and 19:33.</title>']
    for v in (25, 50, 100):
        parts.append(f'<path class="grid" d="M0 {f(ly(v))} H{plot}"/>')
        parts.append(text(w, ly(v) + 4, f"{v}%", "end"))
    parts.append(f'<path class="axis" d="M0 {ly(0)} H{plot}"/>')
    parts.append(text(w, ly(0) + 4, "0%", "end"))
    parts.append(f'<path class="marker" d="M0 {f(ly(CH_TARGET))} H{plot}"/>')
    parts.append(text(w, ly(CH_TARGET) + 4, f"{CH_TARGET}%", "end", "lbl-strong"))
    hist = []
    for i in range(now + 1):
        level = 21 + 31 * (1 - (1 - i / now) ** 1.15)
        hist.append((x(i), ly(level)))
    cone = [(x(now), ly(CH_LEVEL)), (x(lo), ly(CH_TARGET)), (x(hi), ly(CH_TARGET))]
    parts.append(f'<path class="cone" d="{poly(cone)} Z"/>')
    parts.append(f'<path class="cone-edge" d="{poly([cone[0], cone[1]])}"/>')
    parts.append(f'<path class="cone-edge" d="{poly([cone[0], cone[2]])}"/>')
    parts.append(f'<path class="ink" d="{poly(hist)}"/>')
    yb = ly(CH_TARGET)
    parts.append(f'<path class="bracket" d="M{f(x(lo))} {f(yb - 7)} V{f(yb)} H{f(x(hi))} V{f(yb - 7)}"/>')
    parts.append(text((x(lo) + x(hi)) / 2, yb - 12, "19:27–19:33", "middle", "lbl-strong"))
    parts.append(f'<circle class="dot" cx="{f(x(now))}" cy="{f(ly(CH_LEVEL))}" r="4.5"/>')
    # Power panel.
    power = []
    for i in range(now + 1):
        wt = 24.2 if 2 <= i <= 19 else (12 + 6 * i if i < 2 else 24.2 - (i - 19) * 0.39)
        power.append((x(i), py(wt + 0.4 * jitter(i / 3))))
    parts.append(f'<path class="ink-area" d="{poly([(0, py0)] + power + [(x(now), py0)])} Z"/>')
    parts.append(f'<path class="ink" d="{poly(power)}"/>')
    parts.append(f'<path class="grid" d="M0 {f(py(30))} H{plot}"/>')
    parts.append(text(w, py(30) + 4, "30 W", "end"))
    parts.append(f'<path class="axis" d="M0 {py0} H{plot}"/>')
    parts.append(text(w, py0 + 4, "0", "end"))
    parts.append(text(0, py(30) - 6, "Power", "start", "lbl-strong"))
    parts.append(f'<path class="grid" d="M{f(x(now))} 8 V{py0}" stroke-dasharray="2 3"/>')
    for m, label, anchor in ((0, "18:31", "start"), (now, "now", "middle"), (59, "19:30", "middle"), (t_end, "19:55", "end")):
        parts.append(f'<path class="axis" d="M{f(x(m))} {py0} v4"/>')
        parts.append(text(x(m), py0 + 18, label, anchor))
    parts.append("</svg>")
    return "".join(parts)


CHARTS = {
    "now-projection": now_projection,
    "now-current": now_current,
    "drain-reconcile": drain_reconcile,
    "app-components": app_components,
    "app-trend": app_trend,
    "timeline": timeline,
    "health-scale": health_scale,
    "capacity-trend": capacity_trend,
    "charge-level": charge_level,
    "charging-curve": charging_curve,
}

# ---------------------------------------------------------------- driver

SPRITE = re.compile(r"(<!-- sprite -->).*?(<!-- /sprite -->)", re.S)
CHART = re.compile(r"(<!-- chart:([a-z0-9-]+) -->).*?(<!-- /chart -->)", re.S)


def build(page: Path) -> bool:
    html = page.read_text()
    out = CHART.sub(lambda m: m.group(1) + CHARTS[m.group(2)]() + m.group(3), html)
    out = SPRITE.sub(lambda m: m.group(1) + sprite(out) + m.group(2), out)
    if out != html:
        page.write_text(out)
        return True
    return False


def main() -> int:
    pages = sorted(LAB.glob("*.html"))
    changed = [p.name for p in pages if build(p)]
    print("rebuilt:", ", ".join(changed) or "nothing")
    return 0


if __name__ == "__main__":
    sys.exit(main())
