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
| client → server | `{"type":"signal","to":<pubkey>,"envelope":<gift wrap>}`: typing or presence, passed to the recipient's open sockets and **never stored** (≤16 KiB, ≤120 a minute per socket; a recipient who isn't connected misses it); also carries call bodies, see [Calls](#calls-v14-wp11) |
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

### Own SENT media grants (WP09)

A publisher may add **`"media":["<64 lowercase hex SHA-256>",…]`** to the signed JSON body of
`POST /v1/sync?device=<publisher-device>`. At most **8 distinct digests**, using exactly the `/v1/send`
validation (`400 bad_request` for malformed/duplicate references, `400 too_many_media` above the cap).
This is outside the opaque encrypted `data`, and covered by NIP-98's body hash.

```json
{"v":1,"id":"abcdef0123456789","rev":1,"part":0,"parts":1,"total":2,
 "hash":"<assembled encrypted event SHA-256>","expires":1760000000000,"data":"<base64>",
 "media":["<uploaded encrypted blob SHA-256>"]}
```

- `media` is a **publication attribute of the request that completes assembly**, not a stored part
  manifest. Publishers should repeat the same list on **every part and retry**, including out-of-order
  uploads: whichever request completes the event supplies its grant list. Earlier partial requests do
  not save a list, check blob ownership, create an outbox, or grant anything. Omitting it on the completing
  request uses the legacy path, even if an earlier part carried it. The server cannot inspect SENT vs KEYS
  inside opaque bytes; clients attach references only to file SENT events.
- After full size/hash verification, each blob must be live, **uploaded by this authenticated identity**,
  and already include **that same identity** among its PUT recipients. Otherwise `409 media_unavailable`:
  no event/grant is published; held parts can be retried after correcting availability. The PUT must name
  contact **and own pubkey** in `x-relay-recipients`. Confirmation keeps confirmed recipient rows while
  another pair is waiting, so origin confirmation does not erase proof of own-recipient authorization.
- At completed publication, one synchronous SQLite transaction inserts the event and one existing
  `media_grants` outbox row per digest, containing only `(own identity, active device)` pairs at **that
  instant** (primary included; revoked/dormant devices excluded). Publisher binding and event expiry are
  rechecked after RPC awaits. No awaits occur in this transaction. The retry alarm is armed beforehand.
- Blob RPC application is **not** a distributed atomic transaction. The event can be fetched before its
  blob grant applies; `GET` may temporarily be `404`. A successful completing reply adds
  `"media":[{"hash":"…","status":"attached"}]`; `pending` means the durable outbox will retry via alarm,
  and `gone` means the blob disappeared/expired between validation and application. Neither status rolls
  back an already published event or resurrects a blob. Consumers may retry transient missing blobs;
  do not treat `complete:true` alone as proof that every blob is available.
- A completed duplicate does not enqueue, resnapshot, validate newly supplied blob references or widen
  access. Its entries say `unchanged` (no new grant, **not** a blob-availability guarantee), just like send;
  it may drain the existing outbox. Alarm/retry only applies the originally durable pairs. A new event id
  is a new authenticated publication, subject to ownership checks and sync caps.
- GET never adds recipients. Existing blob idempotency preserves confirmed pairs; revocation, dormancy,
  per-device confirmation and the original 8-day lifetime still apply. A stale key after device re-enrolment
  cannot publish through an in-flight request or fetch media.
- Missing `media` or `media:[]` preserves the old replies, SQL rows and behavior; no grant RPC/table/metrics.

**History boundary:** sync is **not** the inbox's no-history cursor. A newly enrolled device starts its sync
cursor at zero and can backfill retained events for up to **24 hours**. Devices enrolled **after** completed
publication do not gain its media grant, even when that older SENT event appears in this backfill or the
publisher retries it. Existing PUT grants remain valid, but no new grant is inferred from reading history.
A backfilled file card may therefore refer to unavailable media; this extension fixes PUT → enrol → completed
SENT publication, not historical media transfer to future devices.

### Error codes added by the link slot and sync channel

| Code | Status | Meaning | Retryable |
|---|---|---|---|
| `link_busy` | 409 | this identity already has a live link slot, or that slot value is in use | no |
| `sync_conflict` | 409 | same sync id with a different revision, or different bytes for a part | no |
| `sync_limit` | 409 | too many unfinished assemblies, or the event/byte cap is reached | yes |
| `media_unavailable` | 409 | send/sync `media` names an absent, foreign-owned or expired blob (sync also requires own identity already on the blob); no message/event/grant published | no |
| `too_many_media` | 400 | send/sync `media` has more than 8 digests; nothing published | no |

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

## Calls (v1.4, WP11)

Server support for 1:1 voice calls (`linux-port/06-calls-1on1.md`, `05-protocol-changes.md` §5). Three pieces: a
credential endpoint, an `urgent` flag on a stored delivery, and the existing `signal` frame. **Not deployed**;
see `linux-port/evidence/WP11.md` for what was and was not run. Spec deviations are listed at the end.

### `GET /v1/call/ice[?device=<id>]`

Short-lived Cloudflare TURN credentials for one call. Authenticated exactly like every other mailbox request
(NIP-98, member or enrolled device key, replay-checked, `401 auth_revoked` for a revoked device, the shared
identity key refused after migration). No request body. The only accepted query parameter is `device`; any
other is `400 bad_request`. Other methods: `405 method_not_allowed`.

```json
{ "v": 1,
  "iceServers": [
    { "urls": ["stun:stun.cloudflare.com:3478"] },
    { "urls": ["turn:turn.cloudflare.com:3478?transport=udp", "turn:turn.cloudflare.com:80?transport=tcp",
               "turns:turn.cloudflare.com:5349?transport=tcp", "turns:turn.cloudflare.com:443?transport=tcp"],
      "username": "<opaque>", "credential": "<opaque>" } ],
  "expiresAt": 1790000000000 }
```

- `expiresAt` is epoch milliseconds: the instant the provider-side credential stops being valid
  (`mint time + ttl`). `ttl` is **at most 600 s** (it is 600 s by default and `CALL_ICE_TTL_S` can only lower it).
  Only the fields shown are returned. Hand `iceServers` to `RTCConfiguration` as is.
- Every URL on port **53** is removed server-side (those entries are dropped, other URLs of the same server stay).
- The server calls `POST https://rtc.live.cloudflare.com/v1/turn/keys/<TURN_KEY_ID>/credentials/generate-ice-servers`
  with `{"ttl":600}`, `Authorization: Bearer <TURN_API_TOKEN>`, and the honest header
  `User-Agent: RelayMailbox/1.0 (call-ice; Cloudflare Worker)` (Cloudflare's edge answers default library
  agents such as `Python-urllib` with error 1010). Redirects are not followed (`redirect: manual`; any non-2xx,
  including 3xx, is a failure). The response is read with an 8 KiB cap and a 5 s deadline, parsed, validated
  (schemes `stun|turn|turns`, hosts `*.cloudflare.com`, at most 8 servers × 8 URLs, credential strings ≤512
  printable characters, at least one TURN server with credentials) and reduced to the shape above.
- `TURN_KEY_ID` and `TURN_API_TOKEN` are Worker **secrets** (`wrangler secret put`), never `vars`, never in source
  and never in a response, a log line or an error. Both must match `[A-Za-z0-9_-]{8,512}`, otherwise the endpoint
  answers `503 not_configured`. Nothing is stored: credential expiry and revocation are the provider's job
  (there is no revoke endpoint here).
- A caller's answer is reused for 60 s (so retries, glare and multi-device reconnects make one provider call).
  A reused answer keeps its original `expiresAt`, so it always has at least 540 s left. Failures are never cached.
  A device revoked while the provider call is in flight gets `401 auth_revoked` and no credentials.
- Limit: 6 requests per identity per minute (in memory, per Durable Object instance), counted before any storage
  access. The authorization id of an accepted request is recorded for replay protection: 1 row written.

| Status | `error.code` | Meaning | Retryable |
|---|---|---|---|
| 400 | `bad_request` | unknown query parameter or malformed `device` | no |
| 401 | `auth_missing`, `auth_invalid`, `auth_expired` | missing, malformed, wrongly bound or stale NIP-98 header; shared identity key after migration | no |
| 401 | `auth_replayed` | that authorization event was already used | no (sign a new one) |
| 401 | `auth_revoked` | the signing device was revoked (also if revoked during the provider call) | no |
| 403 | `not_allowed` | the key is not a member | no |
| 405 | `method_not_allowed` | not `GET` | no |
| 413 | `request_too_large` | declared body over the edge cap | no |
| 429 | `rate_limited` | more than 6 requests in a minute | yes |
| 502 | `upstream_failed` | the provider refused, timed out, redirected, answered too much, or answered something unusable | yes |
| 503 | `not_configured` | `TURN_KEY_ID` / `TURN_API_TOKEN` absent or malformed on this deployment | no |

A call that lasts longer than the credential must fetch fresh credentials before an ICE restart; the endpoint
is cheap (cache, limit above) so clients should call it at call start and again on restart.

### `urgent` on a delivery (call invite wake)

`POST /v1/send` deliveries accept one optional field:

```json
{ "to": "<recipient>", "id": "<delivery id>", "envelope": "<gift wrap>", "urgent": true }
```

`urgent` is for the stored copy of a `CALL_INVITE` (05 §6). The envelope is sealed, so the server **cannot verify**
that it is an invite; the flag is bounded by budgets instead. Absent or `false` is the legacy delivery and takes
exactly the old code path: replies, stored rows and the Firebase message are byte-identical.

- Validation: a boolean, else `400 bad_request`. At most **one** urgent delivery per request, and never to the
  sender's own identity (`400 bad_request`).
- Effect, only when the message is newly stored and the recipient has no open socket: the usual content-free
  Firebase message but **bypassing the 20 s coalescing**, as `{"type":"wake","urgent":"1"}` with
  `android: {"priority":"high","ttl":"45s"}` and **no `collapse_key`**. FCM drops it after 45 s, the same
  window as the invite's `expires` (ts + 45 s), so a late ring is never delivered by FCM. It also stamps the
  recipient's wake time, so ordinary messages in the next 20 s fold into the ring.
- Budgets (in memory, checked before storing, spent only when the delivery is stored): **1 urgent per recipient
  per 3 s**, 6 per sender per minute, 60 server-wide per minute. Over budget: `429 rate_limited`, retryable, and
  **nothing is stored or pushed** (the client retries the same delivery later). They apply to every urgent send,
  also when the recipient happens to be online. A `duplicate` resend is not woken again.
- The stored message is an ordinary message: it is acked, expires after 8 days and appears in the inbox without
  an `urgent` marker. Receivers must enforce the invite's own `expires` (the server does not age out stored
  invites). Reply shape is unchanged.

