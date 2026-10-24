# WP04-AC4 — upload → enrol → send media race (server side)

Status: **server side implemented and tested; AC4 is closed only for clients that send the new `media` field.
The core, app and desktop clients do NOT send it yet, so for them the race is still open.** Not committed, not
deployed. Branch `feat/linux-port`, base `a70a595`. Node 24 via `npx --yes --package=node@24`.

## The failure (reproduced first)

`test/media-race.test.ts` "reproduces the race" was written before any production code: link `dev(1)`, upload as
user 0 naming user 1, enrol `dev(2)` **after** the upload, send the message, and let `dev(2)` fetch.
Run RED (only the `media` field was ignored by the old server):

```
AssertionError: expected 404 to be 200
 ❯ test/media-race.test.ts:34   expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(200)
```

The inbox assertion before it passed: `dev(2)` received the ciphertext message and `GET /v1/media/<h>?device=dev(2)`
returned 404, because the blob's recipient set is the exact snapshot taken at PUT. After the change the same test is
GREEN and the reply carries `"media":[{"hash":…,"status":"attached"}]`.

## Chosen design: (a) authenticated media reference at send time

`POST /v1/send` takes an optional `"media": ["<sha256 hex>", …]` (≤ 8, distinct, lowercase 64-hex), in the signed
body but outside the envelopes.

1. **Check (RPC, before anything is queued).** Each digest → `MediaBlob.attachCheck(sender)`: exists, first uploader
   is the sender's identity, within the 8-day lifetime. Else `409 media_unavailable`, nothing queued. A missing,
   foreign and expired blob are indistinguishable.
2. **Queue + snapshot (one synchronous `transactionSync`, no await).** The messages are stored as before; for the
   recipients whose copy is newly `stored`, the devices active **now** (`activeDevices`: not revoked, primary or
   seen ≤ 72 h, legacy identity = `primary`) are written as one `media_grants` outbox row per digest (a JSON list
   of pairs). Messages and outbox rows commit together or not at all (a storage failure rolls back both and
   returns the usual retryable `503`). The set is therefore exactly the devices that will see this message.
3. **Apply (RPC, after commit).** `MediaBlob.grant(sender, pairs)` runs under the object's existing
   `blockConcurrencyWhile` guard (same guard as PUT/GET/DELETE/alarm). It re-checks `attachable`, then inserts the
   pairs whose pubkey is **already a recipient of the blob** (`ON CONFLICT DO NOTHING`, so confirmations are kept),
   then runs the normal `settle()` if a linked device was added (re-arms the dormancy alarm, waives dead pairs).
   The lifetime alarm is never moved later. The outbox row is deleted on `attached`/`gone`; on an RPC failure it
   stays, the reply says `pending`, and a Mailbox alarm (60 s) and the next media send retry. The grant is
   idempotent, so concurrent drains are harmless; `AUTOINCREMENT` ids prevent a stale drain deleting a newer row.

Why this does not widen access: pairs are only for the message's recipients (newly stored copies), only for devices
active at the queue instant, only for pubkeys already on the blob, only on a blob the sender uploaded. A revoked
device is not added (and if revoked afterwards, `GET` is refused and `settle` waives it). A device enrolled after
the queue instant is not in the set and does not receive that message (it starts at the head it was given).

The only new information the server learns is which uploaded digests accompany which send, which an observer of
the upload and the send could already correlate by timing; no new key material or plaintext.

## Alternatives rejected

- **(b) Grace window / re-snapshot on first GET by an enrolled-after device.** Rejected. At GET time the server
  has no evidence that the message was sent to that device: it cannot tell "enrolled before the message was queued"
  from "enrolled afterwards", nor a device that was never meant to see this file. A window long enough to cover
  late delivery also admits a device revoked-and-re-enrolled, or enrolled by a compromised admin key, to any
  blob of its identity uploaded in that window. I could not show it never widens access, which was the
  condition for considering it. It also makes GET a write path (rows written per read).
- **(c) Client re-upload before send.** Not rejected as unsafe: it already works (a repeat PUT re-snapshots the
  current devices, `adds recipient devices when the same file is sent again`). Rejected as the fix because it
  re-sends up to 25 MiB per part for each retry/race, does not close the gap between the re-PUT and the queue
  (a device enrolled in between is missed again), and costs mobile data. It remains the recovery when the reply
  says `gone` or `media_unavailable`.
- **Server-side inference of the blob from the envelope.** Impossible: the envelope is end-to-end encrypted.
- **Applying the grant inside the Mailbox transaction literally.** The blob is another Durable Object; no
  cross-object transaction exists. The outbox row gives the same atomic point (the snapshot is taken in the queueing
  transaction) with an at-least-once, idempotent apply.
- **Per-delivery `media`.** More precise, but larger parser and no gain given the "only recipients already on the
  blob" rule.

## Tests (`test/media-race.test.ts`, 21 tests)

