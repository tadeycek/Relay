# WP05 part B — server slice: link slot and sync channel

Branch `feat/linux-port`, HEAD `715da4b` at the time of writing. Scope: `server/cloudflare/src/**`,
`server/cloudflare/test/**`, `server/cloudflare/PROTOCOL.md`, `server/cloudflare/README.md` and this file.
Nothing was committed, deployed, or run against a remote provider. No app/, core/, desktop/ or other
linux-port file was modified; the untracked `core/.../linking/` files in `git status` belong to another
workstream, not this one.

## Commands run

All from `/home/tadej/repos/Relay/server/cloudflare`, with the mandatory Node 24 wrapper:

```sh
# full suite, JSON report
npx --yes --package=node@24 node node_modules/vitest/vitest.mjs run \
  --reporter=json --outputFile=/tmp/final.json

# the four new/changed files only
npx --yes --package=node@24 node node_modules/vitest/vitest.mjs run \
  test/link-slot.test.ts test/sync-endpoint.test.ts \
  test/legacy-link-sync-compat.test.ts test/link-sync-budget.test.ts --reporter=dot

# typecheck (both projects)
npx --yes --package=node@24 node node_modules/typescript/bin/tsc --noEmit            # 0
npx --yes --package=node@24 node node_modules/typescript/bin/tsc --noEmit -p tools   # 0
```

Counts parsed from the JSON report (`testResults`, not the log):

| Report | Test files | Vitest suites (describe blocks) | Tests | Passed | Failed |
|---|---|---|---|---|---|
| Before (`/tmp/baseline.json`, HEAD 715da4b) | 14 | 49 | 208 | 208 | 0 |
| After (`/tmp/final.json`) | 18 | 61 | 238 | 238 | 0 |

New tests by file: `link-slot.test.ts` 14, `sync-endpoint.test.ts` 12,
`legacy-link-sync-compat.test.ts` 3, `link-sync-budget.test.ts` 1 — 30 in total. `limits.test.ts` gained
assertions inside an existing test (still 15), so the suite count grows only by the new files and describes.

Every behaviour below had a failing test before the implementation; the four files were run against the
unmodified source first and failed 29/30 (the socket/media compatibility check passed because those routes
were not touched), then went green.

## What is implemented

### 1. `PUT /v1/link/<slot>` — phone publishes the bundle

- `slot = sha256(lowercase hex of the 32-byte one-time code as UTF-8)`, 64 lowercase hex. The server stores
  only the slot; the code never reaches it.
- NIP-98 auth through the existing `authorize()`/`verifyRequestAuth` path, so signature, exact URL, method
  and body digest are checked, and the event id is recorded in `auth_seen` (`401 auth_replayed` on reuse).
- Allowed signer: the admin key after migration, or the legacy shared identity key before migration. A
  linked device's own key is refused `403 not_primary`; a non-member `403 not_allowed`.
- Body is the raw sealed bundle, 1..131072 bytes. Over the cap: `413 envelope_too_large` (checked at the
  edge with one byte of slack so the precise code comes from the mailbox, and again in the object).
- One live slot per identity, enforced by a `UNIQUE INDEX link_slots_pubkey` and a checked insert.
  **Rule chosen: a second PUT while a slot is live is refused with `409 link_busy`; it never replaces.**
  Expired slots are deleted before the check, so an old link cannot block a new one. A slot value already
  present for any identity is also `409 link_busy`.
- TTL 10 minutes (`LINK_TTL_MS`). Reply `{"v":1,"slot":…,"size":…,"expiresAt":…}`.

### 2. `GET` / `DELETE /v1/link/<slot>` — desktop retrieves, then completes

- `GET` is the only unauthenticated data path: the edge router lets `/v1/link/<64 hex>` through without an
  `Authorization` header (any method; the mailbox answers `405` for the rest).
- Proof is the **`x-relay-link-proof` header**, never a query parameter. A query string on this route is
  `400 bad_request`, so there is no leaking fallback. The server hashes the proof and compares digests
  without an early exit.
- Missing proof: `401 auth_missing`. Proof that is not 64 lowercase hex: `401 auth_invalid`. Wrong proof,
  unknown slot, expired slot: `404 not_found` with an identical body — no existence oracle.
- **Lease, not delete-on-read.** `GET` stamps `lease_until = now + 60 s` (one row write), returns the
  bytes, `x-relay-link-lease` and `x-relay-link-expires`. A repeated `GET` returns the bundle again; an
  expired lease changes nothing about availability. Only `DELETE` (same proof header) erases the row and
  answers `{"v":1,"consumed":true}`. After completion every read and completion is `404`.
- Guessing is bounded in memory per `slot` **and** per `CF-Connecting-IP` (`unknown` when absent), 10
  attempts a minute each; over the limit is `429 rate_limited`. Nonexistent slots are counted the same way,
  so the 429 is not an oracle. Buckets are pruned and the map is capped at 1024 keys.
- Past the 10-minute TTL both `GET` and `DELETE` are `404`; a read of an expired slot deletes its row.

### 3. Storage and response bounds

