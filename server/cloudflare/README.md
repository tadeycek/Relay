# Relay mailbox (Cloudflare)

Relay's own delivery server, and since 2026-10-18 the app's only network service: one Cloudflare Worker in
front of SQLite-backed Durable Objects, on the Workers Free plan. It forwards the app's existing encrypted
packets between paired phones, passes live typing/presence signals without storing them, and holds encrypted
photos and videos. The phones keep the history; the server deletes a message or file as soon as its recipients
have it, and anything uncollected after 8 days.
See [PROTOCOL.md](PROTOCOL.md) for the contract.

```
phone ──HTTPS/WebSocket──▶ Worker (cheap checks) ──▶ Mailbox Durable Object (auth, SQLite queues) ──▶ phone
```

## Layout

| Path | What |
|---|---|
| `src/index.ts` | Edge router: health, routing, refuses anonymous or oversized requests before they cost a Durable Object request |
| `src/mailbox.ts` | The mailbox Durable Object: auth replay check, transactional store, catch-up, receipts, live signals, hibernating sockets, schema migrations, device cursors and G1 device authorization (WP04/WP05) |
| `src/media.ts` | One Durable Object per encrypted file: chunked storage, recipient-only download, delete after every recipient device confirms (dormant/unlinked devices waived) or 8 days |
| `src/auth.ts` | NIP-98 request auth |
| `src/push.ts` | Content-free Firebase wake-ups, service-account JWT signing and OAuth token caching |
| `src/protocol.ts` | Limits, error codes, shared types |
| `client/mailbox-client.ts` | Minimal client used by the tests and tools: signing, NIP-17 wrap and open |
| `test/` | Vitest suites that run inside the Workers runtime (`@cloudflare/vitest-plugin`) |
| `tools/pilot.ts` | Pilot tool: identities, smoke, load, probe, hibernation (Node 26 runs it directly) |

## Develop and test

```sh
npm ci
npm test            # local Workers runtime, with Firebase requests intercepted by tests
npm run typecheck
npm run build       # bundle dry run into dist/, deploys nothing
```

Local server with the synthetic identities:

```sh
node tools/pilot.ts identities          # once; keys go to ~/.config/relay-mailbox (mode 600), not the repo
npx wrangler dev --local --port 8799 --var "ALLOWED_PUBKEYS:$(node tools/pilot.ts allowlist)" --var EXPOSE_METRICS:1
node tools/pilot.ts smoke --url http://127.0.0.1:8799
node tools/pilot.ts load  --url http://127.0.0.1:8799
```

## Deploy the test pilot (C1)

Test pilot only: synthetic identities, no real chats. `ALLOWED_PUBKEYS` (the founders) is passed at deploy time
and never committed. Everyone else joins by pairing in person with a member, so a deploy doesn't need the full
list (see PROTOCOL.md, "Membership").

```sh
npx wrangler deploy --var "ALLOWED_PUBKEYS:$(node tools/pilot.ts allowlist)" --var EXPOSE_METRICS:1
export RELAY_MAILBOX_URL=<the workers.dev URL wrangler prints>   # also saved privately in ~/.config/relay-mailbox/pilot-url
node tools/pilot.ts smoke
node tools/pilot.ts hibernate --idle 60
node tools/pilot.ts probe          # appends to ~/.local/state/relay-mailbox/probe.jsonl
```

The founders list is replaced on every deploy; people admitted by pairing are kept in the mailbox's database.
The Android emulator harness `rebuild/dev/test-mailbox-emulators.py` can redeploy with its identities as
founders (`--deploy-allowlist`, `--founders both|alice`), and tests both ways of joining (`--repair --shower
Bob|Alice`).

Schema changes are versioned migrations in `Mailbox.migrate()`. Each step runs once, in a transaction, when
the object next starts. Never edit a released step; add a new one.

## Firebase wake-ups (optional)

The Android build enables Firebase when its own `app/google-services.json` exists. Keep the downloaded
configuration in the gitignored `.local/` directory and copy it to `app/google-services.json` before building.
Without the file, the app continues to use sockets and periodic inbox checks. Use the Android package
`com.relay.app` in Firebase.

The server reads the Firebase service-account JSON from the Worker secret `FCM_SERVICE_ACCOUNT`. After approval
to upload that credential, run from this directory:

```sh
npx wrangler secret put FCM_SERVICE_ACCOUNT < ../../.local/<service-account-key>.json
```

Keep the key out of Git, notes and logs. Deploy the server with its existing founders as described above;
the v6 schema adds device tokens and v7 adds persistent wake-up throttling. Token registration uses authenticated
`POST /v1/push-token`. Android retries failed registration with WorkManager, and refreshes it after a successful
mailbox connection, including after a newcomer joins by pairing.