### Call signals over the socket

Call bodies travel in the existing `signal` frame (see WebSocket above): `{"type":"signal","to":<pubkey>,"envelope":…}`.
They reach **every open socket of the addressed identity** (at most 4), nobody else, and are never stored.
`to` may be the sender's own identity: that reaches the identity's other sockets, which is
how `answered_elsewhere` stops a second device ringing. Limits are unchanged by WP11: the envelope is at most
**16,384 UTF-8 bytes** (`MAX_SIGNAL_BYTES`, default; WP02 measured the largest real encrypted call frame
under 14 KiB and decided not to raise it), at most **120 signals a minute per socket**. Errors on the socket:
`unknown_recipient`, `envelope_too_large` (also for an empty envelope), `rate_limited`, `bad_request`.
Per-socket counting means an identity with 4 sockets can relay up to 480 a minute in total.

### Compatibility and spec deviations (WP11)

- No change for any client that does not use `/v1/call/ice` or `urgent`: `test/urgent-wake.test.ts` pins the
  exact legacy reply and Firebase body; `limitsFrom({})` still equals the defaults for every old limit.
- Deviation from `05 §5`: credential lifetime is **600 s**, not 3 h, and the per-caller cache is 60 s, not 10 min
  (G4 asks for short-lived credentials; a 10 min cache of a 10 min credential would hand out dead ones).
