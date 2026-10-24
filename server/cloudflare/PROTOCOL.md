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
{ "v": 1, "deliveries": [ { "to": "<recipient pubkey hex>", "id": "<client delivery id>", "envelope": "<gift wrap JSON>" } ],
  "media": [ "<sha256 hex of an uploaded blob>", ... ] }
```

`media` is optional and is **new (WP04-AC4)**; see [Attaching media to a send](#attaching-media-to-a-send-wp04-ac4).
A request without it, or with `"media": []`, is handled and answered exactly as before.

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

### Attaching media to a send (WP04-AC4)

A device can be enrolled after a file is uploaded and before the message that refers to it is delivered. The
upload snapshot does not contain that device, so it would receive the message and get `404` for the file. The
server cannot see which blob an encrypted envelope refers to, so the sender says so, next to the envelopes:

```json
{ "v": 1, "deliveries": [ ... ], "media": [ "0f…64 lowercase hex…", "…" ] }
```

- **Wire shape.** `media` is a JSON array of **distinct, lowercase, 64-character hex SHA-256 digests** (the
  address used in `PUT /v1/media/<sha256>`). At most **8** (`MAX_MEDIA_REFS`; a 100 MiB file is at most 4
  parts). It sits in the signed request body, outside the envelopes, so the sender's signature covers it. It
  applies to every delivery in the request. The server learns only digests it already holds from the upload.
- **Parsing.** Absent or `[]`: legacy, nothing changes. Wrong type, a non-string, uppercase, wrong length,
  non-hex or a duplicate: `400 bad_request`. More than 8: `400 too_many_media`. Nothing is queued in either case.
- **Check before queuing.** Every digest must be a blob that exists, was **uploaded by this sender's identity**
  (the first uploader; a later identical PUT by someone else does not make them an owner) and has not outlived
  its lifetime. Otherwise `409 media_unavailable` (not retryable: re-upload, then send). A missing, foreign and
  expired blob give the same answer, so it is no existence oracle. Nothing is queued.
- **Grant, atomically with the queue.** In the same synchronous transaction that stores the messages the server
  records, for each digest, the `(recipient, device)` pairs of the recipients whose copy was **newly stored**
  (not `duplicate`, not `rejected`) that are **active at that instant** (not revoked, primary or seen within 72 h;
  an identity without devices is `primary`). Those are exactly the devices that will see this message. A device
  enrolled later, a revoked device and a dormant device are not in the set.
- **Apply.** The blob then adds those pairs (idempotent, exclusive with confirm, settle, repeat PUT and the
  alarm). A pair is added **only if its recipient pubkey is already a recipient of that blob** (named at PUT,
  which checked membership), so access never extends to a new identity, only to more devices of an existing
  one; a pair already present keeps its confirmation. The lifetime alarm is untouched, and a newly added linked
  device re-arms the dormancy alarm exactly as after a confirm.
- **Reply.** The usual `{ "v":1, "results":[…] }` plus `"media": [ { "hash", "status" } ]` in request order:
  `attached` (applied), `unchanged` (no copy was newly stored, nothing to grant), `gone` (the blob was erased or
  expired between the check and the apply: the message is queued, but this blob is not reachable by the late
  devices; re-upload and resend), `pending` (the blob could not be reached; the grant is stored and retried
  every minute by an alarm and by the next media send; the message is queued).
- **Retry.** Resend the same `(id, envelope, media)`. A retry whose messages are `duplicate` grants nothing new.

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

Since WP05-A the attachment also stores the **key that signed the upgrade**, and every frame and live push
re-checks it against current state. A socket authenticated with the shared identity key is closed with
`4001 auth_invalid` as soon as the identity migrates (the phone reconnects with the admin key); a socket
whose device key was revoked closes with `4009 unlinked`, and one whose device key was rotated closes with
`4001 auth_invalid`. The same checks run inside `sync`, `ack`, `signal` and delivery, so a socket that
survives a close sweep (a failed close, a hibernation race) still cannot page, ack, relay or receive.

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

> **This is not authentication by itself.** `device=primary` is only a query parameter. Before WP05 an
> identity that had not migrated could sign any `device` value with its shared Nostr key, so a linked
> desktop could act as `primary` and ack on behalf of any other device. That was never access control
> (readiness gate G1). WP05-A now binds each enrolled device to its own key: the parameter must match the
> device that key was enrolled for. Legacy identities that never migrate keep the old shared-key
> behaviour, which remains the G1 limit described below.

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
- `GET` and `DELETE` (confirm) work for a snapshotted pair. A device that joined after the upload gets `404`
  unless the message was sent with that digest in `media` while the device was active (WP04-AC4, above); an
  unlinked id gets `403 unknown_device`. A `DELETE` from the primary only confirms the primary's share.
- The object is erased when every pair has confirmed. At each confirm the server also **waives** pairs whose
  device was unlinked or has been dormant for more than 72 hours (same policy as the queue), and arms the alarm
  for the moment the next waiting device would turn dormant, so a desktop that vanished after an upload holds
  the file for at most about 72 hours after its last contact, not the full 8 days. The 8-day expiry is
  unchanged.
- To let the uploader's own other devices fetch a blob, the uploader names its **own pubkey** in
  `x-relay-recipients`.

### Device authorization (v1.2, WP05/G1)

This closes gate G1. The WP04 cursors are queue bookkeeping; they are not authentication. A phone that
migrates its identity installs a **phone-only administration key** (an ordinary Nostr key that is not a
member). After migration the shared identity key no longer authenticates device operations; only the
administration key may enroll or revoke devices.

| Request | Auth | Effect |
|---|---|---|
| `POST /v1/devices/admin {"v":1,"admin_pubkey":"<64 hex>"}` | NIP-98, shared identity key | Migrates the identity. Stores the admin key. A second attempt with the shared key is refused with `401 auth_invalid` (the shared key is disabled by then). The admin key must differ from the identity key, must not be a member, must not already be a device key on any identity and must not be another identity's admin key (`409 admin_key_in_use`). On success every socket of the identity is closed with `4001 auth_invalid`. Reply: `{"v":1,"migrated":true,"head":<seq>}`. |
| `POST /v1/devices?device=primary {"v":1,"device":"<32 hex>","label":"…","auth_pubkey":"<64 hex>"}` | NIP-98, admin key | Enrolls a device's own authentication key. Binds id, key, identity, creation time and protocol version. A key already bound to another `(identity, device)` pair is refused with `409 auth_key_in_use`; a key that is an admin key with `409 admin_key_in_use`. Re-enrolling the same pair with a different key rotates it: the old key stops resolving and that device's sockets are closed with `4001 auth_invalid`. Rotation is not revocation — use `DELETE` for that. Reply: `{"v":1,"head":<seq>}`. |
| `GET /v1/devices?device=primary` | NIP-98, admin key | Lists devices. |
| `DELETE /v1/devices/<id>?device=primary` | NIP-98, admin key | Revokes the device and closes its sockets with `4009 unlinked`. |

Each enrolled device signs every request with its **own** key. The server resolves the signer to its
`(identity, device)` pair and refuses a key acting as a device it was not enrolled for. The `device` query
parameter is still required and must match the resolved device. `device=primary` with a device key is
`403 not_primary`. Revoked devices get `401 auth_revoked` on HTTP and close code `4009` on WS, including
media and SFU-proxy surfaces. Enrollment also refuses a device id or key already bound to another identity
with `409 auth_key_in_use`; the `devices.auth_pubkey` UNIQUE index makes one key resolve to exactly one
`(identity, device)` pair. Ids are scoped per identity in storage.

Administration-key operations are replay-protected by the same NIP-98 event-id table as every request and
rate-limited to 10 per identity per minute in memory. Enrollment and revocation are serialised in one
`transactionSync` section, so concurrent calls cannot exceed the cap.

**Revocation semantics.** Revocation deletes no keys from the computer. It stops that device's key from
using this server: inbox, ack, WebSocket, media and SFU proxy all return `401 auth_revoked` (WS close
`4009`). A revoked device cannot reconnect, read the queue, ack, or fetch blobs. It still holds the Nostr
and Tink secrets it already learned, so it can decrypt anything it already downloaded and can send
messages to contacts directly. Full cryptographic revocation requires a new identity and re-pairing
(LP-D02 consequence, 09 R3). Cursor removal alone was never revocation; this contract is server-side
access revocation only.

### Link slot (v1.3, WP05 part B)

The phone seals **identity keys, contacts, groups and settings** — never messages or media (LP-D06) — into
an opaque bundle of at most 128 KiB and parks it under a one-time code. The desktop proves the code and
fetches the bundle. This is the only unauthenticated data path on the server.

**What the slot is.** The client generates a 32-byte random code and sends the QR containing the code hex.
`<slot>` is `sha256(lowercase hex of the code)` as UTF-8, 64 lowercase hex characters. The server stores
only `<slot>`, so it never learns the code and cannot compute it back. The proof header carries the same
code hex; the server hashes it and compares the digests in constant time.

| Request | Auth | Effect |
|---|---|---|
| `PUT /v1/link/<slot>` body = sealed bundle | NIP-98, admin key (or the shared identity key before migration), primary only | Stores the bundle for **10 minutes**. One live slot per identity: a second PUT is refused with `409 link_busy` and never replaces the first. An expired slot is discarded first, so it never blocks a new link. Reply `{"v":1,"slot":…,"size":…,"expiresAt":…}`. |
| `GET /v1/link/<slot>`, header `x-relay-link-proof: <code hex>` | the code | Returns the bundle bytes (`application/octet-stream`) and marks a **60 s retrieval lease** in `x-relay-link-lease`; `x-relay-link-expires` carries the slot TTL. A repeated GET returns the bundle again. |
| `DELETE /v1/link/<slot>`, same header | the code | **Completion**: erases the bundle. Reply `{"v":1,"consumed":true}`. A later GET or DELETE is `404`. |

**Why a header, not `?proof=`.** URLs are written to access logs, proxy logs, browser history and crash
reports. A code in the query string leaks the one secret that protects the bundle. The proof is a request
header, which those logs do not record (Cloudflare access logs record the path and query, not arbitrary
headers). The mailbox refuses any query string on this route with `400`, so a client cannot silently fall
back to the leaking form. Nothing about the code is ever logged by the server.

**Why a lease and an explicit completion.** A single GET that deletes would destroy the bundle if the
response were lost after the server committed — the desktop would hold no keys and the phone would have to
re-seal. Instead GET only stamps `lease_until = now + 60 s` and keeps the bundle; the desktop stores it
durably and then calls DELETE. If the response is lost, the desktop simply GETs again (the lease is
informational, not exclusive). If the desktop dies before completing, the bundle stays until the 10-minute
TTL. The lease is what makes an unfinished retrieval visible; it never *removes* availability.

**Guessing.** Every read attempt counts against two in-memory buckets, `slot` and `CF-Connecting-IP`
(`unknown` when the header is absent), at most **10 per minute each** (`LINK_ATTEMPTS_PER_MINUTE`). Over
the limit is `429 rate_limited`, retryable. Both an existing and a nonexistent slot are counted the same
way, so `429` is not an existence oracle. A wrong proof and an unknown or expired slot both return
`404 not_found` with the same body; a *missing* proof is `401 auth_missing` and a proof that is not 64 hex
is `401 auth_invalid` (neither depends on the slot existing). Rate-limit state is best effort: it
lives in the object's memory and a restart forgets it. The real defence is the 256-bit code: guessing it is
infeasible, and the limiter only bounds the cost of the attempt.

**Bounds.** The body cap is checked at the edge and again in the object (`413 envelope_too_large`). Slots
are one row per identity, so the table is bounded by the member count; expired rows are removed on the
owner's next PUT or on a read of that slot.

### Sync channel (v1.3, WP05 part B)

`TYPE:SYNC` bodies (05 §3) keep travelling as ordinary end-to-end messages to one's own pubkey. The sync
channel is the bounded transport for the ones that are too large or too stateful for a 16 KiB envelope:
SENT copies and KEYS rotations. It is authenticated with the same device authorization as every other
route, so only this identity's own keys can read or write it.

**Envelope.** A versioned part carries a unique sync id, a revision, the part index and count, the total
decoded size, the SHA-256 of the assembled body and an expiry:

```json
{"v":1,"id":"<8-64 hex>","rev":1,"part":0,"parts":2,"total":8000,
 "hash":"<sha256 of the assembled body>","expires":1760000000000,"data":"<base64 part>"}
