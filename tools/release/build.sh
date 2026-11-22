#!/bin/sh
# SPDX-FileCopyrightText: The Coulomb contributors
# SPDX-License-Identifier: GPL-3.0-or-later
# Builds the signed release APK for a GitHub Release (SPEC 15.6, ADR-0067) and checks what it
# built. It publishes nothing: the tag and the release are the owner's step, printed at the end.
#
# usage: tools/release/build.sh
#   with COULOMB_KEYSTORE, COULOMB_KEYSTORE_PASSWORD, COULOMB_KEY_ALIAS and COULOMB_KEY_PASSWORD
#   in the environment, or a local keystore.properties (docs/releasing.md).
set -eu
cd "$(dirname "$0")/../.."

fail() { echo "release: $*" >&2; exit 2; }

# A local keystore.properties (ignored by git; keys storeFile, storePassword, keyAlias, keyPassword)
# can name the key instead. The environment wins, and the build itself still reads only the
# environment (ADR-0067 §1, §7). The file is read as plain key=value lines.
props=keystore.properties
if [ -f "$props" ]; then
  prop() { sed -n "s/^[[:space:]]*$1[[:space:]]*[=:][[:space:]]*//p" "$props" | tr -d '\r' | tail -n 1; }
  [ -n "${COULOMB_KEYSTORE:-}" ] || COULOMB_KEYSTORE=$(prop storeFile)
  [ -n "${COULOMB_KEYSTORE_PASSWORD:-}" ] || COULOMB_KEYSTORE_PASSWORD=$(prop storePassword)
  [ -n "${COULOMB_KEY_ALIAS:-}" ] || COULOMB_KEY_ALIAS=$(prop keyAlias)
  [ -n "${COULOMB_KEY_PASSWORD:-}" ] || COULOMB_KEY_PASSWORD=$(prop keyPassword)
  case "$COULOMB_KEYSTORE" in
    "" | /*) ;;
    *) COULOMB_KEYSTORE="$PWD/$COULOMB_KEYSTORE" ;;
  esac
  export COULOMB_KEYSTORE COULOMB_KEYSTORE_PASSWORD COULOMB_KEY_ALIAS COULOMB_KEY_PASSWORD
fi

for name in COULOMB_KEYSTORE COULOMB_KEYSTORE_PASSWORD COULOMB_KEY_ALIAS COULOMB_KEY_PASSWORD; do
  eval "value=\${$name:-}"
  [ -n "$value" ] || fail "$name is not set"
done
[ -f "$COULOMB_KEYSTORE" ] || fail "COULOMB_KEYSTORE is not a file"

# A release is a commit that passed the pre-push checks, and nothing else.
[ -z "$(git status --porcelain)" ] || fail "the working tree has changes; commit or stash them"
git merge-base --is-ancestor HEAD origin/main || fail "HEAD is not on origin/main; push it first, so its checks have run"

version=$(sed -n 's/^coulomb\.versionName=//p' gradle.properties)
code=$(sed -n 's/^coulomb\.versionCode=//p' gradle.properties)
tag="v$version"
echo "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' || fail "coulomb.versionName '$version' is not MAJOR.MINOR.PATCH"
if git rev-parse -q --verify "refs/tags/$tag" > /dev/null; then
  fail "tag $tag exists; raise coulomb.versionName and coulomb.versionCode in gradle.properties"
fi

./gradlew :app:verifyReleaseManifestPermissions :app:assembleRelease

apk=app/build/outputs/apk/release/app-release.apk
[ -f "$apk" ] || fail "no signed APK at $apk"

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
[ -n "$sdk" ] || fail "ANDROID_HOME is not set"
apksigner=$(ls "$sdk"/build-tools/*/apksigner 2> /dev/null | sort -V | tail -1)
[ -n "$apksigner" ] || fail "no apksigner under $sdk/build-tools"
certs=$("$apksigner" verify --print-certs "$apk" 2> /dev/null) || fail "the APK's signature does not verify"
if echo "$certs" | grep -q 'CN=Android Debug'; then fail "the APK is signed with the debug key"; fi

# SPEC 16.5: the release APK stays under 15 MB.
bytes=$(wc -c < "$apk")
[ "$bytes" -lt 15728640 ] || fail "the APK is $bytes bytes, over the 15 MB limit of SPEC 16.5"

out=build/release
name="coulomb-$version.apk"
mkdir -p "$out"
cp "$apk" "$out/$name"
(cd "$out" && sha256sum "$name" > "$name.sha256")

echo
echo "Built $out/$name: version $version (code $code), $bytes bytes, commit $(git rev-parse --short HEAD)"
echo "$certs" | grep -E 'certificate (DN|SHA-256)'
echo
echo "To publish (the owner's step; nothing here has been pushed or published):"
echo "  git tag -a $tag -m \"Coulomb $version\" && git push origin $tag"
echo "  gh release create $tag $out/$name $out/$name.sha256 --title \"Coulomb $version\" --notes-file <notes.md>"