- Deviation from `G4`: the urgent flag is bounded by sender/recipient/global budgets, not tied to a server-side
  call record, because the server cannot read the envelope and a call record would need a new client-supplied
  call id and a new table. Recorded as open in the evidence.

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
| Call ICE requests | 6 per identity per minute | `429 rate_limited`, retryable |
| Call ICE credential lifetime | 600 s maximum (`CALL_ICE_TTL_S` can lower it) | n/a |
| Call ICE cache per caller | 60 s | n/a |
| Call ICE provider response / deadline | 8 KiB / 5 s | `502 upstream_failed`, retryable |
| Urgent deliveries per request | 1 (never to oneself) | `400 bad_request` |
| Urgent wake per recipient | 1 per 3 s | `429 rate_limited`, retryable, nothing stored |
| Urgent wakes per sender / server-wide | 6 / 60 per minute | `429 rate_limited`, retryable, nothing stored |
| Urgent FCM lifetime | 45 s, high priority, no collapse key | dropped by FCM |
| Signal envelope | 16,384 UTF-8 bytes (`MAX_SIGNAL_BYTES`) | `envelope_too_large` on the socket |
| Signals | 120 per minute per socket | `rate_limited` on the socket |

Caps refuse new messages. Nothing already accepted is ever evicted to make room.

