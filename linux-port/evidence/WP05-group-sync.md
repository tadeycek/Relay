# WP05-AC3/AC4 + WP07-AC2 evidence — group-roster self-sync (phone → desktop)

Status: **implemented and JVM/local-Worker verified; not verified on a real phone or the live server.**
Branch `feat/linux-port` on `1bfc244`, uncommitted. Decision: LP-D03 (rosters are edited on the phone; the desktop mirrors them).

## Schema: `GroupRoster/1` (`core/.../linking/GroupRosterSync.kt`)

Carried as `J:` of `TYPE:SYNC|GROUP|V:1|SID:<id>|R:<rev>|O:primary|J:<base64 of sealed body>`; unchanged `SyncCodec`
(no codec edit: `SyncBody.Group` already carried one opaque base64 field). `J` = base64 of a `TYPE:ENC|CT:…` string,
the plaintext below sealed with the account's own HPKE key (phone: `AndroidInnerEnvelope.seal(myPublicKey)`, desktop opens with
`InnerEnvelope.open`). Plaintext (UTF-8 JSON, fixed key order, <= 24 KiB):

```
{"v":1,"state":"<GroupProtocol Sync packet>","avatarRev":<long>,"avatar":"<base64 JPEG>"|null,"tombstone":<bool>}
```

| Field | Rule |
|---|---|
| `v` | must be integer `1`; other integer = `UNSUPPORTED_VERSION`, non-integer = `MALFORMED` |
| `state` | the existing `GroupProtocol` v2 sync packet verbatim: id (uuid), owner (64 hex), name (1..30), revision (>0), members (1..17; key, name, optional Tink `inner`, <=2 relay hints); one validator, <= 10 000 bytes |
| `avatarRev` | `-1` never had a picture (then `avatar` null); `>=0` + `avatar` null = picture removed at that revision |
| `avatar` | base64 JPEG, magic `FF D8 FF`, 3..8192 bytes (`MAX_AVATAR_BYTES`); desktop additionally decodes it (`validDesktopAvatar`, <=256x256) before storing |
| `tombstone` | group gone for this account (owner deleted, we were removed, or we left): receiver hides it; history kept |
| keys | exactly these five; extra/missing key = `MALFORMED`; plaintext > 24 KiB = `TOO_LARGE` |

- Sync id `SID` = first 32 hex of `sha256("relay-group:owner:id:revision:avatarRev:tombstone")` → same snapshot, same id.
  `R` = `revision + (avatarRev+1) + (tombstone?1:0)` (monotonic in each component).
- A full roster (17 members, 8 KiB picture) exceeds one 12 KiB wire body; `SyncCodec.chunk` splits it (measured below: 4 parts
  in the Worker run). `J` stays under `MAX_J_BYTES` (64 KB) — pinned by `maxRosterWithMaxAvatarFitsTheCodecThroughChunking`.
- Golden vectors: `core/src/test/resources/golden/group-roster-sync.fixture` (plaintext full + tombstone, sealed `sync_wire` + `sync_wire_tombstone`).
  Sealer in the fixture is a deterministic *reverse-bytes* `KeyMaterial` stand-in for HPKE (documented in the file header); real HPKE is
  covered by the Worker run. Re-record: `RELAY_GOLDEN_RECORD=1 :core:test`.

## Phone producer (`:app`)

- `sync/GroupRosterStager.kt` (pure): seals the snapshot, builds the `GROUP` body (`origin=primary`), stores the *finished logical wire*
  in the **existing** `sent_sync_outbox` (no schema change): `payload_id = "gr:<syncId>"`, `target = group:OWNER:ID`, `confirmed=1`.
  Retry after restart re-posts the stored bytes (identical `data`, `hash`, server id). Same snapshot staged twice = one row.
- `SentSyncPublisher.flush`: roster rows are decoded back to `SyncBody.Group` and sent via `SyncCodec.chunk` with the same retry
  schedule; added `SyncOutbox.backedOffBefore` so a roster item never overtakes an earlier row that is backing off (Android: one indexed SELECT).
- `SentSync.stageGroupRoster(context, localGroupId)` (Android glue, compile-verified only) builds the snapshot from `SharedGroups`/`GroupAvatars`
  (tombstone = hidden or we are not a member). Hooks, each in the edit's own SQL transaction: `SharedGroups.create`, `changeMember` (add/remove),
  `refreshOwnRosters`, `delete`, `handleSync` (roster received as member), `GroupAvatars.set` (and avatar received). Rename = `changeMember`/roster
  path through `save`; there is no separate rename entry point in the app today (see residuals).
