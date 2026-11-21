#!/usr/bin/env python3
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Checks that every string a module shows exists in Turkish too (SPEC 11.10).

Lint's MissingTranslation looks at one module at a time and says nothing about a module that has
no translation at all, so a module could ship English-only strings unnoticed. This walks every
`src/main/res/values/*.xml` and fails when a `string` or `plurals` name that is translatable has
no counterpart under `values-tr`, or when `values-tr` names something `values` does not have.

usage: python3 -I tools/ci/translations.py
"""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SKIP = {"build", ".gradle", "design-lab", "node_modules"}


def names(folder: Path) -> set[str]:
    found: set[str] = set()
    if not folder.is_dir():
        return found
    for file in sorted(folder.glob("*.xml")):
        for item in ET.parse(file).getroot():
            if item.tag in ("string", "plurals") and item.get("translatable") != "false":
                found.add(item.get("name"))
    return found


def main() -> int:
    problems: list[str] = []
    for values in sorted(ROOT.glob("**/src/main/res/values")):
        if SKIP.intersection(values.relative_to(ROOT).parts):
            continue
        base = names(values)
        turkish = names(values.parent / "values-tr")
        module = values.relative_to(ROOT).parts[:-4]
        label = "/".join(module)
        problems += [f"{label}: '{name}' has no Turkish text" for name in sorted(base - turkish)]
        problems += [f"{label}: '{name}' is only in values-tr" for name in sorted(turkish - base)]
    for line in problems:
        print(line, file=sys.stderr)
    if problems:
        print(f"{len(problems)} translation problem(s)", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