## Group calls (WP15)

A group call is one `CallRoom` Durable Object per room id. The room is the SFU's missing directory: it
holds who is in the call, which provider session each participant owns and which track that session
publishes. The Cloudflare Realtime SFU has no rooms of its own.

Authorization is two independent checks: NIP-98 over the exact URL (as everywhere else) and the room's
capability token. The starter mints a random 32-hex `room` and a 32-byte random `secret`, encodes the
secret as **64 lowercase hex characters**, sends `secretHash` = lowercase hex SHA-256 of the UTF-8
bytes of that 64-hex *text* when creating the room, and sends the 64-hex value E2E to the group.
The server stores only the hash. A join presents the raw secret in the query; the room hashes it and
compares in constant time. The server never learns the group roster; it only sees which server members
connect **for an unrostered (legacy) room; a roster-bound room additionally knows its frozen member list, see
"Roster-bound rooms (G5)" below, which supersedes this sentence**. Every request also resolves the signer to an enrolled device and checks current membership
(the Mailbox's G1 device authorization); a revoked device gets `401 auth_revoked` and a non-member
`403 not_allowed`. **The pid reconnect path also calls `callAuth` on every rejoin**, so a device revoked
after the initial join cannot reuse its old pid. Heartbeats recheck at most once per 12 s; an active-room
alarm also rechecks silent sockets every 12 s, closing revoked sockets `4009 auth_revoked` and removing
them from the roster. One alarm performs at most 17 Mailbox probes and one reschedule; no participant
row is written for a healthy check. Failed Mailbox probes fail closed.

The `secretHash` contract is pinned: SHA-256(UTF-8(`0000000000000000000000000000000000000000000000000000000000000001`))
is lowercase hex `c386d8e8d07342f2e39e189c8e6c57bb205bb373fe4e3a6f69404a8bb767b417`.
Hashing the decoded 32 bytes instead is **not** this contract.

### Endpoints

| Request | Auth | Effect |
|---|---|---|
| `POST /v1/call/rooms` `{"v":1,"room":"<32hex>","secretHash":"<64hex>"[,"group":"<uuid>","revision":<n>,"rosterHash":"<64hex>","members":["<64hex>",…]]}` | NIP-98 member/device | Creates the room. Legacy (no roster fields): `{"v":1,"created":true}`. Roster-bound: `{"v":1,"created":true,"rostered":true}`. Idempotent for the same hash **and** the same roster binding; anything else for the same room is `409 room_exists`. See G5 below for the roster rules and the `409 group_room_exists` / `409 stale_revision` / `403 not_in_roster` / `400 roster_required` answers. Both forms set the response header `x-relay-call-rostered: 1\|0`. |
| `GET /v1/call/rooms/<room>[?device=<id>]` | NIP-98 member/device | `200 {"v":1,"room":"…","participants":<n>,"createdAt":<ms>}` while the room lives (a roster-bound room adds `"rostered":true,"revision":<n>,"rosterHash":"<64hex>"`); `404 not_found` after teardown, and for a roster-bound room also to an identity that is not (or no longer) in its frozen set. The only accepted query parameter is `device`. |
| `GET /v1/call/rooms/<room>/ws?secret=<64hex>[&device=<id>][&pid=<16hex>]` | NIP-98 member/device + secret | The room control socket. `426 upgrade_required` without `Upgrade: websocket`; `400 bad_request` for an unknown query parameter or malformed `room`/`secret`/`pid`; `403 forbidden` for a wrong secret; `403 not_in_roster` for an identity outside the frozen set of a roster-bound room; `404 not_found` for an unknown room; `409 room_full` at the cap; `429 rate_limited` above the join budget. |
| `POST /v1/call/rooms/<room>/end[?device=<id>]` | NIP-98 member/device + creator only | `{"v":1,"ended":true}` after telling every member `{"type":"end","by":<creator pubkey>}` and wiping the room. Any other member gets `403 forbidden`. |
| `POST /v1/call/rooms/<room>/roster[?device=<id>]` `{"v":1,"remove":["<64hex>",…][,"secretHash":"<64hex>"]}` | NIP-98 member/device + creator only | G5 removal: `{"v":1,"removed":<n>}`. See below. |
| `POST /v1/call/rooms/<room>/rotate[?device=<id>]` `{"v":1,"secretHash":"<64hex>"}` | NIP-98 member/device + creator only | G5 capability rotation: `{"v":1,"rotated":true}`. See below. |
| `POST` or `PUT /v1/call/rooms/<room>/sfu/<op>[?device=<id>]` | NIP-98 member/device + an open room socket | Proxies the provider call. `<op>` is `sessions-new`, `tracks-new`, `renegotiate` or `tracks-close`. The caller may only touch its own provider session ids. |

> The join secret travels in the WebSocket URL query string as the protocol specifies. It also appears
> inside the signed NIP-98 URL, so capturing request headers leaks it. The Worker and DO never log the
> query; edge request logs outside this code may capture the URL. The secret is per room, short-lived and
> delivered E2E. A first-frame secret would require a different handshake.

Every request except the WebSocket upgrade has a JSON body with `"v":1`; errors are the usual
`{"v":1,"error":{"code":…,"retryable":bool}}`.

### Roster-bound rooms (G5)

G5 requires that group-call access is checked against the group roster, not only "enrolled server member +
capability". **Metadata trade-off (supersedes "the server never learns the roster", LP-D12):** a roster-bound
room makes the Worker see the frozen member pubkeys of that call (not the group name, avatar or labels) for
the life of the room, and the Mailbox sees a hash of the group id, the revision, the live room id and the
creator identity. The group id itself is never stored.

**Create with a frozen roster.** Besides `room` and `secretHash`, the body carries all of:

| Field | Rule |
|---|---|
| `group` | canonical lowercase UUID (36 chars), the group id |
| `revision` | integer, 1 to 2^31-1 (`400 bad_request` above; a larger value could pin a group's slot for 24 h and cannot be superseded) |
| `rosterHash` | 64 lowercase hex. The client's `GroupCallCodec.rosterHash(roster)` value. The server stores and republishes it but does **not** recompute it (it is not given the owner) |
| `members` | 1 to 64 pubkeys, 64 lowercase hex each, **strictly ascending and unique** (rejected with `400 bad_request`, never repaired) |

Giving some but not all of the four, or any malformed field, is `400 bad_request` and stores nothing. The
creator's identity must be in `members` (`403 not_in_roster`) and, as always, a current server member with an
enrolled, non-revoked device (`403 not_allowed` / `401 auth_revoked`). The room stores the member set, the
revision, the roster hash and `sha256("relay-call-group-v1\n" + group)`; nothing else about the group.

**Joins.** A join must satisfy NIP-98, device enrolment and the join secret **and** the signer's identity
must be in the frozen member set (`403 not_in_roster`). The pid reconnect path applies the same check. Every
frame, heartbeat and active-room alarm probe re-checks it, and so does the SFU proxy, so a removed identity
cannot keep a socket or the provider surface even if the direct close was missed. A roster-bound room has no
unrostered join path.

**Legacy (unrostered) create.** A create without roster fields is accepted by default so deployed clients
keep working, and the room is *unrostered*: any current server member with the secret may join, exactly as
before (`x-relay-call-rostered: 0`; the existence body is unchanged). The Worker variable
`ALLOW_UNROSTERED_CALLS="0"` makes the pilot refuse it with `400 roster_required`. Clients that send the
roster must not fall back to a legacy create on any error; a retry for the same room id with different
roster fields (or none) is `409 room_exists`.

**One live room per (group, revision).** The shared Mailbox object holds one slot per group id hash:
`{revision, room, creator}` (bounded: 256 slots by default, 24 h trust for an unreleased slot). The claim is
one synchronous SQL step in that single object, so concurrent creates cannot both win.

- Same group and revision, different room: `409` with body
  `{"v":1,"error":{"code":"group_room_exists","retryable":false},"room":"<winner room id>"}`. The winner's
  join secret (and its hash) is never disclosed. The loser stores nothing; the client should join the
  winner's room using the secret the winner distributed (`group_start`).
- A newer `revision` replaces the slot only when the new room's creator is in the **OLD room's** frozen roster
  (the old creator or any member still listed there) as well as in its own newer set; otherwise `403
  not_in_roster` and the old room stays live. A removed member of the old room cannot supersede it. The older
  room is then told to end: its members get `{"type":"end","by":<creator>,"reason":"superseded"}` and
  its sockets close 1000. An older `revision` than the live one is `409 stale_revision` (no room named).
- The slot is released when the room is torn down for any reason (creator end, last leaver, grace alarm,
  unjoined expiry, supersession). If a release is lost, the next create finds the owner room not live
  (`roomLive` RPC) and takes the slot over by compare-and-swap; an owner that cannot be reached counts as
  live (fail closed, `409 group_room_exists`). The takeover is refused while the slot is younger than 30 s
  (the claim-to-insert gap: a winner that has claimed but not yet written its room row looks dead), answered
  `409 group_room_exists` with `retryable: true`. A room counts as live while it is younger than the unjoined
  TTL, while anyone is connected, **or** while any participant's `last_seen` is inside the reconnect grace.
- **Per-identity cap:** one identity holds at most 4 live slots (`MAX_CALL_SLOTS_PER_IDENTITY`) in addition to
  the 256 global cap. At the cap the creator's own oldest room that nobody ever joined is ended to make room;
  if every one is joined, `429 rate_limited`. This stops one member filling the global table.
- **Slot upkeep:** the room's alarm refreshes its slot at most every quarter of the 24 h TTL, so a call longer
  than 24 h keeps it.

**Before real clients ship: `ALLOW_UNROSTERED_CALLS` must be set to `"0"`.** It defaults to on only so the
old pilot clients keep working; while on, a client can create an unrostered room that has none of the
roster protections above. This is a deployment gate, not a default to keep.

**Limits that remain without a signed roster (B1 residual, BLOCKED cross-layer gate).** The member list is
still claimed by the creator and not verified by the server (no signed owner roster revision exists in the
contract). Therefore: (1) any enrolled member who knows a group UUID can **squat** an unclaimed
(group, revision) slot with a self-chosen set for up to the unjoined TTL (12 min) or until the creator ends
it; (2) a member who was **ex-roster** in the real group but is listed in the live room's frozen set (or is the
old creator) can still supersede that room with a higher revision; (3) the supersede check only proves
membership in the old claimed set, not in the real group. Closing these needs the signed roster revision
(WP02/WP16 contract), which this server work does not invent.

**409 oracle.** `409 group_room_exists` answers any enrolled member, with the winning room id, whether a call
exists for a group UUID they name (`409` vs `200`). The group UUID is a 122-bit value so this is not a
guessing oracle for unknown groups, but anyone who holds a UUID learns that a call is live and its room id
(never the secret). `403 not_in_roster` on a supersede likewise confirms a slot exists.

**Removal.** `POST /v1/call/rooms/<room>/roster {"v":1,"remove":[…][,"secretHash":…]}` is creator-only. `remove`
is 1 to 64 pubkeys, ascending and unique, each currently in the set and none the creator
(`400 bad_request` otherwise; `409 roster_required` for an unrostered room; `403 forbidden` for a non-creator;
`404` unknown room). The set can only shrink: additions need a newer revision's room. For each removed identity
the Worker deletes it from the set, closes **all** its sockets with close code **4009** reason `removed`,
marks its participants left (broadcast `left`; the existing cleanup path closes the provider track durably
via `pending_track_close`, retried by the alarm) and refuses its rejoin. Removal is idempotent: an identity
that was already removed is a no-op success (`removed` counts only newly removed identities; an identity that
was never listed is still `400`). All removed sockets are closed before any provider call is awaited. The
removed participant's **pulled (subscribed)** provider mids, recorded from `tracks-new` remote results (up to
256 per participant), are closed on its session through the same bounded cleanup path with the same durable
retry as its published mid. Teardown deletes the roster rows, so a same-id create after a deferred teardown
cannot fail. The Cloudflare SFU has no
session-delete call: a removed participant's provider *session* is not closed explicitly; its track is
force-closed and the session lapses at the provider. An optional `secretHash` rotates the join capability in
the same call (see below). The frozen `rosterHash` is **not** changed by removals; the live set is the frozen
set minus removed identities. Ending the room (`/end`) revokes everyone.

