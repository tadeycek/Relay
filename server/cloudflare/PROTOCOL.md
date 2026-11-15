# Relay mailbox protocol, v1

A private store-and-forward mailbox for Relay's existing end-to-end encrypted packets, plus live typing and
presence signals and encrypted photo/video storage. Since 2026-10-18 it is Relay's only network service: the
app contacts no public relay. Phones keep their history locally; the server only holds a message (or file)
until the recipient's phone confirms it stored it, and drops anything uncollected after 8 days.

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
any tag mismatch, and any key that isn't a member.

## Membership: founders, then in-person QR pairing

**Founders** are the keys in the deployment's `ALLOWED_PUBKEYS`. Everyone else joins when they pair in person
with a member (decided 2026-10-18). Pairing messages travel through the server themselves, so this happens at
scan time, before the pairing request is sent:

| Request | Who | Effect |
|---|---|---|
| `POST /v1/invite {"v":1,"code":<sha256 hex of the QR's one-time code>,"expiresAt":<ms>}` | a member showing their QR | Registers the code as a one-time invite (at most 1 h; a member keeps their newest 20). |
| `POST /v1/pair {"v":1,"peer":<pubkey from the QR>,"code":<the QR's one-time code>}` | the phone that scanned a QR; **any** signed key | A member caller admits `peer` (vouching). A newcomer is admitted if `peer` registered this code, it hasn't expired, and it wasn't used. Reply: `{"v":1,"you":bool,"peer":bool}` — whether each can now use the server. |

The server stores only code hashes. A newcomer can't vouch for anyone or register invites. At most 100 people
(`MAX_MEMBERS`) can be admitted this way.

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

Messages nobody collects within 8 days are deleted too. The app refuses messages older than 7 days anyway.

### Media: `PUT | GET | DELETE /v1/media/<sha256>`

Encrypted photos and videos (the app encrypts with AES-256-GCM before uploading; the key travels only inside
the end-to-end encrypted message). Each file lives in its own storage object named after the SHA-256 of its
bytes, so a large transfer never holds up the mailbox.

| Request | Who | Effect |
|---|---|---|
| `PUT`, body = file, `x-relay-recipients: <pubkey>,<pubkey>,...` | any allowlisted sender | Stores it (up to 25 MiB) for 1–16 allowlisted recipients. The bytes must hash to the address. Only the original uploader may repeat an upload and add recipients. |
| `GET` | a named recipient | The file. Anyone else gets `404`, the same as for a file that doesn't exist. |
| `DELETE` | a named recipient | "I have stored it." When every recipient has confirmed, the server deletes the file and everything about it. |

Files nobody collects are deleted after 8 days.

### Resumable media: `/v2/media/<sha256>`

This additive protocol stores the SHA-256-addressed **ciphertext**, split into bounded 1 MiB HTTP parts.
Encryption framing is opaque to the server. The Android v2 format uses Tink AES-GCM-HKDF streaming
encryption; the encrypted message carries its format and key. The maximum ciphertext size is
`3222274048` bytes (3 GiB plaintext plus 1 MiB reserved for encryption framing). All sizes are positive safe
integers, including values above signed 32-bit range. Existing v1 uploads and downloads keep their format.

Every request uses the same NIP-98 authentication, exact URL/method binding, payload hash and replay
protection as v1. Sign **fresh auth for every retry**. All hashes below are lowercase 64-character hex.
Do not send the chat message/reference until completion succeeds.

| Request | Who | Effect |
|---|---|---|
| `POST /init`, JSON `{"size":12345,"partSize":1048576,"recipients":["<pubkey>"]}` | member uploader | Creates a pending transfer for 1–16 current members. Size, part size, uploader and recipient set are immutable. An identical retry returns status. |
| `GET /status` | original uploader; named recipients after completion | Returns the status below. Only the uploader can inspect a pending transfer. |
| `PUT /parts/<index>`, raw ciphertext, `x-relay-part-sha256: <hash>` | original uploader | Uploads exactly one part in ascending index order, starting at 0. All except the last must be exactly 1048576 bytes. The last has the remaining length. |
| `POST /complete`, JSON `{"parts":["<part 0 hash>","<part 1 hash>"]}` | original uploader | Verifies every stored part against the ordered manifest and verifies the incremental whole-file SHA-256 against the URL. Only then makes the file downloadable. |
| `GET /parts/<index>` | named recipient or original uploader, after completion | Returns one raw part, `x-relay-part-sha256` and `x-relay-sha256`. Never returns pending file data. |
| `DELETE` (the base URL) | named recipient, after completion | Confirms durable local receipt. Deletes the file and metadata after every recipient confirms. |

