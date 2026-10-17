# Calculator 1.8 preview verification — 2026-10-17

## Implemented behavior

- Shared groups send encrypted photo/video references using the existing upload route.
  Received blobs are hash-verified, decrypted and stored encrypted at rest; membership
  and the creator are authenticated before any download. Pending group downloads
  survive process death and retry on reconnect/group reopen, with two download slots.
  Group attachment pickers reuse the session-preserving activity launcher. A Members
  button exposes creator-only additions/removals of eligible verified friends.
- Long press opens Reply, Copy and Delete in individual/group chats, preserving media
  short-tap viewing. Copy uses readable text/captions and marks the Android clipboard
  as sensitive where supported. Authored messages with UUID ids can request remote
  deletion; incoming requests cannot erase another author's messages. Tombstones and
  author-scoped history cutoffs prevent late delivery from resurrecting deleted rows.
  Old group rows lack event ids and support only local per-message deletion.
- Dispatch permits four recipients concurrently and preserves per-recipient order.
  QR relay hints/cached inbox lists avoid blocking on discovery; contacts without hints
  still perform discovery for correct routing. Fresh inbox discovery runs in parallel
  when hints exist. The worker's artificial 50 ms delay is removed. Text row/outbox
  insertion is transactional; fast relay acknowledgements also survive media insertion.
  Real network latency is not measured or guaranteed. Creator forwarding still limits
  group availability; this change does not replace the group protocol with a mesh.
- Connection settings open the dedicated Android notification channel. Only the user
  can hide that channel; Messages remains separate. The required background service
  and its foreground notification are retained. No unsupported notification hiding.
- Emergency mode requires the existing four-digit code and explicit destructive
  confirmation. Rejected codes use existing lockout counters. It attempts bulk deletion
  of the original author's histories for up to 15 seconds, then marks calculator-only
  mode, stops background work, disables messaging receivers/services, clears previews,
  shortcuts, app media/data/preferences and app Keystore keys, and ends the process.
  Startup checks the non-backup marker before messaging initialization and repeats
  cleanup before showing a calculator whose unlock gate/store are disabled.
- Optional dance uses the supplied JPG locally with Compose sway/bob/zoom for four
  seconds; off by default and skipped when system animations are disabled. No generated
  video or articulated character animation is claimed. The session deadline is unchanged.
- DB schema 17 adds group event ids, pending media references and deletion/purge tables.
  Existing group rows are preserved. Existing application/launcher labels/icons and
  native MessagingStyle notification layout are retained.

## Verification

- Final command: `rebuild/dev/build.sh -PrelayPreview=true assembleDebug
  assembleDebugAndroidTest testDebugUnitTest lintDebug` — successful (1m 25s).
- All 264 JVM tests passed: zero failures, errors or skips. New checks cover group
  media/quoted captions, authenticated delete/purge packets, malformed control rejection,
  slow-recipient isolation, four-recipient concurrency and per-recipient ordering.
- Lint: zero errors, 19 warnings and 7 informational findings. Existing Android Gradle
  plugin compileSdk support and deprecated API warnings remain.
- Host SQLite regression passes: additive v16/v17 migration, legacy row preservation,
  duplicate suppression, transaction rollback, foreign-key cleanup, author/group deletion
  isolation, and monotonic history cutoffs. This does not exercise Android SQLCipher.
- APK identifies com.relay.app, version 1.8-preview, code 9, minimum Android 8/API 26.
  Signature verified; SHA-256 signing certificate remains
  `06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`.
- Android integration tests compile but have not been run: adb reports no connected
  devices, and no emulator profiles were recreated following the user's deletion request.
  Four-second visual timing, actual send latency, cross-phone deletion and emergency
  cleanup/restart have not been verified on a phone.

## Limits and required phone checks

This is a preview, not an audited or bug-free production build. Install 1.8 on all
participants. Test on disposable data before relying on emergency mode:

1. Three phones: create a group, add another verified friend from Members, send photos,
   video, quoted text/media replies, remove a member and verify refusal to send.
2. Interrupt a download and reopen/reconnect; verify its attachment appears at its
   original ratio and opens. Check picker cancellation and session expiry in a picker.
3. Compare send latency with a slow/unreachable different recipient; verify queued/sent
   state and no duplicate rows. Confirm disconnected delivery after reconnect.
4. Hold text/photo/video for the menu; copy readable contents; delete an authored message
   on both phones, including delete-before-delivery and deletion while viewing media.
5. Hide only Relay connection through Android settings and receive a Messages preview.
6. Enable/disable dance, verify four-second duration, rotation/background/expiry, and
   disabled-system-animation behavior.
7. On disposable data only, reject a wrong emergency code, cancel the dialog, then use
   the correct code. Verify local data/key removal, calculator-only restarts, no boot/
   work/relay reconnection, and remote author-only history removal on compatible devices.

Remote deletion is best effort. Offline devices, old versions, blocked/removed contacts,
creator unavailability, unknown old ids, saved copies, screenshots, forwarded messages,
quoted snapshots, and relay ciphertext retention prevent a permanent-erasure guarantee.
Local reset cannot uninstall part of an APK; clearing app data/reinstalling can reset
calculator-only mode. Filesystem-level forensic erasure is not claimed. If the process
crashes after its marker is saved, cleanup repeats on the next launch.