**Capability rotation.** `POST /v1/call/rooms/<room>/rotate {"v":1,"secretHash":"<64hex>"}` (creator only)
replaces the stored hash. Existing sockets stay; new joins (and pid reconnects) need the new secret. Works on
unrostered rooms too. Rotation does not by itself revoke an identity; use `roster` for that.

**Bounds.** Participants stay capped at 17 (`409 room_full`); `pid` is 16 hex, provider session ids and
mids match `[A-Za-z0-9_-]{1,256}` / `{1,64}`, 64 tracks per request, 16 KiB SDP, 8 KiB create/roster/rotate
bodies. Malformed JSON, non-object, non-string `type`, wrongly typed `state` fields and binary frames get an
`error` frame or a 4xx and never close or corrupt the room (regression-tested).

**Not provided (honest limits).** The roster is *claimed by the creator*: the server has no signed owner
roster, so a server member who knows a group UUID can claim a slot with a self-chosen member set (squatting
a revision, or superseding with a higher revision they list themselves in). Only a signed roster revision
(WP02/WP16 follow-up) closes that. Only the creator may remove members or rotate, so a group owner who did
not start the call must have the creator's client act, or end the room. Roster-bound answers prove nothing
about the E2EE of the call (WP17).

### Join and roster frames

On a successful upgrade the room sends:

