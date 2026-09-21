# 06. Codebase impact

Baseline: about 8,300 lines of Kotlin under `app/src/main/java/com/relay/app`. DB version 9. Facts below come from
reading `AndroidManifest.xml`, `RelayCrypto.kt`, `RelaySecureSend.kt`, `SmsMessageParser.kt`, `QrContactCode.kt`,
`Contact.kt`, `MessageType.kt`, `README.md` and `plan.md`. Files not opened are marked "not reviewed".

## Keep as is (or nearly)

| Area | Files | Note |
|---|---|---|
| Encrypted DB | `data/db/RelayDbHelper.kt`, `RelayDbPassphrase.kt`, `DatabaseContract.kt` | SQLCipher stays. Migration 9 -> 10. |
| App lock | `ui/lock/AppLockGate.kt` | Unchanged. |
| Map / location UI | `ui/screens/map/*`, `ui/components/MapPreviewTile.kt`, `SendPinBottomSheet.kt` | Unchanged; sending path swaps underneath. |
| Theme, nav, components | `ui/theme/*`, `ui/navigation/NavGraph.kt`, `ui/components/*` | Unchanged. |
| Crypto | `crypto/RelayCrypto.kt`, `RelayFileCrypto.kt` | Keep as inner layer (see 04 section 4). |
| Models | `data/model/*` | Small additions below. |
| Media compression | `mms/MediaCompressor.kt` | Keep, relax the 900 KB / 30 s carrier caps, move package to `media/`. |
| Pin expiry | `sms/PinExpiryReceiver.kt` | Keep local purge; move package. |
| Key rotation trigger | `sms/KeyRotationReceiver.kt` | Keep the schedule; the broadcast now goes through the transport. |
| Live location | `sms/LocationShareService.kt` | Keep, but throttle updates and send via new transport. Move package. |

## Change

| File | Change |
|---|---|
| `data/model/Contact.kt` | Add `nostrPubkey`; make `phone` nullable or optional label; keep `publicKey`, `pendingPublicKey`, `signingPublicKey`, `trustLevel`. `hasRelay` becomes always true for paired contacts. |
| `util/QrContactCode.kt` | Version 3 with `NPUB` and optional `RELAYS`; keep decoding v1/v2 for existing paired contacts. |
| `sms/QrContactExchange.kt`, `ui/screens/qr/QrExchangeScreen.kt` | Pairing stores `npub`, no SMS key handshake triggered. (Not reviewed in detail.) |
| `sms/RelaySecureSend.kt` | Replaced by `MessagingService.send(...)` calling the Transport. The "bootstrap key exchange over SMS" branch disappears. |
| `util/SmsMessageParser.kt` | Superseded by a JSON payload codec. Keep the old regexes only if legacy SMS messages must still display from the local DB. |
| `data/repository/*` | Contact/Message/Group repositories: swap phone-number lookup for `npub` lookup; add outbox and message id handling. (Not reviewed in detail.) |
| `ui/screens/chat/*`, `contacts/*` | Show `npub` fingerprint instead of phone number; delivery status from the outbox. (Not reviewed in detail.) |
| `ui/screens/settings/*`, `util/RelayPreferences.kt` | Add relay list, Tor toggle and status, background-connection settings. |
| `RelayApplication.kt` | Start/stop the messaging foreground service; keep existing alarm scheduling. |
| `MainActivity.kt` | Remove default-SMS-app prompts/eligibility flow (Phase 4 work). (Not reviewed in detail.) |
| `AndroidManifest.xml` | See "Manifest" below. |
| `app/build.gradle` | Add nostr SDK dependency (and OkHttp if the SDK does not bring its own socket layer), Tor library later. Remove nothing that is still used. |

## Delete (after the new transport is proven)

| File / element | Why |
|---|---|
| `sms/SmsSender.kt`, `sms/SmsReceiver.kt`, `sms/HeadlessSmsSendService.kt` | SMS transport gone. |
| `mms/MmsSender.kt`, `mms/MmsReceiver.kt`, `mms/MmsPduBuilder.kt` | MMS transport gone. |
| `util/SmsMessageParser.kt` (if no legacy display needed) | Replaced by the payload codec. |
| Manifest: `SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS`, `READ_MMS` | Final app should ask for no SMS permissions. |
| Manifest: SENDTO intent filter, `HeadlessSmsSendService`, `SMS_RECEIVED`/`SMS_DELIVER`/`WAP_PUSH_*` receivers | Only existed for default-SMS eligibility. |

## Manifest additions

- `FOREGROUND_SERVICE` and the type-specific permission for the chosen service type (likely
  `FOREGROUND_SERVICE_REMOTE_MESSAGING` on Android 14+; verify against current docs).
- `POST_NOTIFICATIONS` already present. `INTERNET` and `ACCESS_NETWORK_STATE` already present.
- Optionally `RECEIVE_BOOT_COMPLETED` to restart the connection after reboot.
- Review `allowBackup="true"`, `data_extraction_rules.xml` and `backup_rules.xml` to make sure the Nostr key material
  and DB passphrase never leave the device through backup.

## Database changes (DB v10)

- `contacts`: add `nostr_pubkey TEXT`, relax `phone` uniqueness/NOT NULL as needed.
- `messages` / `group_messages`: add `msg_uid TEXT` (payload id), `delivery_state`, remove reliance on SMS provider ids.
- New `outbox` table: payload, recipient, attempts, next_attempt_at, state.
- New `relays` table (optional): url, role (inbox/outbox), last_ok_at, failures.
- Migration must keep existing paired contacts and history. Existing SMS contacts stay readable but become
  "legacy, cannot receive new messages" until re-paired by QR v3.

## Rough size estimate (for planning, not a promise)

New code is concentrated in a `transport/` package (interface, Nostr implementation, relay pool, outbox, payload codec,
foreground service) and a handful of UI additions. The rest is deletion and re-wiring. The UI and crypto layers are
mostly untouched.
