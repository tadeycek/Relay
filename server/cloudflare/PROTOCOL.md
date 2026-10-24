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

### `GET /v1/inbox?after=<seq>&limit=<n>[&device=<id>]`

The caller's own pending messages after `after`, oldest first. There is deliberately no way to name another
recipient: unknown query parameters are refused. A page is bounded by count (≤100) and by bytes (≤1 MiB,
always at least one message).

```json
{ "v": 1, "messages": [ { "seq": 41, "from": "<sender pubkey>", "id": "<delivery id>", "envelope": "…", "storedAt": 1760000000000 } ], "more": false, "cursor": 41 }
```

### `POST /v1/ack[?device=<id>]`

`{"v":1,"through":41}`. A cumulative receipt meaning "everything up to 41 is durably in my local inbox". Only
send it after the local database commit, never on receipt into memory. The server then deletes those messages
completely; nothing about them stays (decided 2026-10-18).

Messages nobody collects within 8 days are deleted too. The app refuses messages older than 7 days anyway.

### Media: `PUT | GET | DELETE /v1/media/<sha256>[?device=<id>]`

Encrypted photos and videos (the app encrypts with AES-256-GCM before uploading; the key travels only inside
the end-to-end encrypted message). Each file lives in its own storage object named after the SHA-256 of its
bytes, so a large transfer never holds up the mailbox.

| Request | Who | Effect |
|---|---|---|
| `PUT`, body = file, `x-relay-recipients: <pubkey>,<pubkey>,...` | any allowlisted sender | Stores it (up to 25 MiB) for 1–16 allowlisted recipients. The bytes must hash to the address. A repeat upload only adds recipients. |
| `GET` | a named recipient | The file. Anyone else gets `404`, the same as for a file that doesn't exist. |
| `DELETE` | a named recipient | "I have stored it." When every recipient has confirmed, the server deletes the file and everything about it. |

Files nobody collects are deleted after 8 days.

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

Unauthenticated liveness of the edge Worker. It doesn't touch the mailbox.

## WebSocket `GET /v1/ws[?device=<id>]`

Authenticated at upgrade. The socket's identity is stored with the socket, so it survives the server
hibernating between messages. A session lasts 1 hour; then the server closes it with `4001 auth_expired`
and the client reconnects with fresh auth. At most 4 sockets per identity; the oldest is closed with
`4008 replaced`. A linked device that is removed has its sockets closed with `4009 unlinked`.

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

## Linked devices: per-device cursors (v1.1, WP04)

One queue per identity, as before. Several devices can now share it, each with its own acknowledged cursor.
**A client that sends no `device` parameter is the implicit `primary` device and behaves exactly as it did
before this section existed** (LP-D14): same requests, same replies (byte for byte), same row costs. The
differences for such a client are listed under *Compatibility* below.

### The `device` query parameter

`device=<id>` is accepted on `GET /v1/inbox`, `POST /v1/ack`, `GET /v1/ws`, `PUT|GET|DELETE /v1/media/<h>`
and `/v1/devices…`. `<id>` is `primary` or 32 lowercase hex characters.

| Case | Result |
|---|---|
| parameter absent, or `device=primary` | the primary device |
| malformed id | `400 bad_request` |
| id the server has no row for (any identity without devices, or an unlinked id) | `403 unknown_device`, not retryable |
| `device` on any other route | ignored |

The parameter is part of the signed URL (the NIP-98 `u` tag covers the query string), so it can't be changed
in flight.

> **This is not authentication.** `device=primary` is only a query parameter. Every device holding the Nostr
> secret key can sign a request for any `device` value, so a linked desktop can act as `primary`, can
> register or remove devices, and can ack on behalf of any other device. The per-device cursor is **queue
> bookkeeping, not a security boundary** (readiness gate G1). WP05 adds separate per-device authentication
> keys, enrolled by a phone-only administration key, and restricts legacy identity auth to the primary;
> until it lands, do not describe this as access control. See *Known limitations* below.