- Legacy/unlinked: `stage` returns before sealing or writing when `LinkedHint` is false; `flush` finds no rows, so no request.
  Existing SENT lane code path: only `wiresFor` was restructured to return `(syncId, wires)`; the 315 pre-existing app tests still pass.
- Failure isolation: staging is a local DB write inside the edit transaction wrapped in try/catch; posting is a later tick — a network failure
  cannot reach the edit. Removed members' behaviour (`rosterRecipients`, no future keys) is untouched.

## Desktop receiver (`:desktop`)

- `SentSyncApplier.judgeGroup`: `origin` must be `primary` (a computer never authors rosters); body must open with our own key; schema+bounds via
  `GroupRosterSync.open`; live roster not listing our pubkey becomes a tombstone. Refusals → `ApplyReport.groupRejected`, cursor still advances
  (lane never wedges). Duplicates/older → `groupStale`. Changes → `groupApplied`. The "sender is our identity" check is: server-authenticated
  own-identity sync channel + self-sealed + `origin=primary` (the sync body has no signature of its own).
- `SyncStore.commitGroupSync(roster, cursor, now)` (new, `DesktopMessageStore`): roster CAS (strictly newer revision), same-revision tombstone hides
  only, newer picture revision replaces picture, conflicting owner for an existing group id → `CONFLICT`, **cursor in the same SQL transaction**.
- Store: additive `group_rosters.hidden` column via `ensureGroupHiddenColumn()` (idempotent, `user_version` stays 3 so existing schema tests hold);
  `conversations()` omits hidden groups; `StoredGroup.hidden`; `saveGroupState(visible=…)` lets the direct owner path un-hide; `DesktopGroups.send` refuses hidden groups.
- Roster write entry points on the desktop remain disabled (LP-D03); nothing in the UI was added.

## Tests

New files: `core/.../linking/GroupRosterSyncTest.kt` (11), `app/.../sync/GroupRosterProducerTest.kt` (10), `desktop/.../GroupRosterSyncApplyTest.kt` (18),
`desktop/.../DesktopLocalWorkerGroupSyncE2ETest.kt` (1, gated). Cross-module contract: the app test asserts the **real producer path**
(`GroupRosterStager` → durable-outbox fake → real `SentSyncPublisher` → posted envelope) emits bytes **equal** to the fixture `sync_wire`; the desktop
test feeds that same fixture to the **real** `SentSyncApplier` + real encrypted `DesktopMessageStore`. Core test round-trips encode/seal/chunk/reassemble/open.

### RED (first runs, before production code)

| Layer | Command | Result |
|---|---|---|
| core | `:core:test --tests '*GroupRosterSyncTest*'` | `compileTestKotlin FAILED` (no `GroupRosterSync`) |
| app | `:app:testDebugUnitTest --tests '*GroupRosterProducerTest*'` | `compileDebugUnitTestKotlin FAILED: 'backedOffBefore' overrides nothing` (the stager/publisher existed; the ordering seam did not) |
| desktop | `:desktop:test --tests '*GroupRosterSyncApplyTest*'` | `compileTestKotlin FAILED: Unresolved reference 'groupApplied'/'hidden'/…` (applier classified GROUP as `otherKind`) |

Honest note: the app stager and publisher edit were written before the app test (the first app RED is a compile error on the one missing seam); after the seam
landed every app test passed on the first run, so the **mutation checks below** carry the proof for the app side.

### GREEN

`GroupRosterSyncTest` 11/11, `GroupRosterProducerTest` 10/10, `GroupRosterSyncApplyTest` 18/18 on the first run after implementation.

### Mutation checks (not RED/GREEN: each run executed after the tests passed; source restored byte-for-byte, md5 verified)