```json
{"type":"welcome","v":1,"you":"<pid>","participants":[
  {"pid":"<pid>","pubkey":"<hex>","device":"<id>","sessionId":null,"track":null,"muted":false}
]}
```

`participants` lists the **other** participants; the caller's own entry is `you`. `sessionId`, `track`
and `muted` reflect what the room knows. The roster never includes the secret, the secret hash or any
provider credential.

The client sends text frames:

| Frame | Effect |
|---|---|
| `{"type":"heartbeat"}` | Keepalive only. The room refreshes the hibernatable attachment; no row write. |
| `{"type":"state","muted":bool,"deafened":bool?}` | Stores and broadcasts the mute/deafen hint. |
| `{"type":"leave"}` | Leaves immediately; the room closes the socket `1000 "left"` and tears down if this was the last participant. |
| `{"type":"end"}` | Creator only. Ends the room for everyone (`{"type":"end"}`), then wipes it. |

Unknown frame types get `{"type":"error","code":"bad_request"}` and the socket stays open. A frame from a
revoked device gets close `4009 "auth_revoked"`.

The room sends:

| Frame | Meaning |
|---|---|
| `{"type":"welcome",…}` | The join succeeded; the roster above. |
| `{"type":"joined","pid","pubkey","device","sessionId","track","muted"}` | A participant joined or published its mic. |
| `{"type":"left","pid","pubkey","device"}` | A participant left. |
| `{"type":"state","pid","muted","deafened"}` | A participant changed mute/deafen. |
| `{"type":"end","by":<pubkey>}` | The creator ended the room. |
| `{"type":"error","code":"…"}` | A frame was malformed or forbidden. |

