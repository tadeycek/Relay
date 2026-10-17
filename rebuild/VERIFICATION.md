# Calculator 1.11 preview verification — 2026-10-17

Prepared on permanent development branch feat/rebuild from main's PR #30/#32 merge.
The published 1.10 preview is preserved. This report supersedes the historical 1.10
checkpoint for this build. Android 8+, versionCode 12, database schema 21.

## Implemented issue changes

- #31: seven-day LOCAL message retention for direct/group history, attachments,
  reactions, pending uploads and associated outgoing fanout. Contacts, aliases,
  paired identities, group rosters and permissions remain. Expiry runs on history
  entry, process startup/minutely while alive and ordinary periodic background work.
  Expired incoming content is rejected using its original timestamp, including after
  reconnect; deletion/profile/roster controls retain their separate handling.
  Android can delay background execution: an offline/stopped phone cleans up on its
  next run. This cannot erase remote relay ciphertext or copies already saved outside
  the app, and older peers do not acquire retention until updated.
- #33: authenticated in-chat photo-save notices after a successful explicit Save photo
  export; cancellation/failure does not announce a successful save. Notices identify
  the actor and stable photo message ID, with a local thumbnail when still available.
  Android 14+ reported chat screenshots include up to eight visible photo IDs and
  thumbnails. A chat capture can contain several photos, so all identified visible
  photos are listed rather than guessing one. Secure fullscreen viewing remains
  protected. No gallery spying, public photo URLs or capture-protection bypass.
  External saves/copies/cameras and unsupported capture methods cannot be detected.
- #34: non-badging notification channels for messages, security and location, plus
  zero notification number/badge icon. Existing channel migration copies current
  importance/sound/vibration/light/visibility settings and updates active cards.
  Android does not allow changing a previously created channel's badge policy in
  place; the migration uses new IDs. In-app unread counters are preserved. OEM
  launcher appearance is unverified.
- #35: draft acceptance now applies immediately after durable enqueue, with a retained
  acceptance token for recreation. Only an unchanged accepted draft or its accepted
  prefix is removed; appended text survives. Selection-aware edit tracking preserves
  replacements/in-place edits and new reply/media selections. Stale keyboard edits
  are rebased so they cannot restore the accepted prefix. Rejected local sends retain
  the draft. Direct/group button/IME paths share the synchronous ViewModel busy guard.
- #36: removed the permanent screenshot banner. A single accessible dot at the top
  right opens mute/unmute, direct location share/request or group members/settings.
  Screenshot support/limits and seven-day retention are available in that menu.
- #37: eligible contacts show approximate Active now / Last active in People and the
  direct-chat header. Authenticated encrypted status leases go only to verified
  contacts over the existing Tor-aware transport while private screens are resumed;
  calculator/locked/background screens do not renew them. Leases expire after one
  minute, and monotonic persisted timestamps reject stale/replayed rollback. Unknown
  means no observation, not offline. Privacy and Tor contains Share active status.
  These are activity observations, not proof of constant connectivity or availability.

Schema 21 adds contact activity and a reaction receipt timestamp. Migration retains
existing reactions/revision tombstones, messages and outbox entries; older upgrades
that create the latest reaction table do not add the column twice. The previous
schema-20 direct member group transport remains in place.

## Verified

- `rebuild/dev/build.sh -PrelayPreview=true assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug`: BUILD SUCCESSFUL.
- 301 JVM tests: zero failures, errors or skips (JUnit XML inspected). New checks cover
  accepted/appended/replaced drafts and stale keyboard callbacks, exact seven-day
  boundaries/future rejection, activity ordering/expiry/labels, and bounded valid photo
  references in screenshot/save controls. Existing crypto/group/QR tests also pass.
- Lint: zero errors, 19 warnings and 13 informational findings. Existing deprecated
  platform/plugin, resource/icon and notification-dismissal warnings remain.
- All 17 Control Panel tests passed.
- `python3 rebuild/dev/check-group-schema.py`: passed. Covers old migrations plus v21
  reaction preservation, per-identity monotonic activity, scoped message/outbox expiry,
  colliding group event IDs, and preservation of fresh content, aliases and rosters.
- `python3 rebuild/dev/benchmark-history.py`: history ordering/window/anchor/page
  regression checks passed. Its timing is synthetic host SQLite, not phone/UI/network.
- Android integration-test APK compiled, including non-badging channel migration with
  silent/blocked preferences. No Android tests were executed: adb reports no devices.

APK certificate, package checksums and source provenance are checked separately during
packaging/publication. The required certificate is
06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9.

## Remaining device checks / limits

1. Existing 1.10 install → 1.11 upgrade and SQLCipher v20→v21; seven-day boundary with
   app open, stopped and offline; saved external copies remain outside app control.
2. Direct/group button and IME sends while appending, replacing, replying, attaching,
   rotating, failing local enqueue and reconnecting. Verify actual keyboard behavior.
3. Existing badge/card migration and fresh direct/group/security/location notifications
   on affected launchers. Verify no badge while in-app unread and mute behavior remain.
4. Real Android 14+ hardware screenshots in direct/group chats with one/multiple photos;
   non-chat screens, lifecycle, older Android and secure viewer behavior. ADB captures
   do not prove screenshot callback coverage. Universal capture detection is unavailable.
5. Save still/GIF photo: destination picker cancel/failure/success, exact exported bytes,
   actor/photo reference on other phones, offline delivery/retry and expired/deleted media.
6. Two upgraded contacts: active lease, background/lock, disconnect/crash expiry,
   reconnect/restart, delayed/replayed updates, privacy off/on, blocked/deleted contacts,
   Tor unavailable and a peer on an old version. A missing update must never claim active.
7. Three upgraded group peers: direct member delivery with creator offline and existing
   v2 compatibility tests. No real end-to-end delivery or latency is claimed here.

No phone/emulator was available, and no emulator profiles were created. New issue
acceptance is not asserted solely from compilation. Background delivery and cleanup
remain subject to Android scheduling. This is not a claim of a bug-free app.

Platform references: [notification badge policy](https://developer.android.com/reference/android/app/NotificationChannel#setShowBadge(boolean))
and [Android screenshot detection limits](https://developer.android.com/about/versions/14/features/screenshot-detection).
