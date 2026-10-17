# Calculator 1.9 preview verification — 2026-10-17

## Scope

Related issues: #19, #20, #21 and #26. These remain open because device acceptance
criteria have not been verified. This is a preview, not an audited or bug-free build.

- Direct/group button and keyboard sends share a synchronous busy guard. A submitted
  composer generation is captured once; durable acceptance is retained in the ViewModel
  across recreation. Only that generation and that attachment selection can clear.
  Local rejection preserves the draft and reports an error. Accepted text commits
  bubble/outbox atomically. Attachments commit an encrypted, synced local file, queued
  bubble and durable upload job before network work. Upload retries survive restart,
  use backoff and expire after seven days. Deleting a pending bubble cancels dispatch.
- Group fanout legs track queued/sent/failed states. Sent means acceptance by a relay,
  not recipient receipt/read or completion of creator forwarding. Existing direct read
  receipts remain unchanged. No new group recipient-delivery guarantee is provided.
- Reversed timelines open at newest without an unread-anchor jump. Stable keys anchor
  older reading positions; a reader-controlled 48 dp tolerance enables following.
  Status changes/history insertion cannot themselves re-enable following.
- Per-contact/group mute persists locally, cancels existing cards and gates the final
  Android notification post under a shared lock. Alert generation tokens suppress media
  downloads that span mute/unmute. Storage and unread tracking do not depend on mute.
  Global settings and Android permissions still apply. Security key-change alerts remain
  security alerts; conversation mute is not a way to hide a key-change warning.
- Group profile records have independent monotonic revisions. Current members submit
  only their own profile to the creator, who forwards it over approved encrypted links.
  Recipients cannot use profile packets to edit membership or permissions. Old group
  state snapshots cannot overwrite newer profile records. Current names resolve for
  members, historical sender labels and future notification previews. Local aliases
  remain local. Saved quoted reply snapshots intentionally retain their original name.
- Profile JPEG maximum rises from 128 px / 6 KiB to 256 px / 8 KiB. Compression starts
  at JPEG quality 90, lowers to 60 then reduces dimensions if needed. EXIF orientation
  is applied; aspect ratio is preserved; small images are never enlarged. The original
  power-of-two-only decode could exceed the intended bound; exact proportional scaling
  now enforces it. Images still travel inside encrypted messages and remain encrypted
  at rest: no public avatar URL or new image-fetch service. Both peers must upgrade;
  old clients may reject larger photos. Reselect the original to improve an existing
  avatar: the app retains only its previously reduced encrypted copy.
- Schema 18 adds profiles, delivery legs and attachment jobs without rebuilding existing
  tables. Existing identity, membership, history and recovery controls are preserved.

## Verification results

- Final command: `rebuild/dev/build.sh -PrelayPreview=true assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` — successful in 1m 37s.
- 269 JVM tests passed: zero failures, errors or skips. Android application and
  integration-test APKs compiled successfully. The Android tests were not executed.
- Lint: zero errors, 19 warnings and 9 informational findings. Android Gradle plugin
  compileSdk support, deprecated API and existing resource/platform warnings remain.
- Control Panel: `python3 -m unittest discover -s owner -p 'test_*.py'` — all 17 tests passed.
- `python3 rebuild/dev/check-group-schema.py` — all host SQLite regression checks passed.
- APK signature verified with Android apksigner. Package com.relay.app, version
  1.9-preview, code 10, minimum API 26, target API 36. SHA-256 signing certificate:
  `06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`
  (matches the previous preview). Preview BuildConfig service URL and enrollment
  token are empty. Packaging scans source/APK DEX for configured local credentials.
- Package SHA-256 checksums and source-commit provenance accompany the APK.

Host SQLite: additive v16/v17/v18 migration, legacy preservation, duplicate/stale
profile rejection, membership/history isolation, transaction rollback, foreign-key
cleanup and persistent attachment-job defaults pass. This is not Android SQLCipher.

JVM regression coverage includes authenticated group profile routing/spoof rejection,
malformed profile packets, maximum-photo roundtrip through real Tink HPKE and the
existing 16 KiB payload decoder. Android integration tests include direct/group duplicate submission and acceptance timing, mute cancellation,
independence, delayed-media suppression and local attachment acceptance with timing
logs. Compiling an integration test does not establish that it passes on a phone.

## Required device verification

adb reports no connected devices. No emulator profiles were recreated. Android tests
have not run; no phone screenshots, send-latency measurements or visual claims are made.

1. Record event-to-local-acceptance and acceptance-to-visible-bubble times for direct and
   group text, replies, photos/video; button and IME sends; rapid repeated submission;
   offline/reconnect, process restart, rotation, new text and replacement attachments.
   Verify no duplicates, retained rejected drafts and truthful queued/sent/failed states.
2. Open/reopen long individual/group histories, including unread messages. Test bursts,
   older history, delivery-state updates, late image sizing, IME/inset changes, scrolling
   away and back to within 48 dp. Confirm no unwanted jump while reading older content.
3. Mute one contact and one group; verify cancellation and no cards, sounds, vibration
   or heads-up alerts for text/media. Restart, receive while muted, check unread counts,
   then unmute: only subsequent arrivals should alert. Include a download spanning mute.
4. Three upgraded phones: owner/member rename, aliases, existing groups, historical
   labels, future notifications, stale/duplicate packets, offline member and creator
   reconnects. Confirm keys, membership, permissions and ownership remain unchanged.
5. Reselect portrait/landscape/rotated/small/detailed photos. Check pixel bounds, aspect
   ratio, encrypted synchronization/removal, legacy-peer behavior and visual quality.
6. Exercise existing calculator session expiry, media viewer, group deletion, recovery,
   background delivery and emergency cleanup on disposable data.

Free-relay retention, Android background restrictions and creator availability still
limit delivery. Failed uploads remain queued during retry and become failed at expiry;
there is no manual upload-retry UI. Delete a pending/failed bubble and resubmit if needed.