Close codes: `1000` normal (`left`, `ended`, `expired`), `4003` no session attachment, `4009` replaced by
a duplicate join or revoked device. A dropped socket keeps the participant's `pid` for 30 s; a reconnect
with the same `pid` and the same NIP-98 signer/device reuses it. After 30 s with no active socket the
room tears itself down and every later request gets `404`.

### SFU proxy operations

The Worker holds `SFU_APP_ID` and `SFU_APP_SECRET` as Worker **secrets** (`wrangler secret put`), never
vars, never in source, never in a response or a log. If either is absent or malformed, the endpoint
answers `503 not_configured`. The provider base URL is fixed:
`https://rtc.live.cloudflare.com/v1/apps/<SFU_APP_ID>/…`. The Worker sends
`Authorization: Bearer <SFU_APP_SECRET>`, `User-Agent: RelayMailbox/1.0 (call-room-sfu; Cloudflare Worker)`,
`redirect: "manual"`, a 15 s deadline and a 256 KiB response cap. A non-2xx, a redirect, a timeout or an
oversized/unparseable body is `502 upstream_failed` (retryable). The caller cannot choose a URL.

The bodies are narrowed to the provider's shape:

| `<op>` | Client body | Provider call |
|---|---|---|
| `sessions-new` | `{"v":1,"offer":"<sdp>"?}` | `POST …/sessions/new` with `{"sessionDescription":{"type":"offer","sdp":"…"}}` |
| `tracks-new` | `{"v":1,"sessionId":"…"?,"sessionDescription":{"type":"offer","sdp":"…"}?,"tracks":[…]}` | `POST …/sessions/<sessionId>/tracks/new` with the same `tracks` and offer |
| `renegotiate` | `{"v":1,"sessionId":"…"?,"answer":"<sdp>"}` | `PUT …/sessions/<sessionId>/renegotiate` with `{"sessionDescription":{"type":"answer","sdp":"…"}}` |
| `tracks-close` | `{"v":1,"sessionId":"…"?,"mids":["…"]}` | `PUT …/sessions/<sessionId>/tracks/close` with `{"tracks":[{"mid":"…"}],"force":true}` |

