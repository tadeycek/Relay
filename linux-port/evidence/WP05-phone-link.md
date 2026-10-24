# WP05 phone link evidence — phone half of "link a computer"

Status: **implemented and unit-verified; not device-verified.** Date 2026-10-24, branch
`feat/linux-port`, base `2b11c17`. The implementing agent ran out of provider credit mid-task and
never wrote its own evidence; everything below was re-run by the parent.

## What exists

`app/src/main/java/com/relay/app/linking/`:

- `PhoneLinkFlow.kt` — pure decision flow (no Android types, no clock, no network).
- `LinkBundleMapping.kt`, `PhoneLinkBundleSource.kt` — build a `LinkBundle` from identity, contacts,
  groups and settings. No message history.
- `AndroidLinkHpke.kt` — HPKE seal over Tink. `decryptWith` is an explicit unsupported error, not a
  fake success.
- `MailboxLinkTransport.kt`, `PhoneLinkCoordinator.kt` — signed requests through the existing mailbox
  auth path (admin key after migration, shared identity key before it).
- `LinkedComputersScreen.kt`, `LinkedDeviceList.kt` — Account → Linked computers list, link, remove.
- Changed: `AccountScreen.kt`, `NavGraph.kt`, `RelayCrypto.kt`, `MailboxTransport.kt` (small signed
  request method), `QrScanner.kt` (the camera/import scanner was split into a shared `RawQrScanView`
  so "Link a computer" reuses it).

## Flow as coded

1. `LinkQr.decode` the scanned text. A legacy v1 code or a damaged code is refused before anything is
   sealed or sent.
2. Build the bundle, seal it (HPKE) to the desktop's ephemeral key from the QR.
3. `PUT /v1/link/<sha256(code)>`.
4. Enrol the desktop: `POST /v1/devices?device=primary`, `{device, label, auth_pubkey}`.
5. Report success. The slot is deliberately **not** deleted; the desktop fetches then completes it.
6. If step 4 fails, `DELETE /v1/link/<slot>` with the proof header so no sealed bundle is left behind.
   If that rollback also fails the result says `slotLeftBehind` (it expires within ten minutes).
7. One attempt at a time: a second call returns `ALREADY_LINKING` without touching the network.

## Contract fix found while integrating

`LinkQr` originally carried a **16-hex** device id; the real server accepts only 32 lowercase hex for a
linked device (`LINKED_DEVICE_ID`) and answers `400 bad_request` otherwise. Found by the desktop
end-to-end test against the real Worker, fixed in `LinkQr` (32 hex) with both halves' tests updated.

## Verification (parent run, `--rerun-tasks`)

```sh
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 ANDROID_HOME=/home/tadej/Android/Sdk
./gradlew --no-daemon -Dorg.gradle.java.home=$JAVA_HOME --console=plain -q --rerun-tasks \
  :core:test :desktop:test :app:testDebugUnitTest :app:assembleDebug
```

- `:app:testDebugUnitTest`: **187 tests, 0 failures, 0 skipped** (150 before; new: `PhoneLinkFlowTest`
  22, `LinkBundleMappingTest` 11, `LinkedDeviceListTest` 4). `:app:assembleDebug` succeeds.
- Failure matrix covered by `PhoneLinkFlowTest`: success, legacy QR refused, malformed QR refused, busy
  slot, expired slot, enrolment failure triggers slot cleanup, duplicate attempt, oversize bundle, no
  message bodies in the bundle.

## NOT RUN

- No emulator or phone was started; no real QR was scanned with a camera.
- The Compose screens and the Android glue are compile-checked and unit-tested at the logic layer only.
- **`QrScanner.kt` was refactored.** Contact pairing by scan uses the same code path and was not
  re-tested on a device. Do that before any release.
- Linking does not itself migrate the identity to the admin key; enrolment signs with the shared key
  until migration happens elsewhere. Whether linking should force migration (gate G1) is open.
- Remove-device (`DELETE /v1/devices/<id>`) UI is not device-tested.
- 1.15 contact interop (WP05-AC6) is not run.