### Cursors and deletion

- Each device has `acked`, the highest seq it has durably stored. `POST /v1/ack` raises the caller's cursor
  (`max(acked, min(through, head))`: it never moves back and never passes the mailbox head, so a receipt
  naming a seq that doesn't exist yet can't pre-acknowledge future messages).
- The server then deletes every delivery with `seq ≤ min(acked over active devices)`. The reply is the
  same `{"v":1,"acked":<rows deleted>,"through":<seq>}` as before.
- A device is **active** if it is `primary`, or if it contacted the server within the last 72 hours
  (`DORMANT_AFTER_MS`). A silent desktop therefore can't pin the queue until `recipient_full`.
- Contact means an inbox request, an ack, a socket upgrade, or a `sync`/`ack` socket frame. `last_seen` is
  written at most once an hour per device (row-write budget), so dormancy is accurate to about an hour.
- The 8-day expiry applies to every device unchanged.
- `GET /v1/inbox?after=` is unchanged: the cursor a client sends is independent of `acked`.
- Live pushes go to every open socket of the identity, as before. The cap of 4 sockets per identity is
  unchanged.

### Dormancy and the gap flag

Dormancy is evaluated against the stored `last_seen` **before** that value is refreshed. If a dormant device
returns after other devices' acks deleted messages it never stored, the server

1. raises its cursor to the deletion floor (the highest seq ever deleted for the identity; the floor only ever
   rises and is persisted, together with the head it was computed against, before any delivery is deleted);
2. records a **gap watermark** for the device, and
3. reports it: the `ready` frame, the inbox response, the socket `batch` frame and the ack reply carry
   `"gap": true`; `ready`, inbox and `batch` also carry `"oldest": <oldest seq still held>` when anything is
   held.

The gap persists until the device has been *shown* it (a ready/inbox/batch response) **and** later acks through
the watermark. An ack that arrives first does not clear it: events are never dropped silently. Messages that
expired unacknowledged (8 days) raise the floor too, so they also show up as a gap. A dormant device that
missed nothing sees no gap. Suggested client text: "This computer was offline for more than 3 days; some
messages are only on your phone."

Reactivation can't lower the floor: a returning device's cursor starts at `max(its acked, floor)`, and a stale
lower ack is a no-op.

### `POST/GET/DELETE /v1/devices` (primary only)

The caller's `device` must be `primary`: a known linked device gets `403 not_primary`, an unknown one
`403 unknown_device`. (Under the current shared-key model this check is bookkeeping, see the box above.)

| Request | Effect |
|---|---|
| `POST /v1/devices {"v":1,"device":"<32hex>","label":"…"}` | Registers a device (label ≤ 64 characters, may be empty). At most **3** besides the primary (`MAX_LINKED_DEVICES`), otherwise `409 device_limit`. Its cursor starts at the mailbox **head**, in the same synchronous section as every store, so it never receives history and no message can slip between enrolment and the first delivery. Reply `{"v":1,"head":<seq>}`: start syncing with `after=head`. The first call for an identity also creates the explicit `primary` row at the position it had already acknowledged. Registering an existing id only updates the label, never moves its cursor; the reply then has `"existing":true` and `head` is that device's own cursor. |
| `GET /v1/devices` | `{"v":1,"devices":[{device,label,added_at,last_seen,active}],"head":<seq>}`. Empty for an identity that never registered a device. |
| `DELETE /v1/devices/<id>` | Drops the cursor, deletes whatever only that device was holding, and closes its sockets with **`4009 unlinked`**. `404 unknown_device` if there is no such device; `primary` can't be removed (`400`). |

`head` is the highest seq the mailbox has ever assigned (it is global, not per identity, and never decreases).

### Socket behaviour

The socket attachment stores the device. `?device=` on the upgrade selects it (unknown device: the upgrade is
refused with `403 unknown_device`). When a device is removed, its open sockets are closed with `4009 unlinked`;
a socket that somehow outlives its device answers its next frame with `unknown_device` and is closed `4009`.

