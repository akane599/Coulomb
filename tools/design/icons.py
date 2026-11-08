#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Converts the design lab's Material Symbols Rounded SVGs into Android vector drawables (SPEC 11.9).

    python3 -I tools/design/icons.py            # rewrite core/designsystem/src/main/res/drawable/ic_*.xml
    python3 -I tools/design/icons.py --check    # exit 1 if a drawable is missing, stale or orphaned

The lab's icons (`design-lab/icons/*.svg`) are the source: one weight, one optical size, 24 px, each a
single filled path in the Material Symbols viewBox `0 -960 960 960`. The drawable keeps the path as it is
and shifts it down by 960 inside a group, because a vector drawable's viewport starts at 0. Compose tints
every icon with its content colour, so the fill colour here only has to be opaque. Anything else in an SVG
stops the script, so a new icon of another shape is noticed rather than drawn wrong. The lab's mock
status bar icons are skipped (LAB_ONLY).
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "design-lab/icons"
OUT = ROOT / "core/designsystem/src/main/res/drawable"
SVG = "{http://www.w3.org/2000/svg}"
VIEWBOX = "0 -960 960 960"
# The lab draws a mock status bar with these; the app never does, and lint fails on unused resources.
LAB_ONLY = {"battery_android_5", "signal_cellular_alt", "wifi"}
# Directional icons flip under an RTL layout, as Material's auto-mirrored set does (ADR-0034).
MIRRORED = {"arrow_back", "chevron_right", "open_in_new", "undo"}


def drawable(svg: Path) -> str:
    root = ET.parse(svg).getroot()
    if root.tag != f"{SVG}svg" or root.get("viewBox") != VIEWBOX:
        sys.exit(f"{svg.name}: not a Material Symbols SVG with viewBox {VIEWBOX}")
    paths = list(root)
    if len(paths) != 1 or paths[0].tag != f"{SVG}path" or set(paths[0].keys()) != {"d"}:
        sys.exit(f"{svg.name}: expected exactly one plain <path d=...>")
    name = svg.stem
    mirrored = '    android:autoMirrored="true"\n' if name in MIRRORED else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f'<!-- Material Symbols Rounded "{name}" (Google LLC, Apache-2.0), from design-lab/icons by tools/design/icons.py. -->\n'
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="960"\n'
        f"{mirrored}"
        '    android:viewportHeight="960">\n'
        '    <group android:translateY="960">\n'
        '        <path\n'
        '            android:fillColor="#FF000000"\n'
        f'            android:pathData="{paths[0].get("d")}" />\n'
        "    </group>\n"
        "</vector>\n"
    )


def main(check: bool) -> int:
    svgs = [svg for svg in sorted(SOURCE.glob("*.svg")) if svg.stem not in LAB_ONLY]
    wanted = {OUT / f"ic_{svg.stem}.xml": drawable(svg) for svg in svgs}
    existing = set(OUT.glob("ic_*.xml")) - {OUT / "ic_launcher_foreground.xml"}
    stale = [p for p, text in wanted.items() if not p.exists() or p.read_text(encoding="utf-8") != text]
    orphans = sorted(existing - set(wanted))
    if check:
        for p in stale + orphans:
            print(f"out of date: {p.relative_to(ROOT)}")
        return 1 if stale or orphans else 0
    OUT.mkdir(parents=True, exist_ok=True)
    for p in stale:
        p.write_text(wanted[p], encoding="utf-8")
    for p in orphans:
        p.unlink()
    print(f"{len(wanted)} icons, {len(stale)} written, {len(orphans)} removed")
    return 0


if __name__ == "__main__":
    sys.exit(main("--check" in sys.argv[1:]))
