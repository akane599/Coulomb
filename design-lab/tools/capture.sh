#!/bin/sh
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Captures every lab screen in light and dark: the 412 x 915 viewport at 2x and the whole page at 1x.
# Usage: design-lab/tools/capture.sh <out-dir>    (CHROME overrides the browser binary)
set -eu
out=$1
lab=$(cd "$(dirname "$0")/.." && pwd)
chrome=${CHROME:-google-chrome}
mkdir -p "$out"

shoot() { # page theme file height scale
  flags=""
  [ "$2" = dark ] && flags="--force-dark-mode"
  # shellcheck disable=SC2086
  "$chrome" --headless=new --disable-gpu --hide-scrollbars --no-first-run --force-device-scale-factor="$5" $flags \
    --window-size=412,"$4" --virtual-time-budget=3000 --screenshot="$out/$3" "file://$lab/$1.html" >/dev/null 2>&1
}

for spec in now:1732 drain:1452 app-detail:2331 timeline:1057 health:2360 charging:1010 onboarding:1311; do
  page=${spec%%:*}
  full=${spec##*:}
  for theme in light dark; do
    shoot "$page" "$theme" "$page-$theme.png" 915 2
    shoot "$page" "$theme" "$page-$theme-full.png" "$full" 1
  done
done
