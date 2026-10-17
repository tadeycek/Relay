# Calculator 1.4 preview verification — 2026-10-17

This revision changes Android's registered application identity to the requested
Viber name and supplied purple phone icon. MainActivity explicitly retains the
Calculator home-screen label and calculator launcher icon. Package com.relay.app
and signing identity are unchanged; this is still the Relay messaging build.

## Verified

- Preview APK build and lint completed successfully. Version 1.4-preview, code 5.
- The compiled APK reports application-label Viber. Every application-icon density
  entry resolves to notification_avatar_badge.png, not the calculator launcher icon.
- The compiled launchable MainActivity reports label Calculator and its calculator
  adaptive icon, independently of the application-level identity.
- Pixels decoded from the packaged application icon exactly match the supplied
  purple phone image. Application roundIcon points to that same phone image.
- APK signature verification passes and its certificate matches 1.3, supporting an
  in-place update without changing package identity or clearing existing app data.
- The emulator device/profile listings remain empty. No emulators or virtual-device
  profiles were recreated for this change. No physical phone is connected to adb,
  so notification appearance on Samsung was not checked at runtime in this revision.

Install the new APK over the existing app, dismiss the old preview and receive a new
message to check the source name and application badge. Android settings and installer
will also show Viber; the launcher remains Calculator. Other notification behavior,
including tap-to-dismiss, is unchanged from 1.3.

The broader Android/JVM checks in [VERIFICATION-1.3-preview.md](VERIFICATION-1.3-preview.md)
and earlier reports are historical and were not repeated for this manifest/resource
change. Lint findings and physical-phone/OEM styling remain limitations. This remains
a debug-signed test preview, not a claim of no remaining bugs or an independent audit.