| # | Mutation | Test that failed |
|---|---|---|
| D1 | removed `origin == primary` check | `a desktop-origin GROUP event is refused…` |
| D2 | roster CAS `>` → `>=` only | **survived** (the SQL `revision < ?` guard still blocks equal revisions: equivalent mutant) |
| D2b | CAS `>=` in branch **and** SQL `<=` | `duplicates and replays are idempotent` |
| D3 | `commit()` before the cursor write (non-atomic) | `store commit is one transaction…` |
| D4 | hidden groups still listed | 4 tests (`tombstone hides…`, `late older roster cannot resurrect`, `removal of self`, `state survives a reopen`) |
| D5 | removal-of-self not a tombstone | `removal of self is a tombstone` |
| D6 | avatar image not validated | `an invalid avatar image keeps the roster change…` |
| D7 | rejected event stops the lane | 3 tests incl. `malformed and oversize rosters never wedge the lane` |
| A1 | unlinked gate removed | `unlinked install stages nothing and makes zero requests` |
| A2 | backoff-ordering filter removed | `older rosters are posted first and a backed-off one holds back the later ones` |
| A3 | row id not idempotent | `re-staging the same snapshot while queued adds no second row` |
| C1 | avatar bound off by one | `encodeRefusesOversizeAvatarNonJpegAndInconsistentFields` |
| C2 | version not enforced | `decodeRejectsEveryMalformedShapeWithoutThrowing` |
| C3 | sync id ignores avatar revision | `goldenSyncWireIsPinned…`, `syncIdIsStablePerSnapshotAndRevisionIsMonotonic` |

### Gated local-Worker test (JVM stand-in for the phone — **not a phone**)

`env -u RELAY_WORKER_DIR RELAY_E2E=1 RELAY_NODE=/home/tadej/.npm/_npx/387698761821791d/node_modules/.bin/node ./gradlew --no-daemon -Dorg.gradle.java.home=$JAVA_HOME :desktop:test --tests '*DesktopLocalWorkerGroupSyncE2ETest*'`
(Node v24.22.1 from the `npx --package=node@24` cache, wrangler `dev --local` port 8797, throw-away persist dir). Output:
`WP05-GROUP-E2E OK port=8797 parts=4 revision=3 hidden=true cursor=14 serverEventsFirstPage=6`.
Covered through the real `/v1/sync`: a 17-member roster with an 7.8 KB picture chunked into 4 server events, sealed with **real HPKE**, applied by the
linked desktop's own sync loop; a newer rename; an avatar-only change; a stale replay (ignored); a tombstone (hidden, off the rail); server holds no
plaintext name/member. The Worker was stopped (no wrangler/workerd/port 8797 left; checked with `ps`/`ss`). No `RELAY_WORKER_DIR`/`XDG_*` exported.

### Full verification (separate invocations, `--no-daemon --rerun-tasks`, `JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1`, `ANDROID_HOME=/home/tadej/Android/Sdk`, counts parsed from JUnit XML)

| Task | exit | tests | skipped | failures | errors | baseline → now |
|---|---|---|---|---|---|---|
| `:core:test` | 0 | 511 | 1 | 0 | 0 | 500 → 511 (+11) |
| `:desktop:test` | 0 | 196 | 7 (env-gated E2E incl. the new one) | 0 | 0 | 177 → 196 (+19) |
| `:app:testDebugUnitTest` | 0 | 325 | 0 | 0 | 0 | 315 → 325 (+10) |
| `:app:assembleDebug` | 0 | — | — | — | — | `app-debug.apk` 138 713 219 bytes |

`gradle.properties` not touched.

## NOT RUN

- **Real phone**: `SentSync.stageGroupRoster`, the `SharedGroups`/`GroupAvatars` hooks, `AndroidSyncOutbox.backedOffBefore` and real Keystore/Tink sealing on Android are
  compile-verified only (no emulator/device; no JVM SQLite in `:app` unit tests).
- **Live server** (`relay-mailbox` production): nothing was posted anywhere but a throw-away local Worker.
- Desktop UI rendering of a tombstoned/updated group (only the store/list API is asserted); no Compose test added.
- Multi-desktop (two linked computers) fan-out.

## Residual blockers / decisions needed

1. **KEYS ordering**: no `TYPE:SYNC|KEYS` producer exists in the tree (`SyncBody.Keys` is never emitted). The "KEYS before post-rotation group state" rule is
   delivered as a lane property (created_at-ordered outbox, strictly increasing roster `createdAt`, `backedOffBefore`), proven for roster rows; it applies to KEYS
   automatically once a KEYS producer stages through the same outbox, but there is no test with a real KEYS item.
2. **Server retention is 24 h**: a desktop offline > 24 h misses roster events; there is no full-roster resync/snapshot request. A re-link carries groups (LP-D06 bundle).
3. **Rename**: the app has no standalone rename API (`SharedGroups` has create/changeMember/delete); the hook set covers every existing roster write.
4. `app/build.gradle`, `server/**`, board files (`tasks.md`, WP frontmatter) were not edited (out of ownership); `tasks.md` still needs the WP05/WP07 rows updated by the owner.
5. Independent code review: none (self-review only, per repo rule).
