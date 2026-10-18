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

## C2: the Android app on the mailbox — 2026-10-18 (#45)

Code: `transport/mailbox/` (client), `transport/HybridTransport.kt` (both routes), DB v22 (`mailbox_inbox`,
`mailbox_packets`), Settings → Connection switch, `rebuild/dev/test-mailbox-emulators.py` (live harness).

- **Tor mode off.** `TorControl.route()` always returns direct and the Settings section is hidden. The
  Tor-aware logic is kept as `routeWithTor()` for a later return.
- **Both routes receive.** Builds with a delivery server (`.local/mailbox.properties`, gitignored) always
  receive from the mailbox and from Nostr. The switch "Send through Relay's delivery server" only picks the
  sending route and is on by default. A pilot user whose friend sends through the mailbox therefore always
  gets the message. An install that isn't on the allowlist is refused and only retries every 15 minutes.
- **Sending:** mailbox first, falling back to Nostr only for recipients who aren't on it, or when this
  install isn't. A busy or unreachable mailbox is retried by the outbox, never silently bypassed. Typing and
  presence signals are marked ephemeral and always use Nostr: the 20-second presence heartbeat alone would
  otherwise use up the Free write allowance.
- **No loss on the phone.** Every packet is written to the encrypted `mailbox_inbox` before the server gets a
  receipt. It's opened and handled from there, and deleted only when the handler reports it handled
  (`IncomingEnvelope.onHandled`; the calculator-only mode defers instead). A packet that keeps failing is
  dropped after 5 app starts rather than blocking the cumulative receipt.
- **Receipt ordering** (`MailboxSession`): nothing is acknowledged until the first catch-up has completed. A
  live push can arrive before the batch that holds older messages, and acknowledging it early would delete
  them unread.
- **Stable retries:** the gift wrap for each (payload id, recipient) is saved in `mailbox_packets` on the
  first attempt and resent byte-for-byte, so the server answers `duplicate` instead of storing a second copy.
- **Found and fixed on the way:** the QR scanner crashed the app when no camera could be opened
  (`QrScanner.kt`, `cameraProviderFuture.get()`). It now leaves the preview empty, and image import still
  works.

### Evidence

- Unit tests: 323 pass, including the receipt state machine, protocol parsing and outcome mapping, and the
  hybrid routing rules (with fakes).
- **Live, real app, two emulators, through the deployed pilot, with Nostr pointed at an unreachable address
  so the mailbox is the only route:**
  - QR pairing and mutual verification.
  - Text both ways (1.8 s with healthy emulators, ~5 s on memory-starved ones, including UI automation).
  - A photo (blob upload direct to Blossom; its reference via the mailbox).
  - A message sent while Bob was in airplane mode, held by the server and delivered 4–5.5 s after his
    network returned.
  - Alice's emulator was upgraded from 1.8-preview with its data, so the DB upgrade to v22 ran on real data.
- Both emulator identities are now on the pilot's allowlist (`.local/mailbox-emulator-pubkeys.txt`); the
  harness restores both apps to "send via relays, default relays" when it ends.

### Not covered yet

- Old/new client mix on public relays. The hybrid logic is unit-tested; a live mixed run needs reachable
  public relays.
- Delivery while the app is closed: unchanged, as there is no background service or push (FCM). That's C3.
- Real phones, mobile networks, and group chats through the mailbox (the group fan-out uses the same per-recipient path).

## No public relays; owner recovery removed — 2026-10-18 (#46)

Tadej's decisions: no Nostr relays anywhere; owner recovery and the Control Panel removed; 10 wrong calculator
codes in a row wipe Relay; photos move from public Blossom servers to Relay's own server.

- **Server.** Live signals: typing and presence pass socket to socket, never stored (no rows written),
  ≤16 KiB, rate-limited per socket. Media: one Durable Object per encrypted file (`MediaBlob`), chunked,
  downloadable only by the recipients named at upload, deleted once all confirm or after 8 days (alarm).
  Waiting messages are dropped after 8 days. 89 server tests.
