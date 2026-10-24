# WP09 — own SENT publication media grants

Status: server implementation verified locally; **not deployed**; platform integration and parent independent review/rerun remain separate. Base `74fe9f0`, branch `feat/linux-port`. No commit/push, board/version edits, private-directory reads, or platform/core edits by this server delegate.

## Integrator wire API

Keep existing encrypted SENT body unchanged. Add optional top-level **`media: [encryptedBlobSha256, ...]`** to the signed JSON of **`POST /v1/sync?device=<publisher device>`**. Lowercase 64-hex digests, distinct, max **8**, identical validation to `/v1/send`. PUT must have included **contact and own identity pubkey** in `x-relay-recipients`. Publisher must resolve to the blob uploader identity; shared identity ownership rather than one particular device key is intentional.

Repeat the media list on **every multipart request and retry**, including out-of-order parts. The list on the request that actually completes verified assembly is authoritative; partial requests do not persist a media manifest or grant/check blobs. Missing/empty media on completion remains legacy, even if a previous partial carried references. One-part publications simply include the array on their normal request.

Complete publication retains existing fields and adds:

```json
{"v":1,"id":"abcdef0123456789","rev":1,"parts":1,"complete":true,"cursor":42,
 "media":[{"hash":"<encrypted blob SHA-256>","status":"attached"}]}
```

`attached`: grant RPC completed; `pending`: event is already durable, outbox alarm will retry; `gone`: blob disappeared/expired after validation, event remains published. Completed duplicates add `media` entries with `unchanged`: **no new grant**, not an availability guarantee. A duplicate may retry the old outbox but never resnapshot or add newly supplied references. Partial replies remain unchanged. Missing/empty media keeps old replies/rows/behavior, with no extra blob RPC or grant table.

Errors: malformed/duplicate references `400 bad_request`, more than eight `400 too_many_media`; absent/expired/foreign-owned blob or uploader not already a recipient `409 media_unavailable`, no event/grant published. Held verified parts remain retryable after an availability failure. A publisher binding invalidated during the ownership RPC returns `401 auth_revoked` before publication. Event expiry is rechecked at publication.

## Snapshot, authorization and lifecycle

- Only full size/hash-verified assembly may request grants. One **synchronous SQLite transaction** inserts the completed event and existing `media_grants` outbox rows with `(own identity, active device)` pairs at that instant. No await inside the transaction. Primary included; revoked/dormant devices excluded using the existing active-device policy. No contact identity is added by this own-SENT path.
- Blob validation uses existing uploader/lifetime checks plus an own-recipient check. `recipients` retains confirmed rows while other recipients are waiting; origin's confirmation therefore does **not** remove proof of authorized own identity. If the last waiting recipient confirms first, `forget()` erases the blob and reconciliation cannot restore it.
- The alarm is armed **before** the event/outbox commit. Blob RPC drain is later and asynchronous: this is **not a distributed atomic transaction**. A receiver can fetch SENT before the blob grant and temporarily get `404`. Consumers need bounded retry for transient unavailable media, not permanent-success assumptions from `complete:true`.
- Existing grant RPC only adds device pairs for identities already present on the blob; it preserves confirmed pairs and uses exclusive blob lifecycle/settle semantics. Existing alarm retry applies durable pairs, never current enrollment. Confirmation, dormant/revoked-pair waiver and original lifetime remain unchanged.
- At publication the signer is resolved again after RPC awaits, preventing a revoked/re-enrolled device's old authentication key from committing an in-flight publication. Fresh media GETs continue through existing per-request signer authorization. This is server access control, not deletion of keys/files already on devices.
- Sync is **not no-history**: new devices begin sync at cursor **0** and may backfill retained events up to **24 h**. A device enrolled after completed publication can see an older SENT card without media access. Duplicate retry/GET does not broaden its grant. This fix covers **PUT → enroll → completed SENT**, not media history for future enrollment. Existing PUT grants are not removed.

## RED / mutation evidence

Initial test-first run:

```sh
npx --yes --package=node@24 node node_modules/vitest/vitest.mjs run test/sync-media-race.test.ts
```

Before production edits: **1 failed**, at new own-device blob GET: expected **200**, actual **404**, despite completed SENT. Sequence includes origin confirming its own stored file before enrollment; contact remains waiting.

Additional event-expiry test was written before its fix: ownership RPC held for 300 ms, event expires after 200 ms; **failed 200 vs expected 400**, then expiry recheck added.

Other boundary tests were added after the first implementation; do not treat their initial green as test-first evidence. Deliberate mutations established that two security guards bite:

1. Bypass `attachCheck` own-recipient requirement: `-t 'refuses unavailable ownership'` gives **1 failed / 1 passed / 16 skipped**; non-own-recipient publication incorrectly succeeds **200 vs 409**. Mutation restored.
2. Remove post-await `who.identity !== identity` guard: `-t 'stale publishing key'` gives **1 failed / 18 skipped**, revoked/re-enrolled old signer incorrectly publishes **200 vs 401**. Mutation restored.

Harness corrections: error shape is `body.error.code`; equivalent legacy row comparison uses separate equally empty identities rather than comparing successive events on one identity. Array-valued Vitest parameter cases are wrapped in objects so the cap/duplicate-array tests actually pass arrays. An attempted RPC call to a DO's alarm method was replaced with real confirmations to erase the blob during the check/grant gap (alarm helpers are used normally outside that mock).

## Final verification (Node 24, real workerd/SQLite)

Working directory `server/cloudflare`:

```sh
npx --yes --package=node@24 node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=/home/tadej/.hermes/cache/scratch/wp09-server-final.json > /home/tadej/.hermes/cache/scratch/wp09-server-final.log 2>&1
npx --yes --package=node@24 node node_modules/typescript/bin/tsc --noEmit
npx --yes --package=node@24 node node_modules/typescript/bin/tsc --noEmit -p tools
```

Parsed JSON: **278 total, 278 passed, 0 failed, 0 pending; 20 test files; success true**. Existing **259** retained plus **19** new sync-media cases. Both TypeScript checks exit **0**. Whitespace check on owned changes passes.

New tests cover: reproduced 404 then access; confirmed-origin own authorization retained; newly granted pair holds blob until it confirms and keeps alarm; foreign ownership; missing own-recipient; malformed/capped/duplicate refs; partial assembly no grant and enrollment between parts; invalid assembled hash; post-completion backfill without grant; duplicate no widening; revoked-at-publication exclusion; expired blob then retry of held completing part; durable pending/alarm retry and duplicate retry; confirmation deleting blob after check (`gone`); revocation/re-enrollment during ownership RPC and old-key GET denial; event expiry during RPC; legacy byte-identical reply shape/equal rows/no grant table or media metrics. Existing send/media lifecycle/race suites also pass.

Measured with `EXPOSE_METRICS` in targeted verbose run:

```text
WP09-SYNC-MEDIA-ROWS read=15;written=10 read=15;written=1
```

First pair = mailbox `x-relay-sql-rows`; second = blob grant-drain `x-relay-media-rows`, for one blob, own primary already confirmed, one newly enrolled own device and one contact primary. As in send, blob media header covers drain RPC accounting, **not** preflight `attachCheck` rows, alarm metadata, network, DDL or all provider billing. Legacy missing/empty test proves equal row headers with no media header/table.

## Third-context review fix — held completion without optional refs

The independent reviewer reproduced a one-part publication returning `409 media_unavailable`, retaining its verified part, then returning `200 complete:false` forever when identical retry bytes omitted `media` or supplied `media: []`. The completing request's refs are authoritative, so dropping unavailable optional refs must allow legacy publication.

Test-first RED (before the production change), working directory `server/cloudflare`:

```sh
npx --yes --package=node@24 node node_modules/vitest/vitest.mjs run test/sync-media-race.test.ts --reporter=json --outputFile=/home/tadej/.hermes/cache/scratch/wp09-retry-red.json > /home/tadej/.hermes/cache/scratch/wp09-retry-red.log 2>&1
```

Parsed JSON: **22 total, 20 passed, 2 failed, 0 pending; success false**. Both new empty/missing-ref recovery cases reached `retry.body.complete` and failed **false vs expected true**, after asserting the original `409 media_unavailable` and zero published events. The ordinary incomplete-duplicate and completed-duplicate controls passed before the fix.

Minimal production delta: the held-part guard now rejects only differing bytes with `409 sync_conflict`. Identical bytes skip reinsertion and flow through the existing held-part completeness, total and digest checks regardless of optional media refs. No changes to publication, snapshot, outbox, blob grants, schema or response encoding. An incomplete duplicate still returns the same incomplete/duplicate body; it now reads held assembly rows to determine completeness instead of assuming incomplete. Fresh missing/empty-ref publications retain the existing byte-identical reply and equal-row test; no extra writes, blob RPC, media header or grant table are introduced on those paths.

Regression coverage: two recovery cases retry the **same envelope bytes and expiry**, differing only by empty/missing refs; each publishes exactly one event with the original data, has no grant table/media result/header, and a repeated completed request returns the same cursor. A third case verifies an ordinary identical incomplete multipart duplicate stays incomplete until the missing part arrives. Existing late-enrollment coverage now also retries completed publications with empty and missing refs, asserting the same cursor, exactly one event, no outbox and no new device access; the nonempty-ref duplicate and durable pending-snapshot tests remain in place.

GREEN and type checks, **Node v24.22.1**, real workerd/SQLite:

```sh
npx --yes --package=node@24 node node_modules/vitest/vitest.mjs run --reporter=json --outputFile=/home/tadej/.hermes/cache/scratch/wp09-retry-green.json > /home/tadej/.hermes/cache/scratch/wp09-retry-green.log 2>&1
npx --yes --package=node@24 node node_modules/typescript/bin/tsc --noEmit
npx --yes --package=node@24 node node_modules/typescript/bin/tsc --noEmit -p tools
```

Parsed JSON: **281 total, 281 passed, 0 failed, 0 pending; 20 test files; success true**. Sync-media file: **22 passed** (previous 19 plus three new cases). Both TypeScript checks exit **0**. These are the third-context fix agent's runs, not the parent's final-source rerun; parent rerun and independent re-review remain separate gates. No commit, push, deployment or platform/private-directory edits. Foreground test processes exited; no standalone server/background process launched.

## Limits / not run

- Local Worker suite only. No deployment, live provider, phone/desktop UI or cross-platform smoke run by this delegate; platform agents own those paths. Parent independently reviews/reruns before any commit.
- Existing limits unchanged: eight refs, eight sync parts, 12 KiB decoded body, four open assemblies, 64 events / 256 KiB per identity; existing drain processes up to 64 rows per pass and schedules retry if rows remain.
- Test runs emit the existing Vite native-config extension warning and workerd `Application called deleteAllDurableObjects()` teardown diagnostics. Final JSON has no failed/pending tests; injected unreachable-grant test intentionally logs a grant failure.
- No standalone dev server/background job launched; foreground Vitest workers exited. No unrelated processes stopped.