### Media (per-device confirmation)

- `PUT` snapshots, from the Mailbox object in the same call that checks membership, the **active** devices of
  every recipient pubkey. A recipient is then a `(pubkey, device)` pair; an identity without devices is the
  single pair `(pubkey, primary)`, exactly as before. A repeated upload only adds pairs.
- `GET` and `DELETE` (confirm) work for a snapshotted pair. A device that joined after the upload gets `404`; an
  unlinked id gets `403 unknown_device`. A `DELETE` from the primary only confirms the primary's share.
- The object is erased when every pair has confirmed. At each confirm the server also **waives** pairs whose
  device was unlinked or has been dormant for more than 72 hours (same policy as the queue), and arms the alarm
  for the moment the next waiting device would turn dormant, so a desktop that vanished after an upload holds
  the file for at most about 72 hours after its last contact, not the full 8 days. The 8-day expiry is
  unchanged.
- To let the uploader's own other devices fetch a blob, the uploader names its **own pubkey** in
  `x-relay-recipients`.

### Compatibility (legacy clients)

- No `device` parameter: identical requests, replies and rows.
- A legacy 1.15 phone on an identity that later has linked devices keeps working as `primary`. It sees the
  queue held for the desktop too (messages are deleted only when the desktop has acked as well, or goes
  dormant), and its media confirm only confirms the primary's pair. Its replies stay byte-identical, so it
  never learns about a gap (it can't have one: the primary is never dormant).
- Differences that exist only once an identity has linked devices: messages (and media) can stay on the server
  up to 72 hours longer than before; `409 device_limit`, `403 unknown_device`, `403 not_primary`, close code
  `4009` and the `gap`/`oldest` fields are new.
- Route `/v1/devices` and `/v1/devices/<id>` are new; an old server answers them `404`.

### Known limitations (read before relying on this)

- **Not a security boundary (G1).** `device=primary` is not proof of being the phone. Any device holding the
  identity key can sign any `device` value, so it can register devices, remove devices (including the phone's
  linked desktop), ack for others, and read the queue as any device.
- **An unlinked device can still read with the shared identity key.** `DELETE /v1/devices/<id>` removes the
  cursor and closes sockets with 4009. It does not revoke the Nostr key: the unlinked computer can reconnect
  and call `inbox`/`ack`/`ws`/media with `device=primary` (or no parameter), read pending ciphertext addressed
  to the identity, fetch blobs addressed to `primary`, and ack as the primary (which can delete messages the
  phone has not stored). It also keeps the Tink and Nostr secrets it already learned. Real revocation needs
  per-device keys (WP05) or replacing the identity.
- A removed or dormant device that still holds the key can also pose as `primary` and receive media the
  primary is a recipient of.
- Dormancy is checked when a request or ack arrives, not by a timer: if nobody acks, nothing is deleted. The
  next ack by any active device frees everything that dormancy released.
- A message already deleted for a dormant device is gone. The gap flag tells the device that something is
  missing; it can't say what.
- Mailbox `head` and the new device's starting position are global seq values, not per-identity counters.
- Pre-link pending messages (seq ≤ head) are not filtered from a new device's inbox if it asks with a smaller
  `after`; the client must start from `head`.
- Row costs are measured in `linux-port/evidence/WP04.md`; the media object's `deleteAll` is not counted by
  the SQL meter.

## Limits (pilot defaults)

| Limit | Value | On breach |
|---|---|---|
| Envelope | 128 KiB | `envelope_too_large`, not retryable |
| Pending per recipient | 32 MiB | `recipient_full`, retryable |
| Pending in total | 256 MiB | `mailbox_full`, retryable |
| Deliveries per request | 16 | `too_many_deliveries` |
| Linked devices besides the primary | 3 | `409 device_limit`, not retryable |
| Dormancy | 72 h without contact | the device stops holding the queue and media |

Caps refuse new messages. Nothing already accepted is ever evicted to make room.
