#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Writes the colour tokens of `DESIGN.md` into Kotlin (SPEC 11.4, 11.12 step 5; ADR-0024).

    python3 -I tools/design/tokens.py            # rewrite ColorTokens.kt
    python3 -I tools/design/tokens.py --check    # exit 1 if ColorTokens.kt is stale

`DESIGN.md`'s frontmatter is the normative source. Its colours are OKLCH, keyed `<token>-light`,
`<token>-dark` and, where true black differs from dark, `<token>-black`. Each is converted to 8-bit
sRGB with Björn Ottosson's OKLab matrices and the sRGB transfer function. A colour outside sRGB is
clipped per channel, which is what CSS Color 4 gamut mapping returns when the clipped colour is
within its just-noticeable difference (deltaEOK 0.02) of the original. A colour further out stops
the script: the token itself has to change.

`DesignTokensTest` in :core:designsystem converts the same values independently and fails the
build when this file and `DESIGN.md` disagree.
"""
from __future__ import annotations

import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESIGN = ROOT / "DESIGN.md"
OUT = ROOT / "core/designsystem/src/main/kotlin/io/github/akane599/coulomb/core/designsystem/theme/ColorTokens.kt"
JND = 0.02
COLOR = re.compile(r'^  ([a-z0-9-]+):\s*"oklch\(([0-9.]+) ([0-9.]+) ([0-9.]+)\)"\s*$')
MODES = ("light", "dark", "black")


def frontmatter_colors(text: str) -> dict[str, tuple[float, float, float]]:
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        raise SystemExit("DESIGN.md has no frontmatter")
    end = lines.index("---", 1)
    colors: dict[str, tuple[float, float, float]] = {}
    inside = False
    for line in lines[1:end]:
        if not line.startswith(" "):
            inside = line.rstrip() == "colors:"
            continue
        if not inside or not line.strip() or line.strip().startswith("#"):
            continue
        m = COLOR.match(line)
        if not m:
            raise SystemExit(f"unexpected colour line in DESIGN.md: {line!r}")
        colors[m.group(1)] = (float(m.group(2)), float(m.group(3)), float(m.group(4)))
    if not colors:
        raise SystemExit("DESIGN.md has no colors")
    return colors


def oklab_to_linear(lab: tuple[float, float, float]) -> tuple[float, float, float]:
    big_l, a, b = lab
    l_ = big_l + 0.3963377774 * a + 0.2158037573 * b
    m_ = big_l - 0.1055613458 * a - 0.0638541728 * b
    s_ = big_l - 0.0894841775 * a - 1.2914855480 * b
    l, m, s = l_**3, m_**3, s_**3
    return (
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )


def cbrt(x: float) -> float:
    return math.copysign(abs(x) ** (1 / 3), x)


def linear_to_oklab(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    r, g, b = rgb
    l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    return (
        0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
        1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
        0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
    )


def encode(c: float) -> int:
    v = 12.92 * c if c <= 0.0031308 else 1.055 * c ** (1 / 2.4) - 0.055
    return math.floor(v * 255 + 0.5)  # half up, as DesignTokensTest rounds


def argb(name: str, lch: tuple[float, float, float]) -> int:
    big_l, c, h = lch
    lab = (big_l, c * math.cos(math.radians(h)), c * math.sin(math.radians(h)))
    linear = oklab_to_linear(lab)
    r0, g0, b0 = (min(1.0, max(0.0, v)) for v in linear)
    clipped = (r0, g0, b0)
    if clipped != linear:
        back = linear_to_oklab(clipped)
        delta = math.dist(lab, back)
        if delta >= JND:
            raise SystemExit(f"{name} is outside sRGB by deltaEOK {delta:.4f}, past the JND {JND}")
    r, g, b = (encode(v) for v in clipped)
    return 0xFF000000 | (r << 16) | (g << 8) | b


def camel(slug: str) -> str:
    head, *rest = slug.split("-")
    return head + "".join(p[:1].upper() + p[1:] for p in rest)


def render(colors: dict[str, tuple[float, float, float]]) -> str:
    modes: dict[str, dict[str, int]] = {m: {} for m in MODES}
    for key, lch in colors.items():
        token, _, mode = key.rpartition("-")
        if mode not in MODES or not token:
            raise SystemExit(f"colour key {key!r} does not end in -light, -dark or -black")
        modes[mode][token] = argb(key, lch)
    if modes["light"].keys() != modes["dark"].keys():
        raise SystemExit(f"light and dark differ in tokens: {sorted(modes['light'].keys() ^ modes['dark'].keys())}")
    if not modes["black"].keys() <= modes["dark"].keys():
        raise SystemExit(f"black-only tokens: {sorted(modes['black'].keys() - modes['dark'].keys())}")

    def palette(name: str, values: dict[str, int]) -> list[str]:
        out = [f"internal val {name}: CoulombColors =", "    CoulombColors("]
        out += [f"        {camel(t)} = Color(0x{v:08X})," for t, v in values.items()]
        return out + ["    )", ""]

    black = {t: modes["black"].get(t, v) for t, v in modes["dark"].items()}
    lines = [
        "// SPDX-FileCopyrightText: The Coulomb contributors",
        "// SPDX-License-Identifier: GPL-3.0-or-later",
        "",
        "package io.github.akane599.coulomb.core.designsystem.theme",
        "",
        "import androidx.compose.ui.graphics.Color",
        "",
        "// Generated by tools/design/tokens.py from DESIGN.md (ADR-0024). Do not edit: change DESIGN.md",
        "// and run `python3 -I tools/design/tokens.py`. DesignTokensTest checks the two agree.",
        "",
        "/** SPEC 11.4 tokens for the light theme, in sRGB. */",
        *palette("LightColors", modes["light"]),
        "/** SPEC 11.4 tokens for the default dark theme: tinted near-black (SPEC 11.11). */",
        *palette("DarkColors", modes["dark"]),
        "/** The opt-in true black theme: dark, with a black `bg` and the inks that follow it (SPEC 11.4). */",
        *palette("BlackColors", black),
    ]
    return "\n".join(lines).rstrip("\n") + "\n"


def main() -> int:
    check = sys.argv[1:] == ["--check"]
    if sys.argv[1:] and not check:
        raise SystemExit(__doc__)
    text = render(frontmatter_colors(DESIGN.read_text(encoding="utf-8")))
    if check:
        if not OUT.exists() or OUT.read_text(encoding="utf-8") != text:
            print(f"{OUT.relative_to(ROOT)} is stale; run python3 -I tools/design/tokens.py", file=sys.stderr)
            return 1
        return 0
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