- **App.** Builds with a server use only `MailboxTransport`; `NostrTransport` is never constructed (it is kept
  for development builds without a server). Typing and presence go over the socket as live signals.
  Photos and videos upload to the server for exactly the message's recipients, and each recipient confirms
  after storing. References to any other host are never fetched. The both-routes `HybridTransport` and its
  sending switch were removed. Release builds now require a delivery-server URL.
- **Owner recovery removed:** OwnerRelaySync, OwnerSync, owner diagnostics, the recovery ID and the owner build
  config, plus the `owner/` Control Panel. Leftover owner keys (including the private recovery key) are deleted
  from the calculator's encrypted preferences on first start. Local `.local/` data was not touched.
- **Calculator:** 10 wrong codes in a row trigger `RelayWipe`, the system's clear-app-data (with a manual erase
  fallback); a correct code resets the count.

### Evidence

- 317 app unit tests and 89 server tests pass.
- Live through the redeployed pilot with the real app on two emulators (`test-mailbox-emulators.py`): both apps
  run only `MailboxTransport`; text both ways; a photo stored on the server for the recipient only; a message
  held while Bob was in airplane mode and delivered 4.0 s after reconnecting.
- The wipe on the real UI: 10 wrong codes on Bob's calculator → the app ended, with 10 preference files, 1
  database and 3 files before and none after.

## Groups, typing and closed-app delivery — 2026-10-18 (no phones needed)

The live scenario (`rebuild/dev/test-mailbox-emulators.py`) now also covers, all through the delivery server
with no relay:

- **Groups:** a group created by Alice reaches Bob; posts both ways; a group photo stored for the members only,
  downloaded and decrypted by Bob.
- **Typing indicators,** direct and in the group, passed live and never stored.
- **Closed-app delivery:** Bob's app process stopped, Alice sends, and Android's periodic background check
  (WorkManager `PollWorker`, forced to run through JobScheduler) fetches the message from the server and posts
  the notification. On a real phone this check runs about every 15 minutes, and Android can delay it longer;
  prompt notifications with the app closed would need push.

Two app bugs found and fixed:

- **Refused live signals dropped the connection.** Presence goes to every contact every 20 s. For contacts not
  on the server, the "unknown recipient" reply was treated like a failed catch-up, so the socket was rebuilt
  each time. Requests now carry a `ref`: a refused signal only stops signals to that recipient for an hour, and
  only failed catch-ups or receipts reconnect. 0 reconnect warnings after the fix.
- **A newly allowlisted phone waited up to 15 minutes.** An install refused before its key was added slept out a
  15-minute back-off. Now it's 5 minutes, and opening the app (`MainActivity.onResume` →
  `MessagingRuntime.onForeground`) or a reconnect retries immediately.

## Joining by QR pairing — 2026-10-18

Tadej chose: new people get onto the server by pairing in person with someone already on it, so no redeploy is
needed per person. The deploy-time allowlist now only lists the founders.

- **Server (schema v5):** `members` and `invites` tables. A member showing a QR registers its one-time code's
  SHA-256 as an invite (`POST /v1/invite`). The scanner calls `POST /v1/pair {peer, code}` before sending the
  pairing request: a member vouches for the peer, and a newcomer is admitted with the peer's unexpired, unused
  invite. The media store asks the mailbox who is a member (Durable Object RPC). Capped at 100 people.
  96 server tests, including 7 joining tests.
- **App:** `PairingCoordinator.joinDeliveryServer` runs before the pairing request, with a clear error if
  neither phone is on the server. The QR screen registers each code as an invite and retries the connection
  every 5 s while not online, so a newcomer sees the pairing request as soon as they're let in.
- **Live, both ways:** with only Alice as a founder, (1) Bob showed his QR and Alice's scan vouched him in;
  (2) after wiping Bob (new key), Alice showed her QR and Bob joined with its invite. Each time the full scenario
  followed: texts, photo, offline catch-up, group with photo, typing, and closed-app delivery.
- Harness fixes for UI races: chats are opened through People → contact (a group row can mention the peer),
  and the opened chat is checked to be the peer's before typing.
