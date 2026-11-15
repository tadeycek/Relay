# Calculator 1.16 preview verification — 2026-11-15

Version **1.16-preview**, versionCode **17**, application ID `com.relay.app`.
Release tag: `calculator-preview-1.16`. Scope: ordered media selection, large-file
encryption/resume, durable attachment queues, progress and chat refresh. The 1.15
loading artwork remains; voice/video calls are not implemented and iOS is deferred.

## Verified checks

| Check | Recorded result |
|---|---|
| Debug/release APK assembly and Android test APK compilation | Passed |
| Debug JVM unit tests | 337 passed; zero failures, errors or skips |
| Cloudflare Worker tests | 130 passed; zero failures |
| Worker bundle | Dry run passed; no deployment |
| Debug lint | Zero errors; 27 warnings and 13 informational findings |
| Offline Android instrumentation, API 35 emulator | 11 passed; zero failures or skips |
| Batch acceptance repeated alongside the background worker | Two additional runs passed |
| Signed release APK offline startup | Installed as an update; cold launch 974 ms, calculator visible, process alive, no startup crash markers |

The Android checks cover picker/session return and recreation, chat catch-up and
listener rebinding, ordered batch acceptance/retry without duplicate bubbles,
durable receive placeholders/jobs, rollback and rejected/deleted media cleanup,
and local encrypted-file compatibility/integrity. Networking was disabled; these
are not live delivery or real-phone acceptance results.

Large-media checks passed for a **generated 3 GiB stream encrypted and decrypted
with bounded buffers**, and a **181-second, 22,771,926-byte video** prepared without
changing its bytes, stored encrypted locally, read through random-access thumbnail
decoding and copied for viewing with checksum verification. The generated stream
was a local cryptographic test, **not a phone-to-phone 3 GiB video transfer**.

## APK identity

- Asset: `Relay-1.16-preview.apk`, 82,425,173 bytes.
- SHA-256: `f5f4fa32d0f260705eb48bb32debc4b69874c575c7f0e71de84abcce47023b3b`.
- Signature verification passed. Certificate SHA-256:
  `06a3d4109ba2322fcb2251f4c4ad97fed523eac1db00dc8bedb3201201de2da9`, matching 1.15.
- Minimum Android version: Android 8 / API 26; target API 36.

The emulator update/startup check does not establish retained data or migration
behavior on every physical phone.

## Deployment boundary and remaining checks

**The updated media server has not been deployed. The user will deploy it after
publication. Videos larger than 25 MiB require that server update and updated
recipient apps; files up to 25 MiB retain the existing legacy path.** The observed
live health reply was HTTP 200 / v1 with no media-v2 capability advertised.

Cloudflare documents 10 GB per SQLite Durable Object but only 5 GB total storage
per Free account. Available account quota must be checked before enabling large
transfers; see [server limits and deployment requirements](../server/cloudflare/README.md#free-plan-budget).
No server deployment was performed by these checks.

Still unverified: actual phone-to-phone large-video delivery, production 3 GiB
throughput/CPU/storage behavior, physical-device playback and picker behavior,
Doze/OEM restrictions, battery cost and live Firebase wake-up for this build.

## Local evidence

Results were recorded in `.local/media-fix-verification.json`, supported by:
`.local/android-final-checks.log`, `.local/backend-tests.log`,
`.local/backend-build.log`, `.local/android-final-instrumentation.log`,
`.local/android-final-concurrent-batch-repeat.log` and
`.local/android-release-smoke.log`. Debug unit XML and lint reports are under
`app/build/`. These local logs/build outputs are not release assets.
