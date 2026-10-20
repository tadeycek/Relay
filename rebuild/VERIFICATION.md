# Calculator 1.15 preview verification — 2026-10-20

Version 1.15-preview, versionCode 16, application ID com.relay.app.
Includes PR #56: all five supplied loading images, enhanced artwork in the 1.5%
slot, and proportional full-screen cropping. Selection probabilities and the
three-second duration are unchanged. Delivery behavior is inherited from 1.14.

## Build and host checks

- `rebuild/dev/build.sh -PrelayPreview=true assembleRelease assembleDebug assembleDebugAndroidTest testDebugUnitTest testReleaseUnitTest lintDebug lintRelease --console=plain`: BUILD SUCCESSFUL, exit 0.
- Debug and release JVM suites: 329 tests each; zero failures, errors or skips.
- UnlockLoadingPolicyTest verifies all 10,000 selection tickets (6000/2000/1300/550/150), slot boundaries and the three-second duration.
- Debug and release lint: zero errors/fatal findings, 20 warnings and 13 informational findings each. Existing warnings remain.
- Host schema/migration/retention checks (`rebuild/dev/check-group-schema.py`): passed.
- Host history ordering/window/anchor/page checks (`rebuild/dev/benchmark-history.py`): passed. Synthetic host SQLite performance is not phone/network latency.
- Android test APK compiled. No Android instrumentation tests ran for this release.

## APK checks

- Release APK is non-debuggable; manifest confirms com.relay.app, version 1.15-preview and versionCode 16.
- Signature verification passed. Certificate SHA-256 is 06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9, matching the checksum-verified published 1.14 APK.
- Mailbox URL and all six Firebase client string resources match that previous APK.
- All five packaged loading pictures match the source pixels exactly after Android resource optimization.
- APK SHA-256: 6d8d375e6ae48cac9781b5572465ac2aab8890b8a101f17f897f7f910107055f.

## Remaining device checks

ADB reported no attached phone or emulator. Phone installation over an existing
version, retained data, loading-screen cropping/appearance, memory behavior,
real-phone delivery latency and live Firebase wake-up remain unverified.
Earlier release emulator results are not new acceptance evidence for 1.15.
A matching certificate and higher version code do not prove on-phone migration.
