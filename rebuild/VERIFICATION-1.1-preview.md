# Calculator 1.1 preview verification — 2026-10-17

Branch: `feat/rebuild`, based on `93825e1`. This records measured checks, not a guarantee
that the app has no bugs or cannot be compromised. The previous preview's results are
preserved in [VERIFICATION-2026-10-16.md](VERIFICATION-2026-10-16.md).

## Completed automated checks

- App and Android test APK build successfully. Version code 2, version name 1.1-preview.
- 234 JVM tests passed, with no failures, errors or skips. New coverage includes the
  exact 600,000 ms session deadline, no extension on return/use, bounded profile-photo
  encoding, malformed inputs, removals and transport-size limits.
- 14 Android tests passed on a dedicated Android 15 emulator. These cover real Keystore
  state, lockout/recovery policy, diagnostic consent, calculator arithmetic/layout,
  notification previews/history/avatars and contact-ID separation, Home/return and
  activity recreation, expiry back to Calculator and reauthentication.
- Media tests open received encrypted portrait and landscape images, verify preview
  aspect ratios, open/close the internal photo viewer, and verify camera JPEG rotation.
  A five-second portrait MP4 fixture actually plays and advances position; closing and
  expiry stop playback. Expiry also removes private plaintext view files.
- Profile-photo tests reject unapproved/blocked contacts, decrypt an approved sender's
  photo, verify encrypted canonical storage, apply removal, and reject stale replay.
- Lint has zero errors, 14 warnings and 7 informational findings. The new functional
  warning recommends AndroidX ExifInterface instead of the platform implementation;
  JPEG orientation behavior was checked on Android 15. Existing locale/deprecation
  and other warnings remain.

The UI expiry tests call the production expiry check at its deadline rather than
waiting ten wall-clock minutes. JVM tests verify the full ten-minute elapsed-time
policy. Native video popup controls are explicitly dismissed on disposal.

## Live two-device checks

Two independent Android 15 emulator installations connected to public Nostr relays.
The production ML Kit decoder read the displayed 30-minute invitation, both users
approved the pairing, and verified contacts were created. Text/emoji and replies
arrived in both directions (3.4 s and 9.9 s including UI automation).

Both peers selected gallery photos, returned through the active session, uploaded
encrypted media, and downloaded/decrypted matching JPEG hashes. Both selected their
own profile photo through Account and the system picker; the approved other peer
received identical photo bytes. Removing Alice's profile photo removed it on Bob.
Both app databases and UI were checked. This verifies image decoding and the real
QR/NFC confirmation path, not optical camera-to-screen scanning.

After the final notification-history change, its two Android notification tests
were repeated against the final APK and passed. The final APK was then used for
the complete live two-device flow. Package signatures, archive checksums, uploaded
asset digests and merged-source identity are checked during publication.

## Behavior and privacy

A successful four-digit code plus + starts a fixed ten-minute session. Background
and return do not extend it. A process restart ends it. Expiry closes private screens
and any media viewer; the original code is needed again. Three wrong submissions
still require the owner's signed relay recovery command. No owner web server is needed.

Photos fit their source aspect ratio. Photo/video taps open an internal secure viewer;
no external app receives the decrypted file. Photos support pinch zoom. Videos use
native playback controls. Temporary per-view plaintext copies are deleted when their
bubble leaves composition, with leftovers removed at process startup. Existing outgoing
compressed-media caches remain a plaintext-at-rest limitation.

Account → Profile photo explicitly shares a JPEG of at most 128 px / 6 KiB with mutually
approved contacts through the existing encrypted conversation. There is no public
profile endpoint or extra avatar fetch. Photos are encrypted at rest and excluded from
backup; revisions prevent stale updates from undoing removal. Recipients can save photos.
Both peers must use the updated app for this new control-message protocol.

Notifications provide sender name, received photo/generated avatar, this app's generic
purple message badge and up to five unread messages per conversation. They have no tap
intent or reply action. Android's app attribution remains Calculator; the badge is not
Viber's logo. See [Android's notification anatomy](https://developer.android.com/develop/ui/compose/notifications)
and [Viber's Android notification features](https://help.viber.com/hc/en-us/articles/9029767646237-Reply-search-and-customize-on-Android).

## Remaining limits

This is a debug-signed test preview. Physical-phone camera/NFC, real Orbot routing,
Doze/OEM survival, battery usage, accessibility/font scaling and the full range of image
and video codecs remain unverified. Real-network video transfer was not newly tested;
received encrypted-video playback was. Public relays/blob servers can change availability
or retention. The protocol has no Double Ratchet or independent security audit. Stable
production signing remains necessary before a production release.

## Reproduce

Use dedicated emulator data only. These tests use code 4826 and synthetic fixtures,
and the two-device harness sends fixture messages/images through public relays/storage.

```sh
rebuild/dev/build.sh -PrelayPreview=true assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
# Install both generated APKs on the dedicated emulators first.
adb -s emulator-5554 shell am instrument -w -e class com.relay.app.calculator.CalculatorSecurityTest,com.relay.app.calculator.MediaViewerTest,com.relay.app.calculator.ProfilePhotoTest com.relay.app.test/androidx.test.runner.AndroidJUnitRunner
python3 rebuild/dev/test-two-emulators.py
```

The included test clip is synthetic: FFmpeg testsrc2, 160x320, 15 fps, five seconds,
MPEG-4 Part 2 in MP4. No personal photos, local databases, private owner state, credentials,
SDK/JDK installations or signing keys are distributed.
