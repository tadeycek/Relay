# WP05-AC2 evidence — sent-copy sync, phone side

Status: **implemented, unit-verified at the logic layer, compile-verified for the Android glue; nothing is
device-verified.** Branch `feat/linux-port`, base `a70a595`, not committed.

## Flow as coded

Gate: `SentSync.hint` (SharedPreferences `relay_sent_sync/linked_hint`) is true only after this install linked a
computer (`PhoneLinkCoordinator.link` success) or saw a non-empty device list. Hint false = no request, no row,
no sealing, legacy behaviour unchanged. Setting it false again happens when the device list comes back empty.

**Publish (phone → desktops)**
1. `Outgoing.enqueue` (every direct send) builds the outbox entry as before, then calls `SentSync.stageDirect`
   in the caller's transaction. `SentSyncWire.mirrored` allows plain text, `TYPE:REPLY|` and `TYPE:MEDIA|`; every
   other body (handshake, receipts, profile, group control, reactions, deletions, location, pairing) is not
   mirrored. `SharedGroups.send` calls `SentSync.stageGroup` for `post` events (payload id = group event id, body =
   the `GroupProtocol` event packet, `TO = group:OWNER:ID`).
2. The staged payload is `RelayPayload(id = same payload id, ts = same ts, body = TYPE:ENC sealed to THIS
   ACCOUNT'S OWN key)` and goes into table `sent_sync_outbox` unconfirmed.
3. `OutboxWorker`: on `SendResult.Sent` -> `SentSync.confirmed` (row becomes publishable, a tick is launched); on a
   permanent failure -> `SentSync.failed` (row deleted). Retryable failures leave the row unconfirmed. Unconfirmed
   rows older than the 7-day give-up window are purged. A group row is confirmed when any leg is accepted.
4. `SentSync.tick` (rate limited to 15 s, one at a time, launched from the existing outbox loop in
   `MessagingRuntime` and after each confirm) runs `SentSyncPublisher.flush`: purge stale, take up to 8 confirmed
   due rows, check linked devices (`GET /v1/devices?device=primary`, cached 60 s; zero devices clears the table and
   the hint), build `SyncBody.Sent(origin = "primary", SID = sha256("relay-sent:"+payloadId)[:32], R = 1)`,
   `SyncCodec.chunk`, and `POST /v1/sync?device=primary` once per wire body (envelope: `part 0 / parts 1`, `total`,
   `hash`, `expires = now + 24 h`, `data` base64). Single wire: server id = SID. Chunked: server id =
   `sha256(SID/i/n)[:32]` per part (the server forbids two different bodies under one id).
5. Retry reuses the app's existing outbox backoff `OutboxPolicy.nextDelayMs` (5 s doubling to 15 min) and
   `GIVE_UP_AFTER_MS` (7 d). A retryable result (network, 429, 5xx, 409 `sync_limit`) records the attempt and ends
   the flush; 400/401/403/404/409 conflict/413 or an unencodable payload drops that row only. A failed publish never
   touches the user-visible message. Re-posting after a crash is idempotent (same ids, server answers duplicate).

**Apply (desktops → phone)**
1. `SentSyncApplier.poll` (same tick): `GET /v1/sync?after=<stored cursor>&limit=8&device=primary`, up to 8 pages.
2. Per event, in order: size <= 12 KiB, `len == total`, `sha256 == hash` -> `SyncCodec.decode` (bad prefix,
   unknown kind, unsupported version, malformed, oversize -> skipped and counted) -> `PART` goes to a fresh
   `SyncReassembler` per poll -> non-SENT kinds are skipped -> `origin == "primary"` is skipped -> payload decoded ->
   thread located (`TO` = contact pubkey, or `group:OWNER:ID` via `shared_groups`) -> one SQLCipher transaction:
   payload id already present = merge (delivery state NONE -> SENT, never a second row), otherwise open the inner
   layer and insert an **outgoing** row with the same payload id (`msg_id` / group `event_id`), `timestamp = payload ts`,
   state SENT, not unread.
3. Cursor: persisted to SharedPreferences with `commit()` and only then `POST /v1/sync/ack`, both after the DB
   commit. It moves to the last finished event, but never past a multi-part event that is still incomplete: it stays
   just before that event's first part (a restart refetches, the server keeps events 24 h).
