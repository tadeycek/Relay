# 05. Protocol changes (contract)

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

This is the contract for WP04, WP05, WP09, WP11 and WP15. `server/cloudflare/PROTOCOL.md` gains a v1.1 section
once each change lands; this file is the plan. The rule throughout is that a client which doesn't send a `device`
parameter behaves exactly like it does today (LP-D14).

## 1. Mailbox: per-device cursors (WP04)

### Data

```sql
CREATE TABLE devices (
  pubkey     TEXT NOT NULL,
  device     TEXT NOT NULL,          -- 'primary' or 32 hex
  label      TEXT NOT NULL DEFAULT '',
  acked      INTEGER NOT NULL,       -- highest seq this device durably stored
  last_seen  INTEGER NOT NULL,       -- ms; updated at most once per hour per device (row-write budget)
  added_at   INTEGER NOT NULL,
  PRIMARY KEY (pubkey, device)
) WITHOUT ROWID;
```

**The `primary` row.** A pubkey with no rows behaves as a single implicit `primary` device. The first time anyone
calls `POST /v1/devices` for a pubkey, the server creates the `primary` row with `acked` set to that pubkey's
current acknowledged position.

### Semantics

- **`device` parameter.** `GET /v1/inbox`, `POST /v1/ack`, `GET /v1/ws` and `/v1/media/*` accept `?device=<id>`.
  - Missing means `primary`.
  - An unknown device on a pubkey that has devices gets `403 unknown_device`, which is not retryable.
  - Unknown query parameters are still refused, so `device` is added to the allowlist.
- **Ack.** `ack(through)` sets `devices.acked = max(acked, through)` for the caller's device. The server then
  deletes deliveries with `seq ≤ min(acked over active devices)`.
- **Active device.** A device counts as active while `last_seen ≥ now − 72 h`, or if it is the `primary`, which is
  always active.
- **Dormant devices.** A device stops holding the queue after 72 h without contact. Without this, a desktop left
  off would fill `maxPendingBytesPerRecipient` (32 MiB) and senders would get `recipient_full`.
- **Gap flag.** When a dormant device returns, it may have missed deletions. The `ready` frame and the inbox
  response then carry `"gap": true` and the oldest seq still held. The client shows "This computer was offline for
  more than 3 days; some messages are only on your phone."
- **Expiry.** The 8-day expiry applies unchanged, to every device.
- **Inbox paging.** `GET /v1/inbox?after=` is unchanged. The cursor is client-held as today, and `after` is
  independent of `acked`.
- **Live pushes** go to every socket of the pubkey, which is unchanged. Each socket's attachment stores its
  `device`.
- **Socket cap.** The cap of 4 sockets per identity stays. Phone, desktop and laptop take 3, leaving 1 for
  reconnect overlap. `MAX_SOCKETS_PER_IDENTITY` is a variable if that proves tight.

### Device management (primary only)

The caller's `device` must be `primary`, otherwise the request gets `403 not_primary`.

| Request | Effect |
|---|---|
| `POST /v1/devices {"v":1,"device":"<32hex>","label":"…"}` | Registers a device, with a maximum of 3 besides the primary. Its `acked` starts at the queue's current **head seq**, so it never receives history. Reply: `{"v":1,"head":<seq>}`. |
| `GET /v1/devices` | Lists `{device,label,added_at,last_seen,active}`. |
| `DELETE /v1/devices/<id>` | Drops the cursor and closes that device's sockets with `4009 unlinked`. |

**Rows.** An ack writes 1 row for the cursor update plus the deletions, which already happen today. A device
registration writes 1–2 rows. The `last_seen` refresh writes at most 24 rows per device per day.

## 2. Media: per-device confirmation (WP04) and files (WP09)

**Confirmation.** The `recipients` table in `MediaBlob` gains a `device` column. `DELETE /v1/media/<h>?device=`
marks `(pubkey, device)` as confirmed. The object is erased only when every recipient pubkey has been confirmed by
all of its devices that were **active when the object was uploaded**. The `PUT` snapshots the device list per
recipient through one read from the Mailbox DO. The 8-day expiry is unchanged.