```

| Request | Effect |
|---|---|
| `POST /v1/sync` | Stores one part. When every index `0..parts-1` is present, the body is assembled, its size and SHA-256 are verified, and it becomes an event with a cursor. Reply `{"v":1,"id":…,"rev":…,"part":…,"parts":…,"complete":bool}`, plus `"cursor"` when complete and `"duplicate":true` for an identical re-send. |
| `GET /v1/sync?after=<cursor>&limit=<n>` | Events this device has not seen, oldest first: `{"v":1,"events":[{"cursor":…,"id":…,"rev":…,"total":…,"hash":…,"data":"<base64>"}],"more":…,"cursor":…}`. Without `after`, the caller's stored cursor is used. |
| `GET /v1/sync/<id>` | One assembled event, or `404` while it is incomplete, expired or unknown. |
| `POST /v1/sync/ack {"v":1,"through":<cursor>}` | Advances this **device's** cursor (one row), never backwards and never past the newest event. Reply `{"v":1,"cursor":…}`. |

**Bounds and failure behaviour.**

- Decoded body ≤ **12 KiB**, so its base64 form still fits the existing 16 KiB envelope bound; a larger
  `total` or part is `413 envelope_too_large`.
- At most **8 parts** per envelope; `part` must be `< parts`, and every part must agree with the first on
  `rev`, `parts`, `total` and `hash` (`409 sync_conflict` otherwise).
- At most **4 unfinished assemblies** per identity (`409 sync_limit`, retryable). A finished event is
  capped by **64 events / 256 KiB** per identity, likewise `409 sync_limit`.
- **Duplicates:** the same part with the same bytes is accepted and answered `"duplicate":true` without
  writing. The same `id` with a different `rev`, or different bytes for an existing part, is
  `409 sync_conflict`; the original assembly is untouched.
- **Bad hash or impossible size:** the assembly is dropped (`400 bad_request`); no event exists and no
  part remains. Nothing partial is ever served.
- **Malformed / unknown version:** `400 bad_request`, and no cursor or assembly is touched.
- **Expiry:** an event expires after the client's `expires`, clamped to at most 24 hours
  (`SYNC_TTL_MS`). An expired event, or its remaining parts, answers `404` and `sync_parts` rows are
  deleted on that identity's next request.

The cursor is per `(identity, device)`, like the mailbox cursors, and is independent of `acked`. Events
stay until their expiry: an ack frees the device, not the server row, which is bounded by the caps above.

### Error codes added by the link slot and sync channel

| Code | Status | Meaning | Retryable |
|---|---|---|---|
| `link_busy` | 409 | this identity already has a live link slot, or that slot value is in use | no |
| `sync_conflict` | 409 | same sync id with a different revision, or different bytes for a part | no |
| `sync_limit` | 409 | too many unfinished assemblies, or the event/byte cap is reached | yes |
| `media_unavailable` | 409 | `POST /v1/send` `media` names a blob that does not exist, was not uploaded by the sender, or has expired; nothing was queued | no |
| `too_many_media` | 400 | `POST /v1/send` `media` has more than 8 digests; nothing was queued | no |

Every other failure uses the codes above (`bad_request`, `not_found`, `auth_missing`, `auth_invalid`,
`auth_replayed`, `not_allowed`, `not_primary`, `rate_limited`, `envelope_too_large`, `storage_error`).

### Compatibility (legacy clients)

- No `device` parameter: identical requests and replies. An identity that has not migrated behaves as WP04,
  including `POST /v1/devices` with the shared identity key. After G1 every request resolves its signer with
  one indexed `devices` lookup even for a legacy identity, so "no extra rows" no longer holds: it is one
  extra read per request, and there is no write unless the identity has devices. After migration the shared
  key is refused for device operations (`401 auth_invalid`); senders are unaffected.
- A legacy 1.15 phone on an identity that later has linked devices keeps working as `primary`. It sees the
  queue held for the desktop too (messages are deleted only when the desktop has acked as well, or goes
  dormant), and its media confirm only confirms the primary's pair. Its replies stay byte-identical, so it
  never learns about a gap (it can't have one: the primary is never dormant).
- Differences that exist only once an identity has linked devices: messages (and media) can stay on the server
  up to 72 hours longer than before; `409 device_limit`, `403 unknown_device`, `403 not_primary`, close code
  `4009` and the `gap`/`oldest` fields are new.
- `media` on `POST /v1/send` is new and optional. A client that sends none (every client today) gets a
  byte-identical reply, no `media_grants` table, no extra RPC and no extra rows (`test/media-race.test.ts`).
  Until a client sends the digests, the upload, enrol, send race stays open for it.
- Route `/v1/devices` and `/v1/devices/<id>` are new; an old server answers them `404`.
- Routes `/v1/link/<slot>` and `/v1/sync…` are new and are only used by clients that opt in. A client that
  never links writes no `link_slots`, `sync_parts`, `sync_events` or `sync_cursors` rows, and the bytes of
  its send/inbox/ack/socket/media replies are unchanged (`test/legacy-link-sync-compat.test.ts`).

### Known limitations (read before relying on this)

- **Revocation is server-side access revocation.** A revoked device's key cannot use this server, but the
  computer keeps the Nostr/Tink secrets it already learned. It can decrypt what it already downloaded and
  can send directly to contacts. Replacing the shared identity and re-pairing is the only full revocation.
- **Migration does not un-learn the shared identity secret.** Closing the sockets and refusing the shared
  key stops future server use, but a device that already held the identity key still has it. That is the
  residual G1 limit: only a new identity and re-pairing removes it.
- **Session lifetime after these fixes.** Migration closes the identity's sockets immediately (`4001`), and
  rotation closes only the rotated device's socket. A socket is still capped at the 1-hour session TTL, and
  expiry (`4001 auth_expired`) or removal from the member list (`4003`) closes it. Every remaining frame
  and push is re-checked against the signer, so the sweep is a fast path, not the only barrier.
- **The admin key is on the phone.** The phone-only admin key is the authority. Anyone who extracts it can
  enroll or revoke devices. The app stores it in Keystore-backed encrypted preferences.
- **A device key is bound to one `(identity, device)` pair.** Re-enrolling a revoked id revives it at the
  head and rotates its key. An admin key cannot target another identity's devices; the admin key's own
  binding is the target, so a key from another identity is refused by membership (`403 not_allowed`).
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
| Media digests per send (`media`) | 8 | `400 too_many_media` |
| Linked devices besides the primary | 3 | `409 device_limit`, not retryable |
| Dormancy | 72 h without contact | the device stops holding the queue and media |
| Link bundle | 128 KiB | `413 envelope_too_large`, not retryable |
| Link slot TTL | 10 min | `404` on read and completion |
| Link retrieval lease | 60 s | not a hard limit: the bundle stays readable until the TTL |
| Link proof guesses | 10 per minute per slot and per client | `429 rate_limited`, retryable |
| Link slots per identity | 1 live | `409 link_busy`, not retryable |
| Sync body | 12 KiB decoded (≤16 KiB base64) | `413 envelope_too_large` |
| Sync parts per envelope | 8 | `400 bad_request` |
| Unfinished sync assemblies per identity | 4 | `409 sync_limit`, retryable |
| Sync events per identity | 64 events / 256 KiB | `409 sync_limit`, retryable |
| Sync retention | 24 h maximum | `404` after expiry |

Caps refuse new messages. Nothing already accepted is ever evicted to make room.