4. Any exception in the transaction stops the poll; the cursor stays at the last finished event.

## Policies chosen

- **Unknown contact / group** (not found, soft-deleted, blocked, hidden group): the event is dropped, counted and the
  cursor passes it. No placeholder contact is invented from a sync event; contact sync is a separate piece of work.
- **Unreadable body** (inner layer cannot be opened): counted and skipped (cursor passes).
- **Attachments written on a computer**: applied as an outgoing text row holding the caption, or
  "Attachment sent from your computer". The blob is not fetched. The reference is published untouched.
- **Locations, handshakes, receipts, reactions, deletions** are not mirrored.
- READ/KEYS/CONTACT/... events are skipped on this phone (other work owns them). Skipping advances the cursor.

## Hooks into existing code (all minimal)

| File | Change | Why |
|---|---|---|
| `messaging/Outgoing.kt` | `enqueue` builds the entry itself (same calls as before) and calls `SentSync.stageDirect` | the one choke point of all direct sends; plain body is only known here |
| `groups/SharedGroups.kt` | one line in `send`: `SentSync.stageGroup` | group posts bypass `Outgoing.enqueue` |
| `messaging/OutboxWorker.kt` | `SentSync.confirmed` on Sent, `SentSync.failed` in `fail()` | a copy is only published for an accepted send |
| `messaging/MessagingRuntime.kt` | `launchIo { SentSync.tick }` in the existing outbox loop | no new service; the loop already runs when ONLINE, on kick and every <= 60 s |
| `linking/PhoneLinkCoordinator.kt` | `noteLinkedCount` after a successful link and after listing devices | sets/clears the hint |
| `data/db/DatabaseContract.kt`, `data/db/RelayDbHelper.kt` | `DB_VERSION` 24 -> 25, create `sent_sync_outbox` in `onCreate` and `onUpgrade(<25)` | durable outbox |

New (not existing code): `app/.../sync/SentSyncTypes.kt`, `SentSyncWire.kt`, `SentSyncPublisher.kt`,
`SentSyncApplier.kt`, `AndroidSentSync.kt`; `app/src/test/.../sync/SentSyncTest.kt`.

**Schema:** the app does **not** use Room; it uses SQLCipher with hand-written `onUpgrade`. The only schema change is
`CREATE TABLE IF NOT EXISTS sent_sync_outbox` (idempotent, additive) at version 25. There is no Room schema JSON to
export. The migration is **not unit-tested** (no JVM SQLite on the test classpath); it is a single idempotent
`CREATE TABLE IF NOT EXISTS`, compile-verified only. The cursor and hint are in SharedPreferences, so the cursor is
not in the same transaction as the message (a crash between them only replays an idempotent event).

## Verification

```sh
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ANDROID_HOME=/home/tadej/Android/Sdk
./gradlew --no-daemon --console=plain -Dorg.gradle.java.home=$JAVA_HOME -Dorg.gradle.jvmargs=-Xmx2g \
  --rerun-tasks :app:testDebugUnitTest :app:assembleDebug
```

JUnit XML summed: **211 tests, 0 failures, 0 errors, 0 skipped** (187 before + 24 new in `SentSyncTest`).
`:app:assembleDebug` BUILD SUCCESSFUL.

