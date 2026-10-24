---
id: WP07
title: "Desktop: groups, profiles, Discord-style UI completion"
phase: L2
lane: doing
depends_on: [WP06]
specs: ["04 §3", "01 goal 3"]
owned_files:
  - "desktop/**"
---

# WP07 — Desktop: groups, profiles, Discord-style UI completion

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its corrections are mandatory
acceptance requirements. An unrun or failed release gate keeps this WP below `for_review`.

## Goal

Groups (v2 direct delivery), profile names/photos and the full desktop interaction model.

## Steps

1. Group list, group conversation, members panel, held-event replay as on phone (use `:core GroupProtocol`).
2. Profile names and photos (request/receive), avatars.
3. Quick switcher (Ctrl+K), unread counts, keyboard shortcuts, settings screen, about/licences.
4. Desktop notification actions (open conversation).

## Acceptance criteria

- **WP07-AC1** Group message from a phone member appears on desktop; desktop post reaches every member directly (harness with 3 emulators + desktop).
- **WP07-AC2** Roster changes made on the primary phone appear on desktop via sync.
- **WP07-AC3** Compose UI tests cover sending, switching conversations and the quick switcher.
- **WP07-AC4** Screenshot set of the main screens attached to evidence.

## Evidence

Write `linux-port/evidence/WP07.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.

<!-- 2026-10-24 UTC: WP07 moved to doing; implementation/evidence incomplete, release gates remain open. -->
