---
id: WP12
title: "`:core` 1:1 call engine and `TYPE:CALL` codec"
phase: L3
lane: doing
depends_on: [WP03]
specs: ["05 §6", "06 §2–3", "LP-D09, LP-D10"]
owned_files:
  - "core/src/main/kotlin/**/calls/**"
  - "core/src/test/kotlin/**/calls/**"
---

# WP12 — `:core` 1:1 call engine and `TYPE:CALL` codec

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Platform-free state machine and signalling that both clients drive.

## Steps

1. `CallBody` codec (deflate SDP, candidate batching, validation, freshness).
2. `CallEngine1to1` with `MediaAdapter`/`SignalSink`/`Clock` interfaces.
3. Fake-adapter tests for every transition, glare, accept race, ICE restart, timeouts.

## Acceptance criteria

- **WP12-AC1** Unit tests for each transition in 06 §2 (named per transition) pass; glare and accept-race tests included.
- **WP12-AC2** Mutation check: disabling the accept-race guard fails its test (recorded).
- **WP12-AC3** Encoded frame sizes for a recorded real SDP (from WP02) ≤14 KiB after wrapping, asserted in a test.

## Evidence

Write `linux-port/evidence/WP12.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.

<!-- 2026-10-24 UTC: WP12 moved to doing; implementation/evidence incomplete, release gates remain open. -->
