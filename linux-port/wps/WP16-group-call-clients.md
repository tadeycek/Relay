---
id: WP16
title: "Group voice on phone and desktop"
phase: L4
lane: planned
depends_on: [WP13, WP14, WP15]
specs: ["07 §2–5, §7"]
owned_files:
  - "core/src/main/kotlin/**/calls/group/**"
  - "app/src/main/java/com/relay/app/calls/**"
  - "app/src/main/java/com/relay/app/ui/** (voice banner, group call screen)"
  - "desktop/**"
---

# WP16 — Group voice on phone and desktop

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its corrections are mandatory
acceptance requirements. An unrun or failed release gate keeps this WP below `for_review`.

## Goal

Join/leave voice in any group with mute, deafen, speaking indicators and honest encryption labelling.

## Steps

1. `:core GroupCallEngine` (room socket protocol, subscription diffing, reconnect).
2. Announcement via group fan-out (`group_start`/`group_end`), voice-active banner, stale clear.
3. Android: Telecom + FGS reuse, group call screen; Desktop: voice bar participants list, per-user volume.
4. Roster check flagging non-members; "Encrypted in transit" label until WP17.

## Acceptance criteria

- **WP16-AC1** 5 participants (≥1 desktop, ≥2 real phones), 15 min, with two leaves and one rejoin mid-call; everyone hears everyone (evidence with device list).
- **WP16-AC2** Muted participant's track is not garbage-collected after 60 s muted.
- **WP16-AC3** A participant not on a client's roster copy is flagged on that client.
- **WP16-AC4** Measured SFU egress for the session compared with 02 §E estimate.

## Evidence

Write `linux-port/evidence/WP16.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
