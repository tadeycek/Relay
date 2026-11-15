# Calculator 1.16 preview — installation notes — 2026-11-15

Release tag: `calculator-preview-1.16`. APK: `Relay-1.16-preview.apk`.
Application ID: `com.relay.app`. Version: **1.16-preview**. Version code: **17**.
Minimum Android version: Android 8 / API 26.

Install over the existing app and choose **Update**. Do not uninstall or clear
storage. Stop if Android reports a signature mismatch or downgrade. The signed
release APK installed as an update and opened the calculator on an offline API 35
emulator; physical-phone upgrade/data preservation remains unverified.

This update adds ordered multiple-photo/video selection, chat catch-up after
picker/background return, durable media retries and preparation/save/upload
progress about every five seconds. Videos can be up to 3 GiB without an artificial
duration cutoff; queued outgoing and received media are encrypted at rest.
The 1.15 loading artwork, calculator entry and ten-minute session are retained.

**Before sending videos larger than 25 MiB: deploy the updated media server and
update the receiving apps. The user will deploy the server separately after
publication; it has not been deployed as part of this release preparation.**
The APK alone cannot enable large transfers on the existing server. Files up to
25 MiB continue through the legacy path. If a large upload already failed because
the server needs updating, delete its failed bubble and resubmit after deployment.
Check account storage capacity first: Free accounts have a 5 GB aggregate allowance.
See [server capacity and deployment requirements](../server/cloudflare/README.md#free-plan-budget).

Voice/video calls are not implemented. iOS remains deferred.

Signature verification passed, using the same identity as 1.15. Certificate SHA-256:
`06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`.
APK SHA-256:
`f5f4fa32d0f260705eb48bb32debc4b69874c575c7f0e71de84abcce47023b3b`.

Verify the downloaded asset with `sha256sum Relay-1.16-preview.apk` and compare the
result above. See [verification](VERIFICATION.md) for the 337 debug unit tests,
130 server tests, 11 offline Android checks, two repeated batch runs and signed
release startup check. The local 3 GiB cryptographic stream test does **not** prove
phone-to-phone 3 GiB delivery or production capacity. Signing keys, local settings,
test logs and build caches are excluded from release assets.