For a real push test, use a Google-enabled emulator or phone. Check `MailboxPush` logs for registration
HTTP 200, end the recipient's app process, send from the other device, and verify a notification appears
without manually running JobScheduler. Do not use Android's Force stop for this test: the app must be opened
again before FCM can deliver after a force stop. See Firebase's
[delivery prerequisites](https://firebase.google.com/docs/cloud-messaging/flutter/receive-messages) and
[Android message priority guidance](https://firebase.google.com/docs/cloud-messaging/android-message-priority).
The app hands wake-ups to WorkManager, with expedited work on Android 12 and later. Actual delivery timing
still depends on Android and FCM; periodic polling remains the fallback.

For the emulator acceptance scenario, build and install the app and instrumentation APKs first, then run
`python3 rebuild/dev/test-mailbox-emulators.py --founders alice --push` from the repository root. This mode
requires successful token registration on both devices, pauses the recipient's periodic poll, ends its process,
and checks for a new content-free FCM callback and a notification without forcing a job. The harness restarts
the recipient afterwards to restore periodic polling. Without `--push`, it tests the polling fallback instead.
Use `--push-only` to repeat just pairing/token-registration and the controlled closed-app FCM check.

Migration v7 supports both original v6 token tables and v6 tables that already contain `last_wake_at`.
Existing tokens, members and pending messages are preserved; the old table is never recreated.

## Linked devices (WP04)

One queue per identity can now be shared by several devices (a phone plus up to 3 computers), each with its own
cursor: a message is deleted once every **active** device has acked it, and a device silent for more than 72 hours
stops holding the queue and media. A client that sends no `device` parameter behaves exactly as before. The
contract is in [PROTOCOL.md](PROTOCOL.md#linked-devices-per-device-cursors-v11-wp04).

- **G1 device authorization (WP05 part A).** An identity can migrate to a phone-only administration key. After
  migration only that key may enroll or revoke devices; each device signs with its own key, and a revoked key gets
  `401 auth_revoked` on HTTP and close `4009` on WS. Migration closes the identity's open sockets with `4001`,
  and every socket frame and live push re-checks the signing key, so a shared-key socket cannot outlive the
  migration even if a close is missed. Device keys are unique across all identities (`409 auth_key_in_use`);
  rotating one closes that device's socket (`4001`) but is not revocation — DELETE is. Legacy identities that
  never migrate keep the shared-key behaviour. Revocation and migration are server-side access changes: they do
  not erase the keys a computer already learned.
- Tables: `devices` and `cursor_state` in the Mailbox object (created by `migrateDevices()`, tracked by the meta key
  `devices_schema` so the numbered `schema` stays 7); `recipients` in each media object becomes `(pubkey, device)`
  (media schema 2). Identities without devices never write the new tables, but since G1 every request resolves its
  signer with one indexed `devices` read, so legacy requests are no longer row-cost-identical to WP04.
- Settings: `MAX_LINKED_DEVICES` (3), `DORMANT_AFTER_MS` (72 h). Not deployed: `relay-mailbox-test` was not touched
  and the 48 h pilot (WP04-AC6) has not run.
- Row costs, measured with `EXPOSE_METRICS=1` by `test/devices-budget.test.ts` (`npx vitest run
  test/devices-budget.test.ts --reporter=verbose` prints a `WP04-ROWS` line): see
  [linux-port/evidence/WP04.md](../../linux-port/evidence/WP04.md).

- **Link slot and sync channel (WP05 part B).** The phone parks a sealed ≤128 KiB bundle (keys, contacts,
  groups, settings — no history) under `PUT /v1/link/<sha256 of a one-time code>`; the desktop proves the
  code in `x-relay-link-proof` on the unauthenticated `GET`, then `DELETE`s the slot to complete. GET marks
  a 60 s lease and does not consume, so a lost response is retried; the slot expires after 10 minutes, one
  live slot per identity (`409 link_busy`), and guesses are limited per slot and per client (in memory).
  `POST /v1/sync` assembles bounded SENT/KEYS envelopes from at most 8 parts (12 KiB decoded, SHA-256
  verified, unique id/revision, 4 concurrent assemblies), and `GET /v1/sync` serves them in cursor order
  per device. Contract and error table: [PROTOCOL.md](PROTOCOL.md#link-slot-v13-wp05-part-b). Rows measured
  by `test/link-sync-budget.test.ts` (`WP05-ROWS`): link PUT 1 read/3 written, GET 2/1, completion 2/1;
  sync first part 4/2, completing part 9/8, list 3/1, single 2/1, ack 2/2. Evidence:
  [linux-port/evidence/WP05-link-slot.md](../../linux-port/evidence/WP05-link-slot.md).
- **Not deployed.** These routes exist in this branch only; `wrangler dev --local` and the vitest workerd
  pool are the only environments they have run in.

## Free-plan budget

Workers Free per day (Cloudflare pricing page, checked 2026-10-18): 100,000 Durable Object requests,
13,000 GB-s duration, 5 M rows read, **100,000 rows written**, 5 GB stored. Going over any of them makes those
operations fail until 00:00 UTC. Rows written is the binding limit. Measured costs: about 5.3 rows written per
delivery and about 2 per confirmed message. That's roughly 27,000 a day for an assumed 10 users × 100 messages,
30% to groups, including the 10-minute uptime probe. Live signals write nothing; a photo costs a handful of
rows (one per 1 MiB chunk plus one per recipient pair). A linked identity costs more: about 5 rows written for
the ack that frees a message with two devices (2 for the first device's ack) against 3 for a legacy receipt. Set `EXPOSE_METRICS=1` to get an `x-relay-sql-rows` header
(row counts only) on every response.

## Uptime probe

`node tools/pilot.ts probe` sends one real message through the live pilot (send → push → receipt) and appends
the result to `~/.local/state/relay-mailbox/probe.jsonl`; `node tools/pilot.ts uptime` summarises that log. On
Tadej's machine a systemd user timer runs the probe every 10 minutes from a copy in
`~/.local/share/relay-mailbox/probe`. It only runs while that machine is on, so gaps in the log are not outages.