`link_slots(slot TEXT PRIMARY KEY, pubkey, bundle BLOB, size, created_at, expires_at, lease_until)`
`WITHOUT ROWID` plus a unique index on `pubkey`. One row per identity at most, so the table is bounded by
the member count. Tables are created by `migrateLink()` under the meta key `link_schema = 1`; the numbered
`schema` stays 7 (the existing migration test still passes).

`sync_parts` is bounded by 4 assemblies × 8 parts per identity; `sync_events` by 64 events / 256 KiB per
identity; `sync_cursors` is one row per `(identity, device)`. Every request body and response is capped;
the largest link response is the 128 KiB bundle, the largest sync page is 64 KiB / 8 events.

### 4. `POST /v1/sync` and `GET /v1/sync…`

The endpoint shape is an addition: 05 §3 defines only the `TYPE:SYNC` body format and does not specify a
transport. `TYPE:SYNC` messages are unchanged and still travel through `/v1/send`. This channel is the
bounded transport G3 demands for SENT copies and KEYS rotations:

```json
{"v":1,"id":"<8-64 hex>","rev":1,"part":0,"parts":2,"total":8000,
 "hash":"<sha256 of assembled body>","expires":<ms>,"data":"<base64 part>"}
```

- Auth: the ordinary `authorize()` path, so legacy shared key before migration, admin key, or an enrolled
  device key — always scoped to the caller's own identity.
- Assembly: an event exists only when every index `0..parts-1` is present, the summed length equals
  `total`, and `sha256(assembled)` equals `hash`. Otherwise the assembly is dropped (`400 bad_request`):
  no event, no leftover part. Nothing partial is ever served.
- Bounds: 12 KiB decoded body (base64 ≤ 16 KiB), ≤ 8 parts, ≤ 4 unfinished assemblies per identity
  (`409 sync_limit`, retryable), ≤ 64 events / 256 KiB per identity (`409 sync_limit`), retention clamped
  to 24 h.
- Duplicates: same `(id, part)` with the same bytes → `200` with `"duplicate":true` and no write. Same id
  with a different `rev`, or different bytes for a part, or disagreement on `parts`/`total`/`hash` →
  `409 sync_conflict`, original assembly untouched. A completed event re-sent with the same rev is
  idempotent (`duplicate`, existing cursor).
- Fetch: `GET /v1/sync?after=<cursor>&limit=<n>`, oldest first, bounded by count and bytes; without
  `after` the caller's stored cursor is used. `GET /v1/sync/<id>` returns one event or `404`.
  `POST /v1/sync/ack {"v":1,"through":n}` advances only that device's cursor, never backwards and never
  past the newest event. Malformed or unknown-`v` input is `400` and moves no cursor.

### 5. Legacy compatibility

A client that sends no `device` parameter and never calls the new routes sees the same replies:
`test/legacy-link-sync-compat.test.ts` compares the exact JSON text of send, inbox and ack (field order
included) and the exact key set of the socket `ready` frame and media replies. A legacy identity writes
zero rows in `link_slots`, `sync_parts`, `sync_events`, `sync_cursors` and `devices`. The only change on
those paths is the one indexed `devices` read per request introduced by WP05-A, unchanged here.

### 6. Docs

`PROTOCOL.md` gained "Link slot (v1.3, WP05 part B)", "Sync channel (v1.3, WP05 part B)", an error-code
table, a compatibility note and nine limit rows. `README.md` gained the operator summary, the measured row
costs and an explicit "Not deployed" line.

## Measured rows (`EXPOSE_METRICS=1`, `test/link-sync-budget.test.ts`)

Reachable from the test pool: the budget test reads `x-relay-sql-rows` on the real responses, so the
numbers are measured, not estimated. Printed line (one 2 KiB bundle, one 8 KiB body in two parts):

```
WP05-ROWS {"link_put":{"read":1,"written":3},"link_get":{"read":2,"written":1},
"link_get_retry":{"read":2,"written":1},"link_complete":{"read":2,"written":1},
"sync_part_first":{"read":4,"written":2},"sync_part_completing":{"read":9,"written":8},
"sync_fetch":{"read":3,"written":1},"sync_fetch_one":{"read":2,"written":1},
"sync_ack":{"read":2,"written":2}}
```

| Operation | Rows read | Rows written |
|---|---|---|
| link PUT (includes the auth replay record and the unique index) | 1 | 3 |
| link GET (fresh lease) | 2 | 1 |
| link GET retry | 2 | 1 |
| link completion (DELETE) | 2 | 1 |
| sync first part | 4 | 2 |
| sync completing part (assembles, inserts, deletes parts) | 9 | 8 |
| sync list | 3 | 1 |
| sync single event | 2 | 1 |
| sync ack | 2 | 2 |

A link therefore costs about 4 row writes from phone to consumed bundle, and a two-part sync event about
10 writes. These are per-operation, one-off costs during linking and rotation, not per message.

## Per-requirement status