| Requirement | Test |
|---|---|
| Race reproduced / fixed | reproduces the race … ; blob stays until the late device confirmed too |
| Lifetime and alarm invariant | …(lifetime and settle); re-arms the early dormancy alarm …; lifetime alarm ≤ original |
| Legacy: byte-identical reply, no table, no rows, no metrics header, `media: []` costs the same rows | legacy clients (no media field) … |
| Revoked never added; dormant not added | revoked before the send; dormant device; queues exactly the devices active at queue time (outbox content) |
| Revoke vs send ordering | revoke after the send; revoke racing the send (3 rounds, `Promise.all`) |
| Enrolled after queue gets nothing | a device enrolled AFTER the message was queued … |
| Not owned | refuses a blob uploaded by another identity; unknown and foreign answer identically |
| Cap 8, malformed rejected, nothing queued | bounds the digest list …(11 malformed shapes) |
| Expired blob | refuses a blob that has expired (409, nothing queued) |
| Blob vanishes after the check | reported `gone`, message stays queued, outbox empty |
| Group send, several recipients | group send … (recipient not on the blob gets nothing) |
| Duplicate / rejected not widened | a recipient whose copy was not newly stored … |
| Failure → pending → alarm | a grant that cannot reach its blob … |
| Race vs settle/confirm/alarm/repeat PUT | race-safe against the last confirmation; against a repeat PUT and the expiry alarm |
| Rows bounded | measures rows … |

Mutations (one source change each, restored byte-for-byte; killed): drop the "pubkey already on the blob" filter (1
test), drop the uploader check (2), grant for non-stored recipients (1), drop the active-device filter (1).

**Counts (parsed from the JSON reporter output `wp04-race-full.json`):** before 18 files / 238 tests / 238 passed;
after **19 files / 259 tests / 259 passed / 0 failed / 0 pending** (+21). `tsc --noEmit` and `tsc --noEmit -p tools`
both exit 0. The existing `device-auth-hardening` test that calls the private `sendStored(…4 args)` is unchanged
and passes (the fifth parameter defaults to `[]`).

## Row measurements (`EXPOSE_METRICS=1`, local workerd, `WP04-MEDIA-RACE-ROWS`)

| Case | Mailbox rows | Blob rows (`x-relay-media-rows`) |
|---|---|---|
| send without `media`, 1 recipient | read 13 / written 6 | – |
| 1 blob, 1 recipient (3 devices, 1 late) | read 17 / written 9 (+3: outbox insert, `sqlite_sequence`, delete) | read 21 / written 1 |
| 8 blobs, 4 recipients, 3 late devices each (worst under test caps) | read 175 / written 45 | read 560 / written 96 (8 × 12 pair inserts) |

Bounds: ≤ 8 outbox rows per send; per blob ≤ (16 recipients × 4 devices) = 64 pair inserts by the protocol limits
(≤ 16 in these tests because `MAX_MEDIA_RECIPIENTS=4`; the test asserts ≤ 8 × 16). Not counted: the `attachCheck`
reads on the blobs (one indexed `info` read each) and the `deleteAll` of an erased blob, as elsewhere in WP04.
Legacy sends add 0 rows and 0 RPCs. These are local counts, not billing.

## What a client must now do (honest statement)

- Send the digest of **every blob the message refers to** in `media` on the same `POST /v1/send` that carries the
  envelopes (a `TYPE:MEDIA` is one digest; `TYPE:FILE` is `PARTS` digests, ≤ 4), for the self copy as well.
- Be the uploader of those blobs (same identity) and have uploaded them before the send; handle `409
  media_unavailable` (re-PUT, then resend) and `status: "gone"` (re-PUT and resend), and treat `pending` as success.
- Keep naming all recipients (and its own pubkey for its other devices) in `x-relay-recipients` at PUT: the
  server only adds devices for pubkeys that are already recipients.
- **The core, app and desktop clients do NOT do this yet.** Until they do, AC4 is not closed end to end, and the
  race is exactly as before for them. Nothing in those trees was touched by this change.

## Residual risk

- Clients that do not send `media` (all today) keep the race.
- A blob erased (everyone confirmed) or expired between the check and the apply: reported `gone`; late devices get 404
  until the sender re-uploads. A tiny window, visible to the client.
- `pending` until the retry: a late device gets 404 for up to ~1 minute plus alarm latency if the blob object is
  unreachable; the outbox row is dropped after the blob lifetime (8 days) at the latest.
- Device identity is still `device=` plus per-device keys (G1/WP05); this change does not alter that boundary.
- Anyone holding the bytes can still re-PUT (existing "repeat upload adds recipients"), which re-snapshots at its
  own PUT; ownership is only enforced for `media` references.
- A new device that asks the inbox with `after=0` (instead of its head) can read older ciphertext (documented WP04
  limitation); it still gets no blob access.
- Bogus digests (≤ 8 per request, member-only) instantiate empty blob objects, as `GET /v1/media/<any hash>` already does.

## NOT RUN

- Deploy to `relay-mailbox-test`, the 48 h pilot, any Cloudflare-billed row or request count (AC6).
- Any real phone, desktop client, Mac, FCM; the Kotlin/core/app/desktop clients sending `media` (not implemented).
- Load, long-run or multi-process concurrency beyond the in-process `Promise.all` races above; real
  hibernation/eviction between queue and apply (the outbox is designed for it, only the injected-failure + alarm
  path is tested).
- Independent review. `wrangler types` regeneration (no `Env` change was needed).
