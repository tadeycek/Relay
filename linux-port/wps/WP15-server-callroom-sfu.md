---
id: WP15
title: "Server: CallRoom Durable Object and SFU proxy"
phase: L4
lane: planned
depends_on: [WP02, WP11]
specs: ["05 §5", "07 §1–5", "LP-D11, LP-D12, LP-D13"]
owned_files:
  - "server/cloudflare/src/callroom.ts (new)"
  - "server/cloudflare/src/call.ts"
  - "server/cloudflare/src/index.ts"
  - "server/cloudflare/wrangler.jsonc (new DO class + migration)"
  - "server/cloudflare/test/**"
  - "server/cloudflare/PROTOCOL.md"
---

# WP15 — Server: CallRoom Durable Object and SFU proxy

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its corrections are mandatory
acceptance requirements. An unrun or failed release gate keeps this WP below `for_review`.

## Goal

Rooms with capability-token auth, participant/track directory and a safe proxy to the Cloudflare SFU.

## Steps

1. `CallRoom` SQLite DO with hibernatable sockets; create/join/state/leave/end; storage wipe on empty.
2. SFU proxy endpoints with per-pid session ownership; per-session mutation serialisation.
3. Room existence check `GET /v1/call/rooms/<room>`; creator-only `end`.
4. Rows/request metrics for a scripted 5-participant join/leave sequence.

## Acceptance criteria

- **WP15-AC1** Wrong secret, non-member, or foreign session id are refused (tests with mocked SFU).
- **WP15-AC2** Room wipes itself when the last participant leaves; existence check returns 404 after.
- **WP15-AC3** 17-participant cap enforced; reconnect within 30 s keeps pid.
- **WP15-AC4** Measured DO requests and rows for the scripted sequence recorded.
- **WP15-AC5** Secrets never appear in responses or logs (test).

## Evidence

Write `linux-port/evidence/WP15.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
