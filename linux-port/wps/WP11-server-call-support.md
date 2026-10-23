---
id: WP11
title: "Server: TURN credentials, urgent wake, signal size"
phase: L3
lane: planned
depends_on: [WP02, WP04]
specs: ["05 §5", "06 §3"]
owned_files:
  - "server/cloudflare/src/call.ts (new)"
  - "server/cloudflare/src/mailbox.ts (urgent wake)"
  - "server/cloudflare/src/push.ts"
  - "server/cloudflare/src/protocol.ts"
  - "server/cloudflare/test/**"
  - "server/cloudflare/PROTOCOL.md"
---

# WP11 — Server: TURN credentials, urgent wake, signal size

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

Everything the 1:1 call clients need from the server.

## Steps

1. `GET /v1/call/ice` calling Cloudflare `generate-ice-servers` with Worker secrets; per-caller 10-min cache; strip port 53.
2. `urgent` delivery flag: separate 3-s limiter, high-priority non-collapsible FCM.
3. `MAX_SIGNAL_BYTES` raise only if WP02 says so.

## Acceptance criteria

- **WP11-AC1** ICE endpoint returns servers to members and 401/403 to others; secrets absent from responses/logs (test with mocked upstream).
- **WP11-AC2** Urgent wake bypasses the 20-s coalescing but is limited to 1 per 3 s per recipient; non-urgent behaviour unchanged.
- **WP11-AC3** Deployed to test Worker; live `ice` call succeeds (evidence without credentials).

## Evidence

Write `linux-port/evidence/WP11.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
