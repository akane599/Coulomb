# Releasing

How a signed release is made (SPEC 15.6; the decisions are in
`docs/adr/ADR-0067-release-signing.md`). Publishing is the owner's step. Nothing in the build or in
CI publishes anything.

## The key

The release key exists once and is never in the repository. Whoever holds it can publish updates
that Android accepts over an installed Coulomb, and nobody else can; if it is lost, no update can
ever be installed over the releases signed with it. Keep the keystore file and its password in two
places.

To create it (once, on the machine that will build releases):

```sh
keytool -genkeypair -keystore <path>/coulomb-release.p12 -storetype PKCS12 \
  -alias coulomb -keyalg RSA -keysize 4096 -validity 10000
```

A PKCS12 keystore has one password: the key's password is the keystore's.

The build reads the key from the environment and from nowhere else:

| Variable | Content |
| --- | --- |
| `COULOMB_KEYSTORE` | Path of the keystore file |
| `COULOMB_KEYSTORE_PASSWORD` | Its password |
| `COULOMB_KEY_ALIAS` | The key's alias (`coulomb` above) |
| `COULOMB_KEY_PASSWORD` | The key's password (the same one, for PKCS12) |

`tools/release/build.sh` can take them from a `keystore.properties` file at the repository's root
instead (ignored by git; lines `storeFile=`, `storePassword=`, `keyAlias=`, `keyPassword=`, with the
keystore itself kept outside the repository). A variable set in the environment wins over the file.
The build itself reads only the environment.

With none of them set, `:app:assembleRelease` leaves the APK unsigned
(`app-release-unsigned.apk`), which is what CI builds. With only some of them set the build fails
and names the missing ones.

## A release

1. Raise `coulomb.versionName` (MAJOR.MINOR.PATCH) and `coulomb.versionCode` (always by at least
   one) in `gradle.properties`, commit, and push. The pre-push hook runs everything CI runs.
2. With the four variables set, run `tools/release/build.sh`. It refuses a working tree with
   changes, a commit that is not on `origin/main`, a version whose tag exists, an APK that does
   not verify or is signed with a debug key, and an APK of 15 MB or more (SPEC 16.5). It writes
   `build/release/coulomb-<version>.apk` with its SHA-256 file and prints the signing
   certificate's digest.
3. Publish: create the tag `v<version>`, push it, and create the GitHub Release with the two
   files. The script prints both commands and runs neither.

The certificate digest the script prints is the same for every release. It is worth stating in the
first release's notes, so that anyone can check a download with
`apksigner verify --print-certs`.

## Updates through Obtainium

Coulomb has no network access and never looks for updates itself. In Obtainium, add the
repository's URL; it follows the GitHub Releases and installs the APK attached to the newest one.
Each release carries one APK for every device.

## Moving from a development build

A development build is signed with a debug key, so Android refuses to install a release over it,
and the other way round. To change from one to the other, the installed one has to be removed,
which deletes its data. Before that, export a Coulomb bundle (Settings, "Data and export") and
import it afterwards: the earlier history is then kept as an imported history beside the new
one, not as this phone's own figures (ADR-0062 §9).
