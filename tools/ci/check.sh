#!/bin/sh
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# The Gradle tasks CI runs, in CI's order. CI calls this script step by step and the pre-push hook
# (.githooks/pre-push) calls it with "all", so a push never skips a check CI would run.
#
# usage: tools/ci/check.sh {static|unit|lint|manifest|assemble|all}
set -eu
cd "$(dirname "$0")/../.."

run() { echo "> ./gradlew $*"; ./gradlew "$@"; }

step() {
  case "$1" in
    static)   python3 -I tools/design/icons.py --check
              python3 -I tools/validation/report.py --self-test
              python3 -I tools/ci/translations.py
              tools/ci/secrets-ignored.sh
              run spotlessCheck detekt checkModuleBoundaries ;;
    # The screenshot tests compare against the committed goldens (SPEC 16.4; ADR-0031, audit TEST-001).
    unit)     run test :build-logic:convention:test -Proborazzi.test.verify=true ;;
    lint)     run :app:lintDebug ;;
    manifest) run :app:verifyDebugManifestPermissions :app:verifyReleaseManifestPermissions :app:verifyBenchmarkManifestPermissions ;;
    assemble) run :app:assembleDebug :app:assembleRelease :benchmark:assembleBenchmark ;;
    all)      for s in static unit lint manifest assemble; do step "$s"; done ;;
    *)        echo "unknown step: $1" >&2; exit 2 ;;
  esac
}

step "${1:-all}"
