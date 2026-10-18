# Relay mailbox protocol, v1

A private store-and-forward mailbox for Relay's existing end-to-end encrypted packets. Phones keep their
history locally; the server only holds a message until the recipient's phone confirms it stored it.

`src/protocol.ts` is the source of truth for limits and error codes; this file explains the contract.

## What the server sees

- **Content: nothing.** Every envelope is the app's NIP-17 gift wrap (kind 14 rumor → kind 13 seal → kind 1059
  wrap, NIP-44 encrypted), passed through as an opaque string. Only the recipient can open it, and the
  recipient verifies the seal signature and that the rumor author is the seal signer, as `NostrTransport` does.
- **Metadata: yes.** Writes are authenticated, so the server knows sender, recipient, size and time of every
  message. This was accepted for the pilot on 2026-10-18. With Tor mode off, the IP links an identity's
  reads to its writes anyway.

## Authentication (every request, including the WebSocket upgrade)

`Authorization: Nostr <base64(event)>`, where the event is a NIP-98 kind-27235 event signed with the user's
Nostr identity key (the key the app pairs by QR):

| Tag | Value |
|---|---|
| `u` | The exact URL, including the query string. For the socket, the `wss://…/v1/ws` URL being dialled. |
| `method` | `GET` or `POST`. |
| `payload` | Lowercase hex SHA-256 of the request body. Required whenever there is a body. |
| `nonce` | Random hex. Not checked, but without it two identical requests in the same second share an event id and the second is refused as a replay. |

The server refuses an event older or newer than 60 s, one whose id it has already accepted, a bad signature,
any tag mismatch, and any key not on the deployment's allowlist (`ALLOWED_PUBKEYS`).

## HTTP

Every body is JSON with `"v": 1`. Errors are `{"v":1,"error":{"code":…,"retryable":bool}}`.

### `POST /v1/send`

```json
{ "v": 1, "deliveries": [ { "to": "<recipient pubkey hex>", "id": "<client delivery id>", "envelope": "<gift wrap JSON>" } ] }
```

Up to 16 deliveries per request, one per recipient copy, so a group message to 9 members is one request.
Each delivery is stored atomically on its own and gets its own result, in request order:

| `status` | Meaning |
|---|---|
| `stored` | Durably committed; `seq` is its position in the recipient's mailbox. |
| `duplicate` | The same sender already sent this `id` to this recipient with identical bytes and it is still waiting; `seq` is the original. |
| `rejected` | See `error.code`: `unknown_recipient`, `envelope_too_large`, `id_conflict` (same id, different bytes), `recipient_full`, `mailbox_full`, `storage_error`. Only the last three are `retryable`. |

**Retry rule:** create the delivery `id` and the exact envelope once, persist both in the outbox, and resend
the same pair until the result is `stored` or `duplicate`. A new gift wrap for each retry is not deduplicated.
The server only remembers a message while it waits. A retry that arrives after the recipient's receipt is stored
again and delivered a second time; the recipient drops it by payload id, which the app already does.

### `GET /v1/inbox?after=<seq>&limit=<n>`

The caller's own pending messages after `after`, oldest first. There is deliberately no way to name another
recipient: unknown query parameters are refused. A page is bounded by count (≤100) and by bytes (≤1 MiB,
always at least one message).

```json
{ "v": 1, "messages": [ { "seq": 41, "from": "<sender pubkey>", "id": "<delivery id>", "envelope": "…", "storedAt": 1760000000000 } ], "more": false, "cursor": 41 }
```

### `POST /v1/ack`

`{"v":1,"through":41}`. A cumulative receipt meaning "everything up to 41 is durably in my local inbox". Only
send it after the local database commit, never on receipt into memory. The server then deletes those messages
completely; nothing about them stays (decided 2026-10-18).

### `GET /v1/health`

Unauthenticated liveness of the edge Worker. It doesn't touch the mailbox.

## WebSocket `GET /v1/ws`

Authenticated at upgrade. The socket's identity is stored with the socket, so it survives the server
hibernating between messages. A session lasts 1 hour; then the server closes it with `4001 auth_expired`
and the client reconnects with fresh auth. At most 4 sockets per identity; the oldest is closed with
`4008 replaced`.

| Direction | Frame |
|---|---|
| server → client | `{"type":"ready","v":1,"instance":"…","expiresAt":…}` once after connecting |
| server → client | `{"type":"message","seq":…,"from":…,"id":…,"envelope":…,"storedAt":…}` for each newly stored message |
| client → server | `{"type":"sync","after":<seq>,"limit":<n>,"ref":"…"}`: catch-up, answered by `{"type":"batch","ref":…,"messages":[…],"more":…,"cursor":…}` (≤512 KiB) |
| client → server | `{"type":"ack","through":<seq>,"ref":"…"}`, answered by `{"type":"acked","ref":…,"acked":<n>,"through":<seq>}` |
| server → client | `{"type":"error","ref":…,"error":{"code":…,"retryable":…}}` |
| client → server | `ping` (raw text), answered with `pong` by the platform without waking the server |

### Client loop

1. Connect, then `sync` from the last cursor the phone durably committed (0 on first run).
2. Store every message from the batch and from pushes in the local database. Deduplicate by `seq` and by `id`,
   because overlap after a reconnect is normal.
3. After the local commit, `ack` through the highest stored seq.
4. On disconnect, reconnect with backoff and go to 1. A missed push is never lost: it waits in the mailbox.

## Limits (pilot defaults)

| Limit | Value | On breach |
|---|---|---|
| Envelope | 128 KiB | `envelope_too_large`, not retryable |
| Pending per recipient | 32 MiB | `recipient_full`, retryable |
| Pending in total | 256 MiB | `mailbox_full`, retryable |
| Deliveries per request | 16 | `too_many_deliveries` |

Caps refuse new messages. Nothing already accepted is ever evicted to make room.