| Required case | Test |
|---|---|
| publish happy path (same payload id, TO, P identical) | `publishHappyPathCarriesTheSamePayloadIdTargetAndPayloadJson` |
| no linked device -> no network, no row | `unlinkedUserMakesNoNetworkCallAndWritesNoRow`, `staleHintWithNoLinkedDeviceStopsMirroringAndClearsRows` |
| device cache TTL | `deviceListIsCachedForTheTtl` |
| failed publish retried | `failedPublishIsRetriedWithTheAppBackoffAndNeverDropsTheRow`, `permanentRefusalDropsOnlyThatItem` |
| failed send publishes nothing | `failedSendPublishesNothing` |
| media/file reference untouched | `mediaAndFileReferencesAreCarriedUntouched` (also publish -> apply round trip) |
| apply insert / twice / merge | `applyInsertsAnOutgoingMessageWithTheSamePayloadId`, `applyingTheSameEventTwiceNeverDuplicates`, `anAlreadyPresentLocalMessageIsMergedNotDuplicated` |
| own origin ignored | `ownOriginIsIgnoredButTheCursorStillMoves` |
| unknown contact / group | `unknownContactIsDroppedWithoutCreatingAnythingAndTheCursorMoves`, `groupSentLandsInTheGroupThread` |
| malformed / unknown version / oversize / bad hash | `malformedUnknownVersionAndOversizeEventsAreSkipped` |
| commit failure leaves cursor | `commitFailureLeavesTheCursorAndTheAckUnmoved`, `aFailureMidPageKeepsEverythingBeforeItAndNothingAfter` |
| order: commit, cursor, ack | `cursorIsPersistedAndAckedOnlyAfterTheMessageIsCommitted` |
| chunked event | `aChunkedEventIsPublishedAsSeveralServerEventsAndReassembles`, `anIncompleteChunkedEventHoldsTheCursorBeforeItsFirstPart` |
| paging, other kinds, unreadable, list failure | `pollPagesThroughMoreThanOnePage`, `otherSyncKindsAreSkippedNotApplied`, `unreadableBodyIsCountedAndSkippedWithoutInsert`, `listFailureChangesNothing` |

## Failure matrix

| Failure | Result |
|---|---|
| network down / 5xx / 429 / `sync_limit` on publish | row stays, backoff 5 s..15 min, send unaffected |
| 400/401/403/404/409 conflict/413 on publish, or payload over codec bounds | that row dropped, others continue |
| devices lookup fails | nothing posted this round, rows kept |
| no linked device any more | table cleared, hint off, no further requests |
| contact send fails for good | row deleted, nothing published |
| process dies before publish | row is durable; resumes on next tick |
| list fails | no state change |
| malformed / unknown version / oversize / hash mismatch | skipped and counted, cursor passes |
| DB commit throws | poll stops, cursor and ack unmoved, event retried next poll |
| ack lost | local cursor already saved; event is not refetched, or if the cursor was rolled back it merges |
| partial chunked event | cursor held before first part until complete or server expiry (24 h) |

## Contract ambiguities

1. **`P` is not literally "as sent" to the contact.** The real payload body is sealed to the *contact's* Tink key, so the
   phone's own other devices could not open it, and `/v1/sync` bodies are not otherwise end-to-end encrypted (the server
   would read plaintext). The phone therefore sends a `RelayPayload` with the **same id and ts** whose body is the
   plaintext sealed (`TYPE:ENC`) to the account's own key, which a linked device opens with the shared keyset. The
   desktop agent's applier already opens `TYPE:ENC|` bodies with its own key, so the two halves line up in code.
2. **How codec chunks map to the server envelope.** Chosen: each `SyncCodec` wire body is exactly one server event
   (`parts = 1`); the desktop did the same. Server ids differ per chunk (hash-derived) because the server rejects
   different bytes under one id.
3. **Group SENT payload.** No single group payload exists "as sent" (each leg has its own id and seal). The phone sends
   the group event packet with payload id = group event id. The desktop's group SENT format is not known to this side.
4. Origin string of the phone is `"primary"`.

## NOT RUN

- No emulator, phone or real server was used. Nothing here is device-verified.
- Not run: the Android adapters (`AndroidSyncOutbox`, `AndroidSentCopyStore`, `MailboxSyncNetwork`, `SentSync`, prefs
  cursor, hooks in `Outgoing`/`OutboxWorker`/`MessagingRuntime`/`SharedGroups`): compile-verified only.
- Not run: the version 24 -> 25 database upgrade on a real SQLCipher database.
- Not run: phone <-> real desktop <-> real Worker end to end; real `TYPE:ENC` self-seal/open round trip (Tink) on device.
- Not run: media blob fetch for an attachment written on the computer (not implemented, see policies).
- Not run: group SENT insertion into `group_messages` and its UI refresh.
- Hint set by a legacy install that linked before this build is not migrated (it stays off until the device list is next
  loaded or a link succeeds).
