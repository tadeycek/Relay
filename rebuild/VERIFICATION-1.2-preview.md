# Calculator 1.2 preview verification — 2026-10-17

Branch: `feat/rebuild`, based on `d69bfe4`. This is measured verification, not a
claim that the app is free of bugs. The previous release record is preserved in
[VERIFICATION-1.1-preview.md](VERIFICATION-1.1-preview.md).

## Changes checked

- QR completion repairs a pre-existing generated contact name. Encrypted versioned
  profile updates follow a peer's display-name changes while preserving local aliases.
- Reconnects and incoming messages request profiles again with a cooldown, recovering
  profile data that arrived before mutual pairing completed. Unapproved/blocked peers
  cannot set a name or avatar. Old revisions cannot undo newer names or photo removals.
- Late photo/name arrivals refresh an existing notification without adding a message
  or another alert. Notification history stays bounded and separated by contact.
- Android conversation shortcuts supply the sender's name/avatar to the compact
  notification template. Unchanged shortcut icons are not pushed on every message;
  existing shortcuts remain usable if Android rate-limits an update.
- The supplied PNG is composed at the avatar's bottom right. Android overlays its own
  small notification icon, so a white foreground traced from that picture also fills
  the system icon slot. Android controls its color/background and exact placement.
  App attribution remains Calculator; taps and reply actions remain inactive.
- New name controls use an existing profile-control extension so 1.1 clients ignore
  them rather than displaying them as ordinary chat text. Update both devices for
  display-name synchronization. Each sender must actually choose a profile photo.

## Completed checks

- Preview and instrumentation APKs build. Version code 3, version name 1.2-preview,
  application ID com.relay.app, minimum Android API 26, target API 36.
- 237 JVM tests passed: zero failures, errors or skips. New tests cover bounded
  Unicode names, malformed input and compatibility with the previous control parser.
- Ten targeted Android checks passed on Android 15 across notification/profile and
  media runs: three notification tests, four profile/QR tests, and three media tests.
  Checks include OS-posted sender name/icon, published conversation shortcut,
  late-profile refresh without duplicate history, no tap/reply intent, real encrypted
  avatar storage, stale/blocked update rejection, placeholder repair and local aliases.
- The actual Android preview was captured and visually inspected: shared name/photo
  and the supplied phone design are shown in the avatar's bottom-right badge.
- Portrait/landscape images retain their aspect ratio and open internally; EXIF rotation
  is preserved. The five-second test MP4 advances playback, stops on close and forced
  session expiry, and private viewing copies are removed. This is local received-media
  playback testing, not a new live video transfer between devices.
- The media harness now starts a fresh activity and waits for it to render. It also
  waits for Android to settle after forced expiry. No saved chats or app data were reset.
- Lint: zero errors, 15 warnings and seven informational findings. One new warning is
  the traced small icon's long vector path; other warnings include existing platform
  ExifInterface, locale and deprecation findings.
- APK v2 signature verifies and its signing certificate matches the previous 1.1 APK.
  In-place update on both emulator installations succeeded. The primary emulator was
  reopened and unlocked for the user's phone-to-PC test.

An initial broader run hit the session smoke test's explicit fresh-install prerequisite;
that test was not represented as passing on this already-configured installation.
The unchanged ten-minute duration is covered by JVM tests; the media expiry test forces
its production deadline instead of waiting ten wall-clock minutes. Earlier media runs
also exposed harness timing issues and a missing fixture; the original clip was recovered
and the complete media class subsequently passed.

## Limits and next checks

The physical phone's notification layout and real phone camera pairing were not
controlled in this run. Android and OEM notification templates can render differently.
No new live network video transfer, Doze/battery behavior or real Orbot routing was
verified. Earlier two-emulator text/photo/profile network checks are recorded in the
1.1 report. No independent cryptographic audit or Double Ratchet is claimed.

Conversation previews publish Android conversation data. A launcher may expose the
sender name/photo in Calculator's long-press menu; opening the chat or removing its
contact removes that shortcut. Public notification previews intentionally expose their
text/photos subject to device settings. Canonical received profile photos stay encrypted;
existing outgoing media cache limitations remain as documented in the previous record.
This remains a debug-signed test preview.
