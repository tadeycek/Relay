# WP05-AC2 evidence — sent-copy sync, desktop side

Status: **desktop side done and tested against a local Worker; no phone involved.** 2026-10-24, branch
`feat/linux-port`, base `a70a595`. Nothing committed, pushed or deployed. No `core/`, `app/`,
`server/cloudflare/**`, `linux-port/tasks.md` or `linux-port/wps/**` file was touched by this slice.

## Files

| file | what |
|---|---|
| `desktop/.../SentSync.kt` (new) | seams (`SyncHttp`, `SyncStore`), `SyncWire`, `SentSyncPublisher`, `SentSyncApplier`, `DesktopSyncService` loop |
| `desktop/.../DesktopSyncHttp.kt` (new) | OkHttp implementation of `POST /v1/sync`, `GET /v1/sync`, `POST /v1/sync/ack`, signed per request, `?device=` |
| `desktop/.../DesktopMessageStore.kt` | implements `SyncStore`: `sync_outbox` table, cursor in `meta`, atomic `commitSync`, `enqueueSent` |
| `desktop/.../DesktopEngine.kt` | optional publisher/applier; `sendText` records the copy after a successful contact send; sync loop starts with `start()` |
| `desktop/src/test/.../SentSyncTest.kt` (new) | 26 unit tests with a fake server that enforces the Worker's part rules |
| `desktop/src/test/.../DesktopLocalWorkerSentSyncE2ETest.kt` (new) | local-Worker end-to-end, both directions + chunked |

`:core` was only called (`SyncCodec`, `SyncReassembler`, `SyncBody`, `PayloadCodec`); no codec bug found.

## Flow as coded

**Send (desktop → phone).**
1. `DesktopEngine.sendText` seals for the contact and sends. **Only on `SendResult.Sent`** does it continue; a failed send
   returns the failure and writes nothing (no row, no outbox entry, no POST).
