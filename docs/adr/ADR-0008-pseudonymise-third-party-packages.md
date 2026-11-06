# ADR-0008: Pseudonymise third-party package names in phone fixtures

- Status: accepted
- Date: 2026-11-06
- Phase: P1
- Amends: ADR-0004 (which kept every package name)

## Context

The repository will become public. The phone fixtures list every installed app by package name, and the
apps' own class names, job tags and FCM services name them again. Together they describe what the owner
uses (banking, messaging, VPN, root tools). Attribution parsers still need real structure: the same UID
must always carry the same package, process names keep their `package:process` form, and the protobuf
fixture must stay a valid message. The owner decided on 2026-11-06: pseudonymise third-party package
names with a consistent mapping, keep the owner's own apps' names.

## Decision

`tools/fixtures/redact.py` pseudonymises package names after the ADR-0004 masks, in text files and in
protobuf string payloads, always with same-length replacements:

- **Which packages.** Every package seen in the capture (checkin UID map and `apk` lines, history `top`,
  `fg`, `pkginst`, `pkgunin` and `active` events, `pm list packages -3`) that is not in the device's
  `pm list packages -s` output. Uninstalled apps that only appear in history are therefore covered too.
  Kept: system packages and the owner's own prefixes (`--keep-prefix`, default `io.github.akane599.`
  and `com.akane.`). `capture.sh` saves both package lists next to `.secrets`; they never enter the
  repository, and `redact.py` refuses to run without the system list.
- **How.** Each dotted segment becomes a random segment with the same length and character classes;
  segments that identify nothing (`com`, `org`, `io`, `android`, `github`, `webapk`, …) stay. Prefixes
  map consistently, so `com.example.app` and `com.example.app.pro` share a pseudonymous prefix. The
  app's own namespace is replaced through those prefixes (`com.example.push.Service`) unless a system
  or kept package shares the prefix. UIDs are not changed.
- **Brand words.** A hand-reviewed list of brand words found outside package names (class names,
  worker tags, library names) is replaced case-preservingly. The list names apps, so it is kept with
  the mapping, outside the repository.
- **Consistency.** The mapping lives outside the repository (`~/.config/coulomb-fixtures/`, mode 0600)
  and is reused and extended by every later capture, so one app has one pseudonym in every file and
  every capture.
- **Protobuf.** A length-delimited payload that is both printable and a valid nested message is
  processed in both readings, innermost first, so package strings inside one-field messages are found.
  The fixture was checked to stay a well-formed message of identical size.

Existing fixtures are converted with `redact.py --in-place <fixture-dir> --system-packages <file>`.
The emulator fixture holds only stock system packages and is unchanged.

## Consequences

Parsers and tests must not depend on third-party package names; they may depend on UIDs, system package
names and the shape of names. Losing the mapping means a new capture gets new pseudonyms, which only
matters for comparing captures. Git history before this ADR still holds the real names; publishing the
repository needs a clean history (`docs/questions.md`).
