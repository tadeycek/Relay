# Cloudflare mailbox: C0 local prototype and C1 test pilot — 2026-10-18

Issues #42 (C0) and #43 (C1). The mailbox in `server/cloudflare` is a Worker plus one SQLite-backed Durable
Object on Workers Free. It carries the app's existing NIP-17 gift wraps unchanged; see
`server/cloudflare/PROTOCOL.md`. The Android app is not changed and still uses Nostr relays.

Decisions this builds on (2026-10-18): Tor mode will be turned off for the pilot; writes are authenticated, so
the server sees who sends to whom; chat history stays on the phones, and the server deletes a message as soon as
the recipient confirms it, keeping no record of it.

## C0: local evidence

`npm test` runs 74 tests inside the local Workers runtime:

- **Privacy.** Anonymous, forged, stale, future, replayed, wrong-URL, wrong-method, wrong-body and
  non-allowlisted requests are refused on every route and on the socket upgrade. A recipient can't read,
  receive pushes from, or acknowledge anyone else's mailbox, and naming another recipient in a query is refused.
- **Real crypto.** nostr-tools NIP-17 wraps: the recipient opens them and checks the seal signer, and a third
  identity can't. Tested for direct messages and for a 9-recipient fan-out.
- **Durability.** Identical retries are recognised while the message waits, reused ids with different bytes
  are refused, catch-up is paged by count and bytes, receipts are cumulative, delete the messages and only touch
  the caller's queue, and a lost ACK replays.
- **Failures.** An injected SQL failure mid-store rolls back completely and is reported as retryable. A failed
  catch-up read returns 503, never an empty inbox. A failed receipt changes nothing.
- **Limits.** Envelope, per-recipient and total caps refuse new work and keep everything already accepted.
- **Restarts.** Messages survive eviction. A hibernated socket is served after eviction from its tag and
  attachment alone. A dropped socket catches up on reconnect. (This is the runtime's local eviction, separate
  from the live check below.)
- **Migrations.** A v1 database is upgraded in place to v4 without losing or reordering waiting messages, and
  sequence numbers keep growing even after every row is deleted.
- **Budgets.** Regression tests fail if rows written per send, fan-out or receipt, or rows read per
  catch-up, grow.

Local load (`node tools/pilot.ts load`, wrangler dev on this machine). The workload is an assumed pilot day,
not measured usage: 10 users × 100 messages, 70% direct and 30% to a 9-member group, sent in concurrent bursts
of 25, with every recipient connected and acknowledging each push.

| Schema | Deliveries | Lost | Stored p50/p95 | Confirmed receipt p50/p95 | Rows written per delivery |
|---|---:|---:|---|---|---:|
| v1 | 3,288 | 0 | 170 / 195 ms | 190 / 226 ms | 7.19 |
| v3 | 3,376 | 0 | 165 / 187 ms | 195 / 227 ms | 5.36 |

v4 (delete on receipt) changes only what a receipt does; its per-operation costs come from the budget tests.

Envelope size: median 2,213 bytes, maximum 2,557 for 20–300 character messages.

## C1: live test pilot

Deployed `relay-mailbox-test` to the new Cloudflare account (created 2026-10-18), with the 10 synthetic
identities only. The deployed v1 database was then migrated live to v3, and later to v4, by redeploys.
The results below are from v1 and v3; v4 was re-checked with the live smoke test.

- **Smoke:** 12/12 checks pass live, the same set as locally (auth refusals, replay, privacy, real wraps,
  idempotency, fan-out, push and socket receipt, keepalive, receipts).
- **Real hibernation:** a socket idle for 30, 45 and 90 s still received the next message and acknowledged it,
  while the object had been rebuilt (new instance id each time).
- **Probes** (Slovenia → Cloudflare, 8 runs): stored 71–87 ms, pushed 71–87 ms, confirmed receipt 123–138 ms,
  socket connect 185–219 ms.
- **Live load:** 300 messages, 1,076 deliveries, 0 lost. Stored p50/p95 214/267 ms, confirmed receipt
  283/329 ms, 5.28 rows written per delivery.
- **Cloudflare's own meter** for the day, read at 11:15 UTC: 1,610 Durable Object requests, 9,722 rows written,
  23,461 rows read, about 4.5 s active time, 0 errors. Periodic metrics can lag, and inbound socket messages
  showed as 0 although the load test sent 1,076 acknowledgements, so recheck this in the dashboard.

## Free-plan budget

Workers Free per day (pricing page, checked 2026-10-18): 100,000 requests, 13,000 GB-s, 5 M rows read,
100,000 rows written, 5 GB stored. Exceeding any one makes those operations fail until 00:00 UTC.

Rows written are the binding limit. Schema v2/v3 cut them by dropping a total-counter row, making the two
small tables WITHOUT ROWID, and using an index a receipt doesn't rewrite. v4 deletes a message on receipt, which
Tadej chose on 2026-10-18, so there are no retained dedup records to update or prune.

Measured per operation (v4 budget tests): 6 rows written for a direct send, 5.1 per recipient in a 9-way
fan-out, about 2 per confirmed message. For the assumed pilot day (about 3,400 deliveries) that's about 18,000
for sends, about 7,000 for receipts, about 1,000 for auth-record cleanup and about 1,400 for the 10-minute probe:
**about 27,000, or 27% of the allowance**, against about 61% for the first version. Storage is negligible at
about 2.2 KB per waiting message.

Trade-off of delete on receipt: a sender retry that arrives after the recipient's receipt is delivered again.
The app's payload-id check (#40) keeps it from showing twice.

## Not yet established

- Android interoperability. The fixtures are JS (nostr-tools); the app uses rust-nostr. That is C2.
- Availability over time. A systemd user timer on Tadej's machine now probes every 10 minutes into
  `~/.local/state/relay-mailbox/probe.jsonl` (`node tools/pilot.ts uptime` summarises it). It only runs while
  that machine is on, so it measures availability from one place, with gaps.
- Behaviour from mobile networks, and from more than one client location.
