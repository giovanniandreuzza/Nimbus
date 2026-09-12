# Agent guide

Two different jobs, two different files. Pick the one that matches what you are doing.

## Working *on* this repository

Read [`CLAUDE.md`](CLAUDE.md). It is the single source of truth for contributors and agents
alike: architecture rules, the public surface, thread-safety invariants, the error hierarchies,
and a table of design decisions with the reason each one exists. Most of those reasons are a bug
that was paid for once already.

This file deliberately does not repeat any of it. Two documents describing the same rules drift,
and the one you happen to read will be the stale one.

Before changing anything, the short version:

- Dependencies point inward: `presentation` → `core` ← `infrastructure`. Nothing under `core/`
  imports `infrastructure`, and an architecture test enforces it.
- `NimbusAPI` is the only public surface. Everything else is `internal` unless `CLAUDE.md` lists it.
- Nothing throws across the public boundary; every failure is a typed `KResult` value.
- `./gradlew build` must be green — all targets, not just `:nimbus:jvmTest`. Kotlin/Native rejects
  things the JVM accepts, and only the full build finds them.
- Never bump the version by hand. `version.txt`, `CHANGELOG.md` and the README coordinates are
  written by release-please when a release pull request is merged.

## Using Nimbus *as a dependency*

Read [`llms.txt`](llms.txt). It is a complete single-file API reference written for coding
agents: every method with its signature, the full error hierarchy, the state machine, worked
patterns, and a section on the behaviours that are easy to assume wrongly.

Start with that last section. It is short, and each line in it is something a caller would
otherwise discover from a bug report.
