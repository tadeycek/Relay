---
id: WP04
title: "Server: per-device cursors, dormancy, per-device media confirmation"
phase: L1
lane: doing
depends_on: []
specs: ["05 §1–2", "02 §E rows", "LP-D02, LP-D14"]
owned_files:
  - "server/cloudflare/src/mailbox.ts"
  - "server/cloudflare/src/media.ts"
  - "server/cloudflare/src/protocol.ts"
  - "server/cloudflare/src/index.ts"
  - "server/cloudflare/test/**"
  - "server/cloudflare/PROTOCOL.md"
  - "server/cloudflare/README.md"
  - "linux-port/evidence/WP04.md"
---

# WP04 — Server: per-device cursors, dormancy, per-device media confirmation

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

One queue per identity, deleted only after every active device has acked; media erased only after every active device confirmed; fully backward compatible.

## Steps

1. Migration adding `devices`; `device` query parameter on inbox/ack/ws/media; allowlist update.
2. Ack path: update cursor, delete `seq ≤ min(active acked)`; dormancy 72 h; `gap` flag on ready/inbox.
3. `POST/GET/DELETE /v1/devices` (primary only), head seq in reply, socket close `4009`.
4. Media: recipient `(pubkey, device)` snapshot at PUT from Mailbox DO; per-device DELETE.
5. `EXPOSE_METRICS=1` measurement of rows for: delivery, ack with 1 and 2 devices, device registration, media confirm with 2 devices.

## Acceptance criteria

- **WP04-AC1** Legacy client test: a client sending no `device` behaves exactly as before (existing suite unchanged and green).
- **WP04-AC2** Two devices: message deleted only after both ack; one device dormant >72 h no longer blocks deletion; returning dormant device gets `gap:true`.
- **WP04-AC3** Non-primary cannot register/remove devices (`403 not_primary`); unknown device refused; device cap enforced.
- **WP04-AC4** Media object with two recipient devices survives the first confirm and is erased after the second; legacy recipient (no devices) unchanged.
- **WP04-AC5** Measured rows per operation recorded; projected daily rows for 10 users / 3 desktops ≤ 40k.
- **WP04-AC6** Deployed to `relay-mailbox-test`; pilot probe green for 48 h with 1.15 phones (evidence).

## Evidence

Write `linux-port/evidence/WP04.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
