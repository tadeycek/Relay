---
id: WP13
title: "Android: 1:1 calls (WebRTC, Telecom, FCM ring)"
phase: L3
lane: doing
depends_on: [WP11, WP12]
specs: ["06 §1, §4", "02 §C", "09 R6, Q1, Q2"]
owned_files:
  - "app/build.gradle"
  - "app/src/main/AndroidManifest.xml"
  - "app/src/main/java/com/relay/app/calls/** (new)"
  - "app/src/main/java/com/relay/app/messaging/RelayMessagingService.kt"
  - "app/src/main/java/com/relay/app/ui/** (call screens, call button)"
  - "gradle/verification-metadata.xml"
---

# WP13 — Android: 1:1 calls (WebRTC, Telecom, FCM ring)

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Phone can place and receive E2EE voice calls, including when asleep.

## Steps

1. Android `MediaAdapter` on `io.github.webrtc-sdk:android`.
2. core-telecom self-managed calls, `phoneCall` FGS, CallStyle notifications, full-screen intent with capability check.
3. FCM urgent path: direct socket fetch inside the callback, ring on fresh invite.
4. Call-only activity respecting the calculator lock (Q1 default), call events in conversation.
5. Permissions flow (mic, notifications, Bluetooth).

## Acceptance criteria

- **WP13-AC1** Phone↔phone call on two networks, 10 min, audio both ways (real phones).
- **WP13-AC2** Screen-off, app swiped away: incoming call rings; median send→ring latency over 10 trials recorded (target ≤5 s) for Wi-Fi and mobile data.
- **WP13-AC3** Answering from lock screen does not expose chats; opening chat requires the code.
- **WP13-AC4** Bluetooth headset routing and speaker toggle work (real device).
- **WP13-AC5** No entries in the system call log (checked).

## Evidence

Write `linux-port/evidence/WP13.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.

<!-- 2026-10-24 UTC: WP13 moved to doing; implementation/evidence incomplete, release gates remain open. -->
