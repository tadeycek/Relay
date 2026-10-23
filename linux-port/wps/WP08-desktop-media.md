---
id: WP08
title: "Desktop: photos and videos"
phase: L2
lane: planned
depends_on: [WP06]
specs: ["04 §3 Media viewing", "09 R14"]
owned_files:
  - "desktop/**"
---

# WP08 — Desktop: photos and videos

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Send and view `TYPE:MEDIA` photos/videos with phone parity.

## Steps

1. Download/decrypt/verify via `:core` media code with `device=` confirm.
2. Inline image viewer (Skia), gallery view, save-as.
3. Choose the video backend: evaluate an in-process player (licence, size, PipeWire audio) vs explicit external open from a private temp file; record decision.
4. Send photos/videos (picker + drag & drop) with the phone's size limits and compression rules.

## Acceptance criteria

- **WP08-AC1** Photo and video from a phone display on desktop; desktop-sent ones display on the phone.
- **WP08-AC2** Media object erased on server only after phone and desktop both confirmed (server logs/metrics).
- **WP08-AC3** Video decision recorded with licence of the chosen component.
- **WP08-AC4** Tampered blob (hash mismatch) is refused and shown as failed.

## Evidence

Write `linux-port/evidence/WP08.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
