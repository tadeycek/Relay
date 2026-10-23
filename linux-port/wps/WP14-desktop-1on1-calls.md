---
id: WP14
title: "Desktop: 1:1 calls"
phase: L3
lane: planned
depends_on: [WP06, WP12]
specs: ["06 §5"]
owned_files:
  - "desktop/**"
---

# WP14 — Desktop: 1:1 calls

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Desktop places and receives calls, rings while in tray, picks audio devices.

## Steps

1. Desktop `MediaAdapter` on `webrtc-java` (platform classifier).
2. Ringing window + tray notification + ring sound; voice bar; device pickers; optional focused push-to-talk.
3. Automated call test with virtual PulseAudio sink/source between two desktop instances.

## Acceptance criteria

- **WP14-AC1** Phone↔desktop call works across networks; TURN-only case works.
- **WP14-AC2** Phone and desktop of the same identity both ring; accepting on one stops the other within 2 s.
- **WP14-AC3** Automated two-desktop call test passes in CI-like headless run with virtual audio devices.
- **WP14-AC4** Wi-Fi→mobile handover on the phone side recovers within 20 s.

## Evidence

Write `linux-port/evidence/WP14.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
