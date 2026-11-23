#!/bin/sh
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Raw captures name apps, accounts and the device: what a capture script writes is readable by its
# owner only (audit SEC-002). Runs the fixture capture against a stand-in adb under an open umask and
# checks the modes; the other capture scripts carry the same three lines.
#
# usage: tools/ci/capture-modes.sh
set -eu
cd "$(dirname "$0")/../.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir "$tmp/bin"
printf '#!/bin/sh\necho x\n' > "$tmp/bin/adb"
chmod +x "$tmp/bin/adb"
(
  umask 022
  PATH="$tmp/bin:$PATH" bash tools/fixtures/capture.sh stub "$tmp/raw"
) > /dev/null 2>&1 || true
[ -d "$tmp/raw" ] || { echo "the capture wrote nothing" >&2; exit 1; }
mode=$(stat -c %a "$tmp/raw")
[ "$mode" = 700 ] || { echo "the raw folder's mode is $mode, not 700" >&2; exit 1; }
open=$(find "$tmp/raw" -perm /077)
[ -z "$open" ] || { echo "readable by others: $open" >&2; exit 1; }
# A link in place of the folder is refused, not followed.
mkdir "$tmp/elsewhere"
ln -s "$tmp/elsewhere" "$tmp/link"
if PATH="$tmp/bin:$PATH" bash tools/fixtures/capture.sh stub "$tmp/link" > /dev/null 2>&1; then
  echo "a symbolic link was accepted as the raw folder" >&2
  exit 1
fi
[ -z "$(ls -A "$tmp/elsewhere")" ] || { echo "the capture wrote through a symbolic link" >&2; exit 1; }
