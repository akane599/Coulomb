# ADR-0002: Disable lint checks that depend on the release calendar

- Status: accepted
- Date: 2026-11-06
- Phase: P0

## Context

SPEC 15.5 requires Android Lint with warnings as errors. Four lint checks compare the build against
releases published after the commit: `GradleDependency`, `NewerVersionAvailable`,
`AndroidGradlePluginVersion` and `OldTargetApi`. With warnings as errors, any upstream release would
turn an unchanged, green commit red, which breaks SPEC 0.3.4 ("every commit builds").

## Options

1. Keep them as errors: CI fails on release days with no code change.
2. Downgrade them to informational.
3. Disable them and rely on SPEC 0.3.2: versions are resolved from Maven metadata and pinned to the newest
   stable at build time, phase by phase.

## Decision

Option 3, in `config/lint/lint.xml`. Every other lint warning stays an error.

## Consequences

Version freshness is checked when versions are resolved (start of each phase and on library work),
not by lint. Revert by deleting four lines.
