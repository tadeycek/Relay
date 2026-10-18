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
| `src/mailbox.ts` | The mailbox Durable Object: auth replay check, transactional store, catch-up, receipts, live signals, hibernating sockets, schema migrations |
| `src/media.ts` | One Durable Object per encrypted file: chunked storage, recipient-only download, delete after all confirm or 8 days |
| `src/auth.ts` | NIP-98 request auth |
| `src/protocol.ts` | Limits, error codes, shared types |
| `client/mailbox-client.ts` | Minimal client used by the tests and tools: signing, NIP-17 wrap and open |
| `test/` | Vitest suites that run inside the Workers runtime (`@cloudflare/vitest-plugin`) |
| `tools/pilot.ts` | Pilot tool: identities, smoke, load, probe, hibernation (Node 26 runs it directly) |

## Develop and test

```sh
npm ci
npm test            # 74 tests in the local Workers runtime, no network
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

Test pilot only: synthetic identities, no real chats. The `ALLOWED_PUBKEYS` value is passed at deploy time and
never committed.

```sh
npx wrangler deploy --var "ALLOWED_PUBKEYS:$(node tools/pilot.ts allowlist)" --var EXPOSE_METRICS:1
export RELAY_MAILBOX_URL=<the workers.dev URL wrangler prints>   # also saved privately in ~/.config/relay-mailbox/pilot-url
node tools/pilot.ts smoke
node tools/pilot.ts hibernate --idle 60
node tools/pilot.ts probe          # appends to ~/.local/state/relay-mailbox/probe.jsonl
```

The allowlist is replaced on every deploy. The Android emulator identities used by
`rebuild/dev/test-mailbox-emulators.py` are added by running that harness with `--deploy-allowlist`
(their keys are kept in `.local/mailbox-emulator-pubkeys.txt`); a plain deploy as above drops them.

Schema changes are versioned migrations in `Mailbox.migrate()`. Each step runs once, in a transaction, when
the object next starts. Never edit a released step; add a new one.

## Free-plan budget

Workers Free per day (Cloudflare pricing page, checked 2026-10-18): 100,000 Durable Object requests,
13,000 GB-s duration, 5 M rows read, **100,000 rows written**, 5 GB stored. Going over any of them makes those
operations fail until 00:00 UTC. Rows written is the binding limit. Measured costs: about 5.3 rows written per
delivery and about 2 per confirmed message. That's roughly 27,000 a day for an assumed 10 users × 100 messages,
30% to groups, including the 10-minute uptime probe. Live signals write nothing; a photo costs a handful of
rows (one per 1 MiB chunk plus one per recipient). Set `EXPOSE_METRICS=1` to get an `x-relay-sql-rows` header
(row counts only) on every response.

## Uptime probe

`node tools/pilot.ts probe` sends one real message through the live pilot (send → push → receipt) and appends
the result to `~/.local/state/relay-mailbox/probe.jsonl`; `node tools/pilot.ts uptime` summarises that log. On
Tadej's machine a systemd user timer runs the probe every 10 minutes from a copy in
`~/.local/share/relay-mailbox/probe`. It only runs while that machine is on, so gaps in the log are not outages.
