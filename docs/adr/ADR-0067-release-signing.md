# ADR-0067: Release signing and releases

- Status: accepted
- Date: 2026-11-21
- Phase: P6 (SPEC 0.6, 15.6, 16.5)
- Builds on: ADR-0027 (each of these is a one-line decision), ADR-0050 (the benchmark build keeps
  the debug key)

## Context

SPEC 15.6 asks for a release "signed with the owner's key" and for "signed APKs on GitHub Releases
with semantic versions, so the owner can track updates with Obtainium". The key is a secret the
agent does not have, and publishing a release is the owner's (SPEC 0.6). What can be built before
either is the path the key takes into the build and the steps around it.

## Decisions

1. **The key reaches the build through four environment variables and nothing else.**
   `COULOMB_KEYSTORE`, `COULOMB_KEYSTORE_PASSWORD`, `COULOMB_KEY_ALIAS`, `COULOMB_KEY_PASSWORD`.
   No Gradle property and no file in the repository names a key, so a shared `gradle.properties`
   on a machine cannot sign a Coulomb build by accident. Reason: the same four work in a shell and
   as CI secrets, and nothing has to be kept out of a commit.
2. **None of them means an unsigned release; some of them fails the build.** CI and the pre-push
   hook assemble the unsigned APK as before. A half-set environment names what is missing instead
   of producing an APK that looks like a release and is not. Reason: the failure that costs most
   is a wrong APK that goes unnoticed.
3. **A release is built by a script on the owner's machine, not by CI.** `tools/release/build.sh`
   builds from a clean commit that is on `origin/main`, checks the signature, refuses a debug key,
   holds the APK to SPEC 16.5's 15 MB, and writes `coulomb-<version>.apk` with its SHA-256. It
   creates no tag and no release; it prints the two commands. A workflow that signs on GitHub
   would need the key stored there, which is the owner's to decide and can be added later without
   changing the build. Reason: the cheaper and easier to take back of the two.
   Amended 2026-11-23 (audit TEST-003): "on `origin/main`" was taken to mean "its checks have
   run". The pre-push hook checks the working tree, which can hold changes the pushed commit
   lacks, and nobody waits for CI. The script now runs `tools/ci/check.sh all` on the clean
   commit itself before it signs, with the signing variables unset, as CI builds it. It also
   refuses a keystore that lies inside the checkout (audit OPS-001).
4. **Versions stay in `gradle.properties`.** `coulomb.versionName` is MAJOR.MINOR.PATCH and the
   tag is `v` and that name; `coulomb.versionCode` rises by hand with it. The script refuses a
   version whose tag exists. Reason: one place, and no build that depends on git state.
5. **One universal APK per release.** No splits by ABI or density: the only native code is two
   small AndroidX libraries, and the APK with all four ABIs is about 7 MB. Reason: one file is
   what an updater picks without rules.
6. **The signing schemes are the Android Gradle Plugin's defaults for `minSdk` 36** (APK Signature
   Scheme v2). Key rotation, which needs v3, can be turned on with a later release if it is ever
   wanted.

7. **Amended 2026-11-22: the release script also reads a local `keystore.properties`.** The owner
   keeps the key's path and passwords in that file at the repository's root, ignored by git, with
   the keystore itself outside the repository. `tools/release/build.sh` fills any of the four
   variables the environment leaves unset from its `storeFile`, `storePassword`, `keyAlias` and
   `keyPassword` lines; the build still reads only the environment, so CI and the pre-push hook
   are unchanged. Reason: the owner's chosen place for the key, at no cost to §1's guarantee that
   nothing of the key reaches the build any other way.

## Consequences

- A release cannot be installed over a development build, or the other way round; the data moves
  as a bundle and arrives as an imported history (`docs/releasing.md`).
- The release build type is no longer always unsigned: with the variables set, every
  `assembleRelease` on that shell is signed.

## Verified

On the build server, with a throwaway key made for the test and deleted after it: the release
assembles signed, `apksigner verify` accepts it and names the test certificate, and the
passwords appear nowhere in the build's output. The script's refusals for a missing variable and
a missing keystore file were run. Not done: a build with the owner's key, a tag, a release.
