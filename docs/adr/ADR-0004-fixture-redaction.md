# ADR-0004: Redact personal identifiers in committed fixtures with same-length masks

- Status: accepted; package names amended by ADR-0008
- Date: 2026-11-06
- Phase: P0

## Context

SPEC 16.2 asks for raw dumps from real devices to be committed under `fixtures/` for parser golden
tests. Dumps from the owner's phone contain personal data: account names (an e-mail address, an account
showing the owner's full name, a messaging user id), the home Wi-Fi SSID, and the battery pack's QR/serial
code. SPEC 0.2 puts user data safety first. The spec is silent on redaction.

## Options

1. Commit raw dumps (the repository is private).
2. Keep fixtures out of git.
3. Redact before committing, keeping byte offsets and protobuf framing intact so the parsers see the
   real structure.

## Decision

Option 3. `tools/fixtures/capture.sh` writes raw output outside the repository;
`tools/fixtures/redact.py` writes `fixtures/<device>/<build>/` with same-length masks (letters to `x`/`X`,
digits to `0`, non-ASCII bytes to `x`) for:

- e-mail local parts, and domains unless public providers (`gmail.com`, …); `namespace@package` job
  identifiers are recognised and kept;
- account names in `*sync*/…/…/<name>`, `Account {name=…}` and `accountName=…`;
- international phone numbers, MAC addresses, Wi-Fi SSIDs, the Samsung battery QR/serial, and the
  device serial numbers.

In `batterystats.proto.bin` only string payloads found by walking the protobuf wire format are touched,
so the file stays a valid message (checked after redaction). A post-scan prints anything that still
looks personal; it was reviewed by hand for the first two captures. Raw captures are deleted after
redaction. Installed package names stayed in the first version; ADR-0008 replaces third-party ones with
consistent pseudonyms.

## Consequences

Parsers must never depend on masked values (they are not stable across captures). New personal
patterns found later are added to `redact.py` and existing fixtures are re-generated from a fresh capture.
