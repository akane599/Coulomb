# ADR-0012: GPL-3.0-or-later, with SPDX headers

- Status: accepted (owner decision)
- Date: 2026-11-06
- Phase: P1 (publish prep)

## Context

The owner chose the license before publishing: GPL-3.0-or-later, keeping the planned Apache-2.0
`NOTICE` for the AOSP protos (SPEC 5.1). Apache-2.0 code can be combined into a GPL-3.0 work.

## Decision

- `LICENSE` holds the GPL-3.0 text from gnu.org (SHA-256 `3972dc97…6986`).
- Every source and configuration file Coulomb authors starts with:

  ```
  SPDX-FileCopyrightText: The Coulomb contributors
  SPDX-License-Identifier: GPL-3.0-or-later
  ```

  The holder line names the project, not a person; there is no year to keep up to date. Spotless adds
  and checks the header in Kotlin and Gradle Kotlin files (`config/spotless/license-header.txt`).
  Other file types carry it as a comment. Files that cannot hold a comment (Markdown, fixtures,
  binaries) are covered by `REUSE.toml`, so the tree follows the REUSE specification.
- The Gradle wrapper files keep their own Apache-2.0 notice (`REUSE.toml`).
- License texts live in `LICENSES/` (REUSE layout). `LICENSES/Apache-2.0.txt` is the apache.org text
  (SHA-256 `cfc7749b…3d30`).
- When P2 vendors the AOSP `batterystats` protos, they keep their AOSP headers, `REUSE.toml` marks
  them Apache-2.0, and a `NOTICE` file credits the Android Open Source Project.

## Consequences

Anyone may fork and redistribute Coulomb; modified versions they distribute stay GPL-3.0-or-later.
A new file without the header fails `spotlessCheck` (Kotlin) or the `reuse lint` check before
publishing. Changing the license later needs the agreement of every contributor.