2. `SentSyncPublisher.recordSent` builds the SENT body: `O` = this device id (32 hex), `TO` = contact pubkey (or
   `group:OWNER:ID` for a group target), `P` = base64 of `RelayPayload{id = the id that went to the contact, ts, body}`.
   The body is **sealed to our own HPKE key** (`TYPE:ENC|CT:…`, same shape the phone's `selfSealedPayload` produces), so the sync
   server holds no plaintext. `SyncCodec.chunk` splits it when the wire body exceeds 12,288 bytes.
3. The local sent row and the wire bodies are written in **one SQLite transaction** (`enqueueSent`). The copy is therefore never lost
   between "row stored" and "copy queued", and a duplicate payload id queues nothing.
4. `flush()` posts each queued wire body as one server envelope (`part 0 of 1`, `rev 1`, `expires = now+24 h`, id = first 32 hex of
   SHA-256 of the wire bytes, so a retry is byte-identical → `duplicate:true`). A transient failure stops the flush and keeps the entry
   (attempt counter bumped); a permanent rejection drops that entry; entries older than 24 h (the server TTL) are dropped. The user-visible
   `sendText` returns before any of this touches the network: `kick()` only wakes the background loop.

**Receive.** `SentSyncApplier.runOnce`, driven by `DesktopSyncService` every 15 s, right after a send, and at start:
1. `GET /v1/sync?after=<local cursor>&limit=8&device=<id>`, paging while `more`.
2. Per event, strictly in cursor order: base64 → size ≤ 12 KiB → `total` and SHA-256 `hash` must match → `SyncCodec.decode` → if `PART`,
   `SyncReassembler.offer` (any order, duplicates tolerated) → on completion decode again.
3. `O == own device id` → ignored (counted). Not a `SENT` → ignored (counted, see limitation 3). `SENT`: decode `P`; if the body is `TYPE:ENC`,
   open it with our key (not openable → counted `unreadable`, skipped). Insert as `outgoing = true`, `sender = own pubkey`, **same payload id**,
   in conversation `TO`.
4. The row and the **local cursor are committed in one transaction** (`commitSync`; the cursor never moves back). Skipped events let the
   cursor pass at the end of the page, only after every earlier event is resolved.
5. Only after a durable commit is `POST /v1/sync/ack` sent, from the committed local cursor (never from what was merely fetched). A failed
   ack changes nothing; the next cycle acks again.

**Idempotence / merge.** `messages.id` is the primary key; the insert is `INSERT OR IGNORE`. A SENT whose payload id already exists
(received or sent) returns `MERGED`: the existing row stays untouched, no second row.

**Unknown contact.** The conversation row is created as a **placeholder** (title = the pubkey, no `contacts` row). The report counts it
(`unknownContact`). A real contact arrives later through the link bundle / `SYNC CONTACT`. Nothing is parked.

**Cursor persistence.** `meta` row `sync.cursor` in the SQLCipher database. The `sync_outbox` table is created with `IF NOT EXISTS`,
**without** bumping `PRAGMA user_version` (the existing test pins schema 2), so older databases open unchanged.

## Failure matrix

| situation | behaviour | pinned by |
|---|---|---|
| contact send fails | nothing stored, nothing published | `contact send failure publishes nothing…` |
| sync server down at send time | send returns `Sent`; row + copy queued | `engine send succeeds and queues a copy…` |
| POST transient failure (net, 5xx, 429, 401/403, retryable 409) | entry kept, retried; survives restart | `a failed publish is retried after restart…` |
| POST permanent (400, 413, non-retryable 409) | entry dropped, counted | code path, `Rejected` |
| reply lost after server stored it | retry is `duplicate:true`, one event | `a retry after a lost reply…` |
| copy can't be built / sealed | row kept, no copy, counted, **never a plaintext fallback** | `an unencodable copy…`, `a sealer that fails…` |
| list failure | nothing moves, no ack | `a list failure moves nothing` |
| commit failure | cursor unmoved, no ack, event applied next cycle | `a commit failure leaves the cursor…` |
| malformed / bad hash / bad base64 / unknown version / oversize | skipped, counted; later good events still apply; cursor passes | `malformed, unknown-version and oversize…` |
| incomplete multi-part event | cursor held just before its first part; re-fetched until complete | `an incomplete multi-part event holds…` |
| own-origin event (also chunked) | ignored, releases the cursor | `own-origin events…`, `a chunked own-origin event…` |
| duplicate / out-of-order parts | one row | `an oversize body is chunked…` |
| `TYPE:ENC` body we cannot open | counted `unreadable`, skipped | `an encrypted body that is not for us…` |
| apply twice / lost cursor | one row (`MERGED`) | `applying twice yields one row` |

## Counts (parsed from `desktop/build/test-results/test/TEST-*.xml`)

Command (final): `./gradlew --no-daemon --console=plain --rerun-tasks :desktop:compileKotlin :desktop:test`, `RELAY_E2E` unset.

| suite | tests | fail | err | skipped |
|---|---:|---:|---:|---:|
| `SentSyncTest` (new) | 26 | 0 | 0 | 0 |
| `DesktopLocalWorkerSentSyncE2ETest` (new, gated) | 1 | 0 | 0 | 1 |
| existing: Engine 2, KeyMaterial 6, LinkFlow 17, MailboxTransport 2, MessageStore 6 | 33 | 0 | 0 | 0 |
| existing gated E2E (`LocalWorkerE2E`, `LocalWorkerLinkE2E`) | 2 | 0 | 0 | 2 |
| **all** | **62** | **0** | **0** | **3** |

The existing 35 tests are unchanged and green (33 + 2 gated-skipped). Compile: BUILD SUCCESSFUL.

E2E run (gated, `RELAY_E2E=1`, `RELAY_E2E_PERSIST=$PWD/.local/wp05-sent-desktop-state`, `RELAY_WORKER_DIR`/`XDG_*` unset; state dir deleted first,
Worker stopped in `finally`, port 8795 confirmed free afterwards): `SentSyncTest 26/0/0/0` + `DesktopLocalWorkerSentSyncE2ETest 1/0/0/0`,
`BUILD SUCCESSFUL in 25 s`.

## Measured (real local Worker, one run; wrangler dev --local, same host)

| measurement | value |
|---|---|
| desktop send → peer received | 344 ms |
| desktop send → JVM phone-primary applied the SENT through `/v1/sync` (same payload id as the peer received) | 365 ms |
| phone publish → desktop applied (1 s loop interval) | 815 ms |
| 11,000-char body, sealed: wire bodies / bytes | 3 parts / 11,083 + 11,083 + 4,870 B (all ≤ 12,288) |
| chunked publish → desktop reassembled | 1,025 ms |
| server events at the end | 5 (1 desktop SENT, 1 phone SENT, 3 parts) |
| final cursors | desktop 5, phone 5 (both released past the phone's own chunked copy) |
| server held plaintext | none: the check scans every event body for all three plaintexts |

Asserted in the E2E: the desktop row id equals the id the peer received; exactly one row per id on each side; the desktop never re-publishes
the phone's copies (outbox 0 after the dust settles); an applied phone copy is `outgoing`.

## What the phone side must match

1. **Sync body**: `TYPE:SYNC|SENT|V:1|SID|R:1|O:<device>|TO|P` through `SyncCodec`; bodies > 12,288 B chunked with `SyncCodec.chunk`
   and **each wire body posted as its own server event** (`part 0`, `parts 1`). The desktop reads one wire body per event.
2. **`P` body is sealed to the identity's own HPKE key** (`TYPE:ENC|CT:…`), both ways. The desktop opens with its (imported) keyset. The Android
   `selfSealedPayload` already does this.
3. **`O`**: the desktop's id is its 32-hex device id; the phone must use `primary`. The desktop ignores any event whose `O` equals its own id and
   applies every other origin.
4. **Server ids**: free for readers (they read `data`, not `id`). The desktop uses `sha256(wire)[0:32]`; the phone's `eventId` scheme is also fine.
5. **Auth**: after migration the phone signs `device=primary` with the **admin key** (that is what the E2E does), not the shared identity key.
6. **Ack after commit**, cursor kept locally; list with an explicit `after`.
7. Size: `PayloadCodec` caps a body at 16 KiB, and sealing adds ~⅓. The largest text whose sealed copy still fits is ≈ 11–12 KB; a contact send
   of that size is bounded the same way.

## Contract ambiguities hit (not papered over)

1. **Plaintext on the server.** `PROTOCOL.md` says sync bodies are "ordinary end-to-end messages" yet `POST /v1/sync` stores the bytes the
   client sends, readable by the Worker for up to 24 h. Nothing in the spec says to seal. The desktop seals `P`'s body to its own key (matching the
   phone code that already exists); the `TO`, `O`, ids and timestamps stay visible to the server. Decide whether to seal the whole wire body too.
2. **"P exactly as sent".** The payload that goes to a contact has a body sealed *for the contact* (`TYPE:ENC` to the recipient key), which the
   sender's other device cannot open. So `P` keeps the same `id` and `ts` but carries the body sealed to *ourselves*, not the contact copy
   byte-for-byte. Dedupe/receipts only need the id, so they line up.
3. **Other SYNC kinds.** There is one cursor per device, so ignoring `READ`/`KEYS`/`CONTACT`/… (counted `otherKind`) while acking past them drops
   them for good. Until the desktop applies those kinds, an event of another kind is lost to it.
4. **Unknown-version / malformed events are skipped permanently** (the cursor passes them), otherwise one bad event would stall the device for 24 h.
   "Never past an unprocessed *good* event" holds; an unreadable-by-definition event is not "good".
5. **First sync backfills up to 24 h.** A newly linked device's sync cursor is 0 and the server keeps events 24 h, so the desktop fetches the SENT
   copies published before it was linked (whose contact replies it never received). The link flow could start the cursor at the sync head, but the
   server has no "head" call for sync.
6. **Chunking has two layers.** `SyncCodec` PART bodies (8 KiB each) are carried one per server event; the server's own 8-part envelope is unused.
7. `rev`: the server needs ≥ 1; the codec `R` is 1 for SENT, so they match.

## NOT RUN

- A real phone, the Android `:app` sync code (it was being written concurrently under `app/src/main/java/com/relay/app/sync/`; not compiled or run
  here), a phone↔desktop test with the real Android seal/open, and a real HPKE seal on Android (the desktop test uses Tink on the JVM for both ends).
- A live (deployed) Worker, the websocket (the transport has no sync hint frame, so the desktop polls), an Android emulator, a real contact device.
- The desktop **UI** with a human: the loop is wired through `DesktopEngine.start()` (no new screens) and covered by the E2E's engine, but no window was opened.
- Group SENT through the real Worker (group targets are covered at unit level only); media/file SENT is covered with reference strings, not a real upload.
- `READ`, `KEYS`, `CONTACT`, `DELETE` application (out of scope here).
- Behaviour after the 3-day dormancy gap, server `sync_limit` (64 events / 256 KiB) under load, and concurrent multi-desktop sync.
- Cursor reset on a wiped server (a stored local cursor above the server head would hide new events until the head catches up).
