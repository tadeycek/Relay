---
id: WP17
title: "Group call E2EE (frame encryption) — gated"
phase: L4
lane: planned
depends_on: [WP16]
specs: ["07 §6", "LP-D16", "09 R9"]
owned_files:
  - "core/src/main/kotlin/**/calls/e2ee/**"
  - "app/src/main/java/com/relay/app/calls/**"
  - "desktop/**"
---

# WP17 — Group call E2EE (frame encryption) — gated

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Make group audio unreadable to the SFU, if both platforms can share one frame format.

## Steps

1. Implement the frame format agreed in the WP02 probe on desktop (encoded transform) to match Android `FrameCryptor`.
2. Per-sender HKDF keys from `e2eeKey`; key in `group_start`.
3. Optional: rekey on leave.
4. Remove the "in transit" label only when enabled for every participant; mixed rooms keep the label.

## Acceptance criteria

- **WP17-AC1** Android→desktop and desktop→Android audio decrypts through the real SFU with E2EE on.
- **WP17-AC2** With a wrong key, audio is silence/garbled (test) — proving frames are actually encrypted.
- **WP17-AC3** If the gate fails, evidence records why and LP-D16 stays "not shipped"; UI label unchanged.

## Evidence

Write `linux-port/evidence/WP17.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