**Size.** `maxMediaBytes` stays at 25 MiB + 64 per object. Files over 25 MiB are split across up to 4 objects
(LP-D07).

**`TYPE:FILE` body** (it travels inside `TYPE:ENC` like every other body, and must be ≤16 KiB):

```
TYPE:FILE|V:1|NAME64:<base64 utf-8 file name>|MIME:<type/subtype>|SIZE:<plain bytes>|KEY:<b64 32B>
         |PARTS:<n>|P1:<sha256 hex>,<blob bytes>|P2:…|…|URLBASE:<https://…/v1/media/>[|CAP64:<b64 caption/reply>]
```

**Validation rules** (`:core FileBody`, unit-tested):
- **Name**: `NAME64` decodes to ≤255 UTF-8 bytes. The decoded name has path separators, control and format
  characters stripped, and is never trusted as a path. Leading dots are made visible.
- **MIME**: `type/subtype` over `[a-z0-9.+-]`, at most 100 characters. Anything else becomes
  `application/octet-stream`. MIME is only a hint: the receiver never auto-opens and never executes, and desktop
  "Open" goes through the OS handler with a warning for executables (`.desktop`, `.sh`, `.AppImage`, `.app`,
  `.command`, ELF/Mach-O magic).
- **Size and parts**: `1 ≤ SIZE ≤ 100 MiB` and `1 ≤ PARTS ≤ 4`. Every part except the last decrypts to exactly
  25 MiB. The sum of the plaintext sizes must equal `SIZE`.
- **URLBASE** must be this build's mailbox media URL (as `MailboxMedia.isOurs` checks today), and nothing else is
  fetched.

**Older builds.** 1.15 phones don't recognise `TYPE:FILE`, and today they render unknown `TYPE:` bodies as text.
WP09 adds a generic unknown-type renderer ("Unsupported message, update Relay") to the phone **before** files are
enabled. The rollout order is in 08.

## 3. Sync bodies (WP05)

These are only ever sent to one's own pubkey. Receivers drop a `TYPE:SYNC` that comes from any other sender, and
log a security event when that happens.

| Body | Sent by | Meaning |
|---|---|---|
| `TYPE:SYNC\|SENT\|O:<origin device>\|TO:<contact pubkey or group:<owner>:<id>>\|P:<b64 RelayPayload JSON>` | any device | A message we sent. The receiver inserts it as outgoing, with the same payload id, so dedupe and receipts line up. |
| `TYPE:SYNC\|READ\|O:…\|C:<conversation>\|T:<ts>` | any device | Read up to a timestamp. |
| `TYPE:SYNC\|KEYS\|O:primary\|K:<b64 sealed Tink keysets current+previous+expiry>` | primary | Key rotation. Must be `stored` before the rotation broadcast. |
| `TYPE:SYNC\|CONTACT\|O:primary\|J:<b64 JSON contact>` / `…\|CONTACT_DEL\|…` | primary | Contact added, changed, verified or blocked. |
| `TYPE:SYNC\|GROUP\|O:primary\|J:<b64 JSON roster+meta>` | primary | Our view of a group roster changed. Rosters other people send us already arrive at every device. |
| `TYPE:SYNC\|SETTINGS\|O:primary\|J:…` | primary | Typing on/off, notification preview, retention. |
| `TYPE:SYNC\|DELETE\|O:…\|C:…\|ID:<payload id>` | any device | A local delete ("delete for me") is mirrored to our other devices. |

A body that exceeds 16 KiB is split by the existing chunking rule. If no such rule exists, WP05 adds a `PART i/n`
envelope. `SENT` copies of media and files carry the same `TYPE:MEDIA` or `TYPE:FILE` reference. The uploader adds
**its own pubkey** to `x-relay-recipients`, so its other devices can fetch the blob.

## 4. Link slot (WP05)

