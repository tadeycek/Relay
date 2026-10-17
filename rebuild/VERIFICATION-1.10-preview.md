# Calculator 1.10 local preview verification — 2026-10-17

This follow-up is prepared on permanent branch feat/rebuild for the authorized push.
No new main merge, release or issue closure is performed. The published 1.9 preview
is unchanged. Issues #18–#27 remain open pending their device criteria.

## Implemented scope

- Retired and disabled ConnectionService. No connection foreground-service start,
  persistent connection card, background setting toggle or channel-settings workaround.
  App startup/boot/background paths stop the legacy service and cancel notification
  1001 and its relay_connection channel. Incoming user-message notifications and
  per-conversation mute are separate. Ordinary WorkManager checks continue; Android
  can delay them beyond the 15-minute minimum. Prompt closed-app delivery is not assured.
- Unlock illustration click and accessible Skip animation call the existing idempotent
  transition-completion path. Authentication, app lock and session expiry are unchanged.
- Gallery/Files QR import is offered before camera permission, and uses the same
  decoding/admission/preview/confirmation and mutual pairing flow as camera/NFC.
  My Code exports the exact rendered PNG and original expiry through CreateDocument.
  Saving/re-importing cannot mint a new invitation; issuer nonce consumption and expiry
  checks still govern trust. Saved-image success feedback includes its original expiry.
- Creator-only group picture choose/preview/save/replace/remove. Bounded 256 px / 8 KiB
  JPEGs are sent in authenticated private encrypted group-avatar controls; SQLCipher
  stores the blobs. Independent monotonic revisions reject old updates/removals. Local
  save and durable fanout are transactional; failed preparation/enqueue preserves the
  old picture. Reconnect/new-member sync queues current pictures without public URLs.
  Contacts, conversation list and group header resolve the same local group avatar.
- Android 14+ lifecycle-scoped ScreenCaptureCallback creates a timestamped screenshot
  event only for a visible/resumed chat. Capture notices use durable encrypted direct
  or creator-mediated group transport and stable event IDs. On-screen disclosure is
  explicit. Android 8–13 has no gallery-monitoring fallback. Hardware captures require
  device testing; ADB/instrumentation screenshots do not establish detector coverage.
- Local GIF/sticker selection preserves the original animated bytes (3 MiB, 2048 px
  bounds). Coil's pinned GIF decoder handles chats/viewer; other pictures retain normal
  compression. No external search provider. Reactions reference message UUIDs and use
  per-actor monotonic revisions, including removal tombstones. Sender identity comes
  from the authenticated channel/group author, not a claimed body field. Muting still
  suppresses conversation alerts. Deleted messages have their local reactions removed.
- Newest-200 history windows, explicit older-page expansion, stable timestamp/id order,
  retained lower boundary during incoming updates and composite indexes. Group sender
  resolution is per distinct author and delivery-state reads are batched. Active-group
  refresh no longer loads every group. No recipient routing/relay defaults changed.
- Additive schema 19 creates group avatars/reactions and query indexes. Existing rows,
  profile revisions, identities, memberships, permissions and private routes remain.

## Verification

- `rebuild/dev/build.sh -PrelayPreview=true assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug`: successful.
- 275 JVM tests: zero failures, errors or skips. New tests cover exact QR pixel roundtrip
  and unchanged nonce/expiry, malformed images, typed screenshot/reaction controls,
  invalid reaction IDs/revisions/emoji, group-avatar updates/removals and an actual
  maximum-avatar HPKE ciphertext within the existing 16 KiB payload limit.
- Lint: zero errors, 19 warnings, 11 informational findings. Existing SDK/plugin,
  deprecated API and resource/platform warnings remain.
- Android integration-test APK compiles, including retired-notification/manifest checks
  and byte-for-byte animated GIF preservation. These tests were not executed on a device.
- All 17 Control Panel unit tests passed.
- Host SQLite regression and synthetic history benchmark passed. Android SQLCipher,
  on-screen animation, system screenshot callbacks and physical notifications are unrun.
- APK metadata: com.relay.app, 1.10-preview, code 11, min API 26, target API 36. The
  compiled manifest disables ConnectionService and omits its remote-messaging foreground
  permission. DETECT_SCREEN_CAPTURE is declared for the Android 14+ callback.
- APK signature and local package checksums/provenance are verified with the final package.
  No credentials or private owner state are included by the packaging script.

The synthetic host SQLite benchmark uses 50,000 messages and 31 samples: full-history
query/materialization p50/p95 90.913/126.633 ms; newest-200 window 0.401/0.501 ms. It
checks equal-timestamp ordering, incoming anchor retention and older-page expansion.
These are not phone, SQLCipher, frame-time or network results. Details and the dated
free-hosting comparison are in PERFORMANCE-2026-10-17.md.

Host schema regression includes v16–v19 additive migration, old-row preservation,
transaction rollback, avatar/reaction monotonicity, removal replay rejection and
actor/conversation isolation. It does not execute Android SQLCipher.

## Remaining acceptance checks

No connected phone/emulator is available; no emulator profiles were recreated.
All phone assertions below remain unverified:

1. Upgrade with an existing connection notice, reopen, background, reboot and restart:
   no running-in-background card or service reappears; genuine messages still notify.
   Measure actual reception delays under Doze/OEM battery restrictions and force-stop.
2. Real QR export/import: camera denied, picker cancel, valid image, non-Relay/unreadable
   images, self code, re-export near expiry, import after expiry/use and issuer restart.
   Verify the original expiration and both participants' confirmation remain required.
3. Tap/Skip/automatic unlock animation; repeated taps, app lock, rotation and expiry.
4. Three phones: creator avatar replacement/removal, failure preserving previous image,
   new/offline members, stale replay, ordinary-member rejection and restart persistence.
5. Android 14+ physical screenshot buttons in direct/group chat, then non-chat screens,
   background, navigation, media viewer and session expiry. Check real timestamp/sender,
   offline delivery/dedup and the Android system disclosure. Verify older-OS behavior.
6. Send animated GIFs and text/media reactions in direct/group chats; change/remove,
   out-of-order/duplicate controls, before-target arrival, restart, muted chat, deletion,
   unsupported old peers, failed uploads and encrypted-at-rest copies. Confirm animation
   in both the bubble and viewer, including Android 8/9 decoder paths.
7. Long-history entry/scroll/older loads, simultaneous arrivals, inset/IME/media resizing,
   exact composer acceptance, new edits/attachments, button and keyboard sends. Obtain
   before/after phone p50/p95 and real recipient/relay timings before closing #25.

Creator availability, free-relay retention, Android background restrictions, seven-day
upload retries and lack of manual upload-retry UI remain. Android screenshot detection
is best effort. No claim of complete acceptance, instant delivery or a bug-free app.