`tracks` has 1–64 entries. A local entry is exactly `{"location":"local","mid":"<id>","trackName":"mic"}`
(at most one per request). A remote entry is
`{"location":"remote","sessionId":"<publisher session>","trackName":"mic"}`; the publisher must be another
participant currently in the room (its current or 30 s grace session id). Any other location or track name
is `400 bad_request`. The response is reduced to `v`, `sessionId`, `tracks` (`mid`, `trackName`),
`sessionDescription` and `requiresImmediateRenegotiation`; provider-only fields are dropped.

Mutations for a participant are serialised: concurrent `sessions-new`/`tracks-new` requests cannot
replace an unclosed local mic. Session rotation closes the current published track first and keeps only
the newest current and previous session ids (previous is authorized for up to 30 s). Older sessions
are no longer addressable through Relay; the provider has no session-delete endpoint, so empty sessions
expire under provider policy, **not** a Relay-enforced deadline. Local publishing on a previous session
is refused. Provider close failure keeps a durable retry entry and refuses further publish/rotation until
closure succeeds; alarm retries may continue indefinitely if provider credentials or service are lost.
A provider response without a valid `mid` is rejected and the requested MID is quarantined for cleanup;
provider behavior when it returns a different, unreported MID is not verifiable here.

Room creates are limited to 30 per identity per rolling minute in the *shared Mailbox DO*, across all
room ids. A bounded SQLite budget is pruned on create (up to member-count × limit rows plus expired
rows until the next create), so eviction does not reset it. Unjoined rooms expire after 12 minutes on
both existence and upgrade, even if their alarm fires late.

A participant can only name its own current session id or its previous one within the
30 s grace window; another participant's session id is `403 forbidden`.

### Limits (pilot defaults)

| Limit | Value | On breach |
|---|---|---|
| Participants per room | 17 | `409 room_full` |
| Reconnect grace for a dropped socket | 30 s | room tears down |
| Joins per room per minute | 60 | `429 rate_limited`, retryable |
| SFU operations per identity per minute | 120 | `429 rate_limited`, retryable |
| Create/end/existence body | 8 KiB | `413 request_too_large` |
| SFU request body | 64 KiB | `413 request_too_large` |
| SFU SDP string | 16 KiB | `400 bad_request` |
| Tracks per SFU request | 64 | `400 bad_request` |
| SFU provider response | 256 KiB | `502 upstream_failed`, retryable |
| SFU provider deadline | 15 s | `502 upstream_failed`, retryable |

### Measured cost (workerd, `EXPOSE_METRICS=1`)

The scripted five-participant create/join/leave sequence measured **4 rows written for create, 5 per
join (25 total), 5 for the leaves, 34 total**. A join writes the participant row, its identity index and
the room's auth-replay row; the secret hash is the only secret-derived value stored. Reads: 6–16 per join
as the roster grows. `x-relay-sql-rows` carries the counts on every non-101 response. The full run and
the raw numbers are in [linux-port/evidence/WP15.md](../../linux-port/evidence/WP15.md).

### Eviction and failure behaviour

A room can be evicted or its object replaced at any time. Clients treat the socket as disposable: on a
close or error they reconnect to the same `wss …/ws?secret=…&pid=<pid>` within 30 s and reuse the pid.
If the room was already wiped, the join gets `404 not_found`; the client falls back to the stored
`group_start` announcement and the group's "voice active" banner self-clears after 10 minutes without
room activity. The room's `alarm` wipes an empty room 30 s after the last socket closes, so a dead room
holds no rows. The provider's tracks are closed best-effort within a 3 s budget during teardown.