| Requirement | Status | Evidence |
|---|---|---|
| R1 `PUT /v1/link/<slot>`, admin key or pre-migration shared key, NIP-98 + replay, ≤128 KiB, one live slot, 10 min TTL, keyed by sha256(code), second PUT refused | **Done** | `link-slot.test.ts` (happy path, oversize, second slot, admin/legacy, replay, non-member, device key) |
| R2 `GET /v1/link/<slot>` unauthenticated, proof **header**, lease + explicit completion, wrong/missing proof, bounded guesses, expiry | **Done** | `link-slot.test.ts` (2× GET then complete then 404, lease-expiry retry, TTL, missing/wrong/malformed proof, query refused, 5 guesses then 429) |
| R3 compact slot state, bounded responses, row-cost notes | **Done** | `link_slots` schema, budget test above |
| R4 `POST /v1/sync` + `GET /v1/sync…`: id, revision, parts, total, hash, expiry, duplicates, concurrent-assembly cap, per-device cursor, 16 KiB bound, malformed/unknown/oversized/conflicting rejection | **Done** | `sync-endpoint.test.ts` (12 tests) |
| R5 legacy byte-identical send/inbox/ack/ws/media, no new rows | **Done** | `legacy-link-sync-compat.test.ts` |
| R6 PROTOCOL.md + README.md with error table, proof/lease reasoning, limits | **Done** | this branch's docs |
| Full server suite + both tsc checks | **Done** | 18 files / 238 tests / 0 failed; `tsc` 0 and `tsc -p tools` 0 |
| WP05 lane stays `doing` | **Done** | `linux-port/tasks.md` untouched (outside this slice's ownership) |

WP05 acceptance criteria, as they apply to a server-only slice:

| AC | Status |
|---|---|
| WP05-AC1 bundle round-trip (seal/open in `:core`, wrong ephemeral key, second read 404, expired 404) | **Partial** — the server half is proven: read twice returns the bundle (lease), completion makes the third read 404, expiry 404. The HPKE seal/open and wrong-key cases are client code and were not in this slice. |
| WP05-AC2 SENT copy appears on a JVM client within one cycle with the same payload id | **Not run** — transport exists; the Android/JVM emitters are WP05 client work. |
| WP05-AC3 rotation ordering (`SYNC KEYS` before broadcast) | **Not run** — needs the client rotation path and durable `stored` barrier. |
| WP05-AC4 `TYPE:SYNC` from a non-self sender is dropped and logged | **Not run** — receiver-side rule; the server is content-blind and cannot enforce it. |
| WP05-AC5 unlink closes sockets `4009` and stops retaining | **Done (WP05-A, unchanged)** — covered by existing `device-auth-hardening.test.ts`; this slice does not touch it. |
| WP05-AC6 no change for a 1.15 contact (harness) | **Partial** — byte-identical reply test only; no emulator harness run. |

## Contract deviations from 05 §4, and why

1. **Proof in a header, not `?proof=`.** 05 §4 says query parameter; G1 requires a header because URLs are
   logged. G1 takes precedence per the gate preamble. A query string is actively refused.
2. **Lease + explicit `DELETE` instead of delete-on-first-read.** 05 §4 says "returns the bundle and
   deletes it"; G1 requires lost-response recovery. GET is repeatable, DELETE completes.
3. **`409 link_busy` rather than replacement.** 05 §4 is silent; refuse-and-keep was chosen so a second
   device (or a retry with a stale code) cannot overwrite the bundle a desktop is about to fetch.
4. **`/v1/sync` exists at all.** 05 §3 defines `TYPE:SYNC` bodies but no server transport; G3 requires a
   bounded, versioned codec. This adds the transport without changing `TYPE:SYNC` messages.
5. **Per-device cursor** for the sync channel rather than one shared per-identity cursor, matching WP04:
   each device acks what it stored, so one device cannot skip another's events.

## Security properties actually demonstrated

- The stored slot is a hash of the code; the code is never sent in a URL, never stored server-side and
  never logged by this code. The test client only ever puts it in `x-relay-link-proof`.
- A wrong guess cannot distinguish an existing slot from an absent one (same 404, same body), and the two
  rate buckets bound attempts whether or not the slot exists.
- The bundle cannot be read after completion or after 10 minutes, and a second PUT cannot replace a live
  one.
- After migration the shared identity key can neither publish nor read a slot; only the admin key can
  publish, and only the code holder can read.
- Sync bodies are accepted only from authorized keys of the same identity; assembly never serves partial,
  wrong-length or wrong-hash data, and a conflict leaves the previous state intact.

## NOT RUN

- No deploy, no `wrangler deploy`, no remote pilot; only the vitest workerd pool.
- No manual `wrangler dev --local` run; the edge routing is exercised through the Worker entrypoint in
  tests, not by a separate local server process.
- No real network loss / interrupted HTTP test: the lease is tested by a repeat GET after a simulated
  lease expiry, not by dropping a response mid-flight.
- No sealed-bundle interop with `:core` (HPKE seal/open, QR encoding) — client side, other slice.
- No Android/JVM sync client, no emulator harness, no 1.15 device.
- No load, CPU or memory measurement; row counts only.
- Rate limiter behaviour across a Durable Object restart is not tested (it is in-memory by design).
- No test of concurrent PUTs arriving in the same instant beyond the unique index and the checked insert
  (the DO serialises requests, so a true race needs a second object).