| Request | Auth | Effect |
|---|---|---|
| `PUT /v1/link/<sha256 hex of code>` body = sealed bundle ≤128 KiB | NIP-98, primary | Stores the bundle for 10 minutes. A member may hold at most one slot. |
| `GET /v1/link/<sha256 hex of code>?proof=<code hex>` | none | Returns the bundle and deletes it, if `sha256(proof)` matches and the slot hasn't expired. Every failure returns `404`. Rate limit: 10 a minute per IP (`CF-Connecting-IP`), kept in memory, best effort. |

This is the only unauthenticated data endpoint. The edge router lets just this path through without NIP-98.

## 5. Calls (WP11, WP15)

| Request | Auth | Effect |
|---|---|---|
| `GET /v1/call/ice` | NIP-98 member | Returns `{"v":1,"iceServers":[…],"expiresAt":…}` from Cloudflare TURN `generate-ice-servers`, with `ttl` = 3 h. The port-53 URL is removed. Cached per caller for 10 min. Secrets: `TURN_KEY_ID`, `TURN_API_TOKEN`. |
| `POST /v1/send` delivery field `"urgent": true` | NIP-98 | Used only for `CALL_INVITE`. It bypasses the 20 s wake coalescing, under a separate limit of 1 urgent wake per recipient every 3 s. FCM data is `{"type":"wake","urgent":"1"}`, sent as a high-priority message with no collapse key. |
| `POST /v1/call/rooms` `{"v":1,"room":"<32hex>","secretHash":"<sha256 hex>"}` | NIP-98 member | Creates a `CallRoom` DO (id from name `room`). Reply `{"v":1,"created":true}`. Idempotent for the same hash. |
| `GET /v1/call/rooms/<room>/ws?secret=<hex>&device=` | NIP-98 member + secret | Room control socket (07 §3). |
| `POST /v1/call/rooms/<room>/sfu/<op>` | NIP-98 member + room socket open | Proxies `sessions/new`, `tracks/new`, `renegotiate`, `tracks/close` to `rtc.live.cloudflare.com/v1/apps/<SFU_APP_ID>/…` with `SFU_APP_SECRET`. Each participant may only touch its own session ids (the DO tracks ownership). |

`MAX_SIGNAL_BYTES` is raised from 16 KiB to 32 KiB by environment variable, only if WP02 measures call frames over
14 KiB after deflate and trickle ICE.

## 6. Call bodies (`TYPE:CALL`)

These are carried as `signal` frames (never stored), except `INVITE`, which is also sent as a stored delivery so a
sleeping callee is woken. Fields are JSON, base64-encoded after `J:`:

```
TYPE:CALL|J:<b64 {"v":1,"op":"…","call":"<32hex>","ts":<ms>, …}>
```

| op | Fields | Notes |
|---|---|---|
| `invite` | `media:"audio"`, `from_device`, `expires` (ts + 45 s) | Stored and urgent. Also sent as a signal for an instant ring when the callee is online. |
| `ringing` | `device` | The callee's device is ringing. |
| `offer` / `answer` | `sdp` (deflate + base64), `device` | Trickle ICE. The offer goes only to the device that accepted. |
| `ice` | `cands:[…]`, `device` | Candidates are batched every 250 ms, capped at 20 per frame. |
| `accept` | `device` | First wins. The caller replies `offer` to that device and sends `answered_elsewhere` to the callee's identity. |
| `answered_elsewhere` | `device` | Sent to self, so the callee's other devices stop ringing. |
| `decline` / `busy` / `cancel` / `end` / `timeout` | `reason?` | Terminal. |
| `group_start` / `group_end` | `group`, `room`, `secret`, `[e2eeKey]` | Group call announcement over the **stored** group fan-out, so members joining later see "voice active". |
| `mute` | `muted` | Optional UI hint in 1:1 calls. |

**Validation.** The receiver drops call bodies whose `ts` is more than 60 s old (ring) or more than 10 min old
(group announcements). It also drops bodies from senders who aren't verified contacts or group members, and ones
whose `call` id is unknown (except `invite` and `group_start`).
