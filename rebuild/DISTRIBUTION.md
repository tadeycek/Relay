Calculator 1.15 preview — installation notes — 2026-10-20
=======================================================

Install the APK over the existing app and choose Update. Do not uninstall or clear
storage. Stop if Android reports a signature mismatch or downgrade.
Application ID: com.relay.app. Version: 1.15-preview. Version code: 16.
Minimum Android version: Android 8 / API 26.

This update replaces all five unlock loading placeholders with the supplied images.
The enhanced artwork occupies the rare 1.5% slot. Selection chances remain
60%, 20%, 13%, 5.5%, 1.5%; the screen lasts three seconds. Pictures fill the screen
without stretching, cropping excess edges. Disable it in Account > Appearance >
Three-second loading screen.

The 1.14 delivery changes and removal of active/last-active tracking remain in place.
Existing pairing, server membership, seven-day local retention and app-lock behavior
still apply. Internet audio calling is not implemented.

The release uses the original signing identity. Certificate SHA-256:
06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9
A matching certificate and increasing version code support installation as an update;
actual phone upgrade/data preservation and loading-screen appearance remain untested.

See 02-TEST-RESULTS.txt for checks performed on this build. No phone or emulator was
connected for this release. Real-phone delivery latency and live Firebase wake-up
remain unverified. Previous release device checks were not rerun for 1.15.

The ZIP includes the APK, source, installation notes, verification results, build
provenance and SHA-256 checksums. Verify with sha256sum -c 04-SHA256SUMS.txt inside
the extracted folder. Signing keys, local settings and build caches are excluded.