`init` and `status` return:

```json
{"v":2,"size":1048655,"partSize":1048576,"totalParts":2,"nextPart":1,"receivedParts":[0],"state":"uploading","expiresAt":1790000000000}
```

`state` becomes `complete` after validation. `receivedParts` is always the prefix `0..nextPart-1`, so resume
with `nextPart` using the **same durable ciphertext file**. `PUT` returns
`{"v":2,"stored":true,"duplicate":false,"nextPart":1}`. Re-uploading an already stored identical part returns
`duplicate:true`; changed bytes or a new out-of-order index return `409 id_conflict`. An incomplete completion
returns retryable `409 id_conflict`; a mismatched manifest is a non-retryable conflict. Invalid part checksums,
lengths or whole-file hash return `400 bad_request`. Completion is idempotent, returning
`{"v":2,"stored":true,"duplicate":false}` (then `duplicate:true` on retry). Errors retain the existing v1 error
envelope. Downloaders must verify all part checksums and the whole ciphertext hash, authenticate/decrypt the
complete file and commit it locally before confirming receipt. A retry must not change the ciphertext or key.

Unknown files and files forbidden to the caller both return `404 not_found`. Another uploader cannot claim an
existing hash or extend its recipients. Confirmations cannot delete an unfinished transfer. A legacy v1 GET
for an authorized, completed v2 file returns `426 upgrade_required`, avoiding whole-file buffering.

Control JSON is limited to 256 KiB, each part request to 1 MiB; bounds are enforced while streaming even if
Content-Length is absent. SQLite stores each part as one bounded row. A persisted incremental SHA-256 state
is updated with each accepted part in the same transaction, so completion reads only the small hash manifest.
No request loads the complete v2 file. Completed **and abandoned** transfers expire 8 days after initialization;
retries and completion do not extend that deadline. Expiry is checked even if alarm delivery is delayed.

This uses the existing MEDIA Durable Object namespace and SQLite storage. It creates no external storage
service. Cloudflare currently documents 10 GB per SQLite Durable Object and **5 GB total per Free account**;
one maximum transfer fits an object, but two cannot fit within that aggregate free allowance. Actual account
usage, plan and available quota must be checked before any production capacity claim. See
[Durable Object limits](https://developers.cloudflare.com/durable-objects/platform/limits/) and
[pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/) (checked 2026-11-15).

### `POST /v1/push-token`

`{"v":1,"token":"<Firebase registration token>"}` registers the caller's current device token; an empty
token removes it. A member can only register their own token. Reply: `{"v":1,"push":true}` when a nonempty
token was saved and the server has Firebase credentials, otherwise `push` is false.

After storing a new message, the server attempts live delivery to the recipient's sockets. If none accepts
it, Firebase receives a high-priority, collapsible data message containing only `{"type":"wake"}`. Google
sees the target device and push timing, but no message content, sender or Relay identity key. The app schedules
an inbox check with WorkManager and acknowledges only after local storage, using the usual receipt rules.

Wake-ups are limited to one attempt per recipient per 20 seconds; this state survives hibernation. Refreshing
the same token keeps the limit, while replacing it resets the limit. Only Firebase's explicit `UNREGISTERED`
error removes a dead token. Push failures leave the stored message available for socket catch-up or polling.
Without the `FCM_SERVICE_ACCOUNT` Worker secret, delivery continues through sockets and periodic polling.

### `GET /v1/health`

Unauthenticated liveness of the edge Worker. It doesn't touch the mailbox. V2-capable deployments return
`{"ok":true,"v":1,"media":{"v":2,"partSize":1048576}}`. Older deployments lack `media`; a client receiving
404/405 at v2 initialization must report that a server update is required instead of retrying indefinitely.

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
| client → server | `{"type":"signal","to":<pubkey>,"envelope":<gift wrap>}`: typing or presence, passed to the recipient's open sockets and **never stored** (≤16 KiB, ≤120 a minute per socket; a recipient who isn't connected misses it) |
| server → client | `{"type":"signal","from":<pubkey>,"envelope":…}` |

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
