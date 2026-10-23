---
id: WP02
title: "Spike: WebRTC Android↔Linux audio, Cloudflare TURN and SFU, signal sizes"
phase: L0
lane: doing
depends_on: []
specs: ["02 §C–E", "06 §1–3", "07 §3, §6"]
owned_files:
  - "linux-port/spikes/calls/**"
  - "linux-port/evidence/WP02.md"
---

# WP02 — Spike: WebRTC Android↔Linux audio, Cloudflare TURN and SFU, signal sizes

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Retire the call unknowns: library behaviour on both platforms, Cloudflare account setup on the free plan, real frame sizes, and frame-encryption interop.

## Steps

1. Enable Realtime on the owner's Cloudflare account: create a TURN key and an SFU app; record whether a payment method was required (no secrets in evidence).
2. Minimal Android app with `io.github.webrtc-sdk:android` and a minimal `webrtc-java` 0.20.0 desktop program; exchange SDP manually or through a throwaway Worker; P2P audio both ways.
3. Force relay (`iceTransportPolicy=relay`) through Cloudflare TURN; confirm audio and that `turns:443` works with UDP blocked (`iptables` on the Linux side).
4. Both clients publish `mic` to the SFU and subscribe to each other through `sessions/new` / `tracks/new` / `renegotiate` from a throwaway Worker.
5. Measure: deflated SDP size, gift-wrapped `TYPE:CALL` frame size with/without inner Tink layer; audio egress per minute in the Realtime dashboard.
6. Frame-encryption probe: Android `FrameCryptor` (AES-GCM, shared key) → SFU → desktop encoded transform; can the desktop decrypt? and reverse.

## Acceptance criteria

- **WP02-AC1** P2P audio Android↔Linux both directions, with and without forced TURN; UDP-blocked case connects via TLS 443.
- **WP02-AC2** SFU publish/subscribe works from both libraries; per-minute egress recorded and compared to the 02 §E estimate.
- **WP02-AC3** Measured `offer`/`answer`/`ice` frame sizes recorded; decision recorded whether `MAX_SIGNAL_BYTES` must rise.
- **WP02-AC4** Whether the free Realtime allowance needed a card is recorded (yes/no + date).
- **WP02-AC5** Frame-encryption interop result recorded as pass/fail with the observed frame layout; WP17 gate updated accordingly.
- **WP02-AC6** Audio device enumeration on Ubuntu 24.04 (PipeWire) recorded.

## Progress

- 2026-10-23 UTC — Live Android↔Linux Cloudflare TURN/TLS443 independently rerun, including both real silent-source
  mutations (3 live native tests) and local regression (3). [Evidence](../evidence/WP02-live-turn.md).
  Independent UDP-blocking, live SFU/E2EE and hardware gates remain open; lane stays `doing`.

- 2026-10-23 UTC — Actual Android↔Linux native generated audio independently rerun; positive and both silent-source
  mutations passed (3 native integration tests). [Parent evidence](../evidence/WP02-cross-platform.md); live/hardware gates open.

- 2026-10-23 UTC — Native Linux generated-audio exchange, Android native init and actual encrypted signalling
  sizes verified. Android↔Linux audio, live TURN/SFU and E2EE interop remain open; see [evidence](../evidence/WP02.md).

## Evidence

Write `linux-port/evidence/WP02.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
