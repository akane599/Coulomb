#!/bin/sh
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Key material and its settings stay out of every commit (ADR-0067, audit OPS-001): the names a
# release key may have, and the file that names it, must be ignored by git.
#
# usage: tools/ci/secrets-ignored.sh
set -eu
cd "$(dirname "$0")/../.."
for f in release.p12 release.pfx release.jks release.keystore release.pem release.key keystore.properties; do
  git check-ignore -q --no-index "$f" || { echo "not ignored by git: $f" >&2; exit 1; }
done
