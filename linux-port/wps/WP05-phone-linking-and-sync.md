---
id: WP05
title: "Phone: link a computer, link slot, sync channel, sent-copies"
phase: L1
lane: planned
depends_on: [WP03, WP04]
specs: ["04 §4", "05 §3–4", "LP-D03, LP-D06"]
owned_files:
  - "app/src/main/java/com/relay/app/linking/** (new)"
  - "app/src/main/java/com/relay/app/messaging/** (sync hooks)"
  - "app/src/main/java/com/relay/app/crypto/RelayCrypto.kt (rotation ordering)"
  - "app/src/main/java/com/relay/app/ui/screens/account/** (Linked computers)"
  - "core/src/main/kotlin/**/sync/**"
  - "server/cloudflare/src/link.ts (new)"
  - "server/cloudflare/src/index.ts"
  - "server/cloudflare/test/**"
---

# WP05 — Phone: link a computer, link slot, sync channel, sent-copies

## Binding readiness gates

Read [10-readiness-gates.md](../10-readiness-gates.md) before implementation. Its security and compatibility
corrections are mandatory acceptance requirements in addition to the criteria below. A failed or unrun release
gate keeps this WP below `for_review`; recording a blocker is not a passed criterion.

## Goal

The primary phone can link and unlink computers and keeps them in sync, without changing anything contacts see.

## Steps

1. Server link slot (`PUT`/`GET /v1/link/<hash>`), unauthenticated GET path in the edge router, TTL and single-read, IP rate limit.
2. `:core` `LinkBundle` (HPKE seal/open, versioned JSON), `SyncBody` codecs for SENT/READ/KEYS/CONTACT/GROUP/SETTINGS/DELETE.
3. Phone UI: Account → Linked computers (list, link via QR scan, remove), re-auth before linking.
4. Outgoing paths add sent-copy deliveries; uploader adds own pubkey to media recipients.
5. Rotation: send `SYNC KEYS` and wait for `stored` before the rotation broadcast; contact/group/settings changes emit sync events.
6. Phone ignores its own sent-copies by origin; accepts `SYNC` only from own pubkey.

## Acceptance criteria

- **WP05-AC1** Bundle round-trip test: sealed on phone code path, opened by `:core` on JVM; wrong ephemeral key fails; slot read twice → second 404; expired → 404.
- **WP05-AC2** A message sent from the phone appears as outgoing on a JVM test client within one sync cycle; payload id identical.
- **WP05-AC3** Rotation test: contact message encrypted to the new key is processed by the linked client after `SYNC KEYS` (seq order asserted).
- **WP05-AC4** `TYPE:SYNC` from a non-self sender is dropped and logged.
- **WP05-AC5** Unlink closes the device's sockets (`4009`) and stops retaining messages for it.
- **WP05-AC6** No change for contacts: a 1.15 emulator contact exchanges messages with the linked identity normally (harness).

## Evidence

Write `linux-port/evidence/WP05.md`: exact commands, numbers parsed from reports, versions/digests of new
dependencies, and a **Not run** section (real phone, Mac, live provider, etc.). Touching files outside `owned_files` is a
scope decision — note it in the evidence and the PR.
