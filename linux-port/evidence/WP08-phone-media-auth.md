# WP08 prerequisite: phone media auth + linked-device recipients

## Changed (uncommitted)
- `app/.../media/LegacyMediaAuth.kt` (new): `mediaDevice`, `mediaRecipients`, `legacyStoredBytes`, `mediaOriginMayConfirm`, `phoneMediaClient` (pure seam) / `phoneMedia(context)`, bounded `downloadPhoneMedia`.
- `MediaTransfer.kt`: upload uses `phoneMedia(context)` (PhoneDeviceEnrollment.authKeys signer; `?device=primary` only after migration, otherwise the legacy URL/bytes); recipients = contact + own identity, deduped, ≤16, 64-hex only; stored-size cap check; receiver confirm via the same client; logs carry class name only.
- `BlossomRoute.kt`: mailbox download goes through `downloadPhoneMedia` (staged, digest-verified, bounded).
- `MediaSender.send`: media digest is persisted (`file_media_refs`) with the payload id BEFORE the outbox row exists, so `OutboxWorker -> sendWithMedia -> MailboxProtocol.sendBody(mediaDigests)` signs the digest in the top-level `media` list on POST /v1/send, and SENT sync parts (`SentSyncWire.envelope`, `AndroidSentSync` reads the same refs) carry it too. `PendingAttachments` persists refs inside its transaction before enqueue (same path for groups).
- `PendingAttachments.confirmSentMedia`: the sender's own DELETE confirm runs only after SENT-grant publication (`sync_granted`) when a computer is linked; unlinked users confirm immediately (no indefinite retention). Skipped while a SENT-sync outbox row is still pending.
- Tests: `LegacyMediaAuthTest` (6).
- Not mine / untouched: `calls/**`, `MailboxUrgent.kt`, `deliverUrgent` in MailboxTransport (WP13).

## Verification (isolated build dir `~/.hermes/cache/scratch/wp08auth`, JDK 21, --no-daemon, --rerun-tasks)
- RED: first run of the new test failed to compile (`mediaDevice`/`phoneMediaClient` etc. absent).
- GREEN: `LegacyMediaAuthTest` 6 tests, 0 fail/err/skip (JUnit XML). Migrated PUT/GET/DELETE are each signed over the exact `…/v1/media/<sha>?device=primary` URL with the injected per-device signer; un-migrated requests sign the unchanged URL with no query.
- `com.relay.app.media.*`, `transport.*`, `sync.*` unit tests: 73 run, 0 failures, 0 errors, 0 skipped.
- `:app:assembleDebug`: BUILD SUCCESSFUL.
- Full `:app:testDebugUnitTest`: NOT a clean result. Run aborted with test executor exit 143 after 47 tests; one failure is `calls/CallReviewBlockersTest.mailboxSignalPortDoesNotLetCancelOvertakeABlockedInvite` (WP13 agent's in-progress file, not in scope). Rerun once WP13 settles.

## NOT RUN
- Real phone / device; real Worker (wrangler) fixture with synthetic identities (no end-to-end upload→enrol→send race check on a server); `PhoneDeviceEnrollment.authKeys` selection itself is Android-keystore backed and is only exercised by wiring, not by a JVM test.
