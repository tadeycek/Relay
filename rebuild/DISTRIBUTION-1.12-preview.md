# Calculator 1.12 preview — Relay mailbox pilot

Android 8/API 26 or later. Application ID `com.relay.app`, versionCode **13** (published 1.11 used 12). This candidate is a non-debuggable release build signed with the original preview key, not this PC's unrelated debug key.

## Updating an existing phone

- Install the APK as an update. **Do not uninstall, clear storage or wipe the phone** to bypass an installation error.
- Required signing certificate SHA-256: `06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`.
- Matching signing and application ID permit an in-place update; actual phone migration/data preservation still needs acceptance testing. Existing seven-day message/attachment retention remains in effect; signing compatibility is not a promise to retain expired history.
- If Android reports an incompatible package or downgrade, stop and verify the installed APK's certificate/version. Do not generate a replacement key.

## Pilot behavior and limits

This build uses the configured **test delivery server**, not a separate production deployment. It does not use public relays; Tor is off. All participating peers need the mailbox-capable build and server membership, obtained through QR pairing with an admitted member. Updating an old Nostr contact alone does not automatically admit a phone to the new server.

Messages remain end-to-end encrypted; the server deletes its pending message copy after receipt. Photos use Relay's server, not public hosts. Firebase carries content-free wake-up signals, not message bodies. The owner-recovery flow is removed; ten consecutive wrong access codes still request a local wipe.

Emulator results and any unresolved acceptance checks must accompany the distributed candidate. Emulator FCM success is not proof of physical-phone Doze/OEM behavior, production readiness or complete security. Tadej merges PR #44 himself; this document does not authorize a merge or deployment.

## Candidate verification — 2026-10-18

- 317 Android unit tests, debug/test/release builds and debug lint pass. Earlier server checks passed 110 tests, typecheck and 12/12 live synthetic checks; no server code changed in this build step.
- All 19 selected device regression tests pass. The video-expiry harness now queries all accessible windows rather than relying on a sometimes-null active root; it still requires the Calculator gate, absent media window, stopped playback and empty plaintext view cache. Six focused repeats pass after dismissing an unrelated emulator System UI startup ANR that blocked Bob's first attempt.
- Controlled Firebase delivery passes in 3.3 seconds with the recipient process absent, periodic polling paused, a fresh callback and the exact new message in the active notification. No periodic job was forced. This remains a debug-build emulator check, not physical-phone release acceptance.
- The actual published 1.11 APK and this non-debuggable 1.12 release have the same verified signing certificate. In a separate offline API-36 emulator, `adb install -r` updates 12 → 13 without uninstalling or clearing storage. App UID and six private fixture files remain byte-identical immediately after installation. After launch, the original PIN still rejects a wrong code and accepts the correct one; the profile name, encrypted identity, database secret and synthetic file persist. The encrypted database opens through the upgrade. Existing message-history fixtures were not exercised by this particular upgrade check.
- Physical-phone update/data preservation, QR admission of the first real phone, Doze/OEM/mobile-network delivery and the separate production server remain pending. Selected instrumentation excludes fresh-enrollment and launcher-channel-migration cases on existing devices; no real data wipe was performed.

## Local build setup

Keep private inputs out of Git:

- `.local/mailbox.properties`: `url=https://...` for the intended delivery server.
- `app/google-services.json`: matching Firebase Android configuration for `com.relay.app`.
- `.local/signing.properties`: original key configuration, mode 600, for example:

```properties
storeFile=/absolute/path/to/original-signing.keystore
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

The keystore itself must remain private and gitignored, with a protected backup. Release signing never replaces `~/.config/.android/debug.keystore`; emulator debug/test builds retain their existing certificate. The Firebase service-account key is server-only and must never be packaged in the APK or given to someone merely signing it.

With JDK 17 and the configured Android SDK:

```sh
./gradlew --no-daemon --max-workers=2 \
  -Dorg.gradle.java.home="$JAVA_HOME" \
  -Pkotlin.compiler.execution.strategy=in-process \
  -PrelayPreview=true :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
./gradlew --stop -Dorg.gradle.java.home="$JAVA_HOME"
```

The signed output is `app/build/outputs/apk/release/app-release.apk`. Verify with Android SDK `apksigner verify --verbose --print-certs`, and check application ID, versionCode 13 and non-debuggable status using `aapt dump badging`/`aapt dump xmltree`. Share only the verified APK, installation notes and checksum—not signing inputs, server credentials or `.local/`.

Build and stop Gradle before starting emulators. The current API-36 emulator raises a requested 1024 MB to 2560 MB per VM; budget for the actual allocation, use two cores/headless SwiftShader, monitor host available memory, avoid `/tmp` for large artifacts and stop the VMs afterwards.
