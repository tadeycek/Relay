# Relay

Relay is an Android SMS/MMS messenger with map-based location sharing. It's a native client for carrier SMS/MMS — no accounts, no backend server, no push infrastructure. Two people with the app (or even just one — Relay talks to any phone number) exchange plain carrier text messages, and Relay layers a small text protocol on top to add map pins, read receipts, and location requests.

## Features and how they work

### SMS chat
Standard one-to-one texting. Outgoing messages go through `SmsSender` (`android.telephony.SmsManager`). Incoming messages are caught by `SmsReceiver`, a manifest-registered `BroadcastReceiver` on `SMS_RECEIVED_ACTION` with `android:priority="999"` so Relay sees the message before the stock Messages app. Each inbound SMS is matched to a contact by phone number (see below), written to a local SQLite `messages` table, and a local broadcast (`com.relay.app.NEW_MESSAGE`) tells any open `ChatScreen` to refresh.

### MMS support (images, short video)
MMS is sent by hand-building the binary PDU itself — `MmsPduBuilder` writes a raw OMA MMS 1.3/1.2 `m-send-req` (message type, transaction ID, `To`/`From` headers, a `multipart/related` body with a tiny generated SMIL layout, the attachment part, and an optional text part), then hands the resulting byte blob to `SmsManager.sendMultimediaMessage`. Before that, `MediaCompressor` re-encodes images to JPEG (downsampled to fit under 1280px, quality stepped down from 85 until the file is under 900 KB) and rejects video over 30 seconds or 900 KB, since carrier MMS size limits are tight and non-negotiable.

Incoming MMS arrives as a WAP push (`WAP_PUSH_RECEIVED`, `application/vnd.wap.mms-message`). `MmsReceiver` waits 3 seconds for Android's own MMS stack to finish writing the message into the system `content://mms` provider, then queries it directly for the sender address and the first image/video part, copies that part into the app's cache dir (so Relay owns a stable file it can reference later), and inserts a message row.

### Map-first UI for location pins
`MapScreen` (OSMDroid) is the app's home screen. Dropping a pin opens `SendPinBottomSheet`, where you pick a contact or group, an optional label, and an expiry. On send, the pin becomes a structured SMS string (see protocol below) sent via the normal SMS path — location pins are just specially-formatted text messages, not a separate transport.

### Pin metadata (label + expiry) and pin history
Labels (≤30 chars) and expiry (`1hr`/`6hr`/`24hr`/`48hr`/`never`, `PinExpiry` enum) are encoded directly into the SMS body and parsed back out on receipt. `expiry_at` is stored as an absolute epoch-ms column on the message row. `RelayApplication` schedules a repeating `AlarmManager` alarm (every 15 min) that fires `PinExpiryReceiver`, which calls `MessageRepository.deleteExpiredPinsSync()` to purge rows past their expiry. `PinHistoryScreen` lists all location-type messages ever received/sent (`getPinHistory()`), while the map itself only shows currently-unexpired pins (`getSavedPins()`).

### Contact trust levels (Trusted / Ask / Blocked)
Each contact has a `trust_level` column (`ContactTrustLevel`: `TRUSTED`, `ASK`, `BLOCKED`). Tapping a contact's trust badge in `ContactsScreen` cycles it. This gates incoming `TYPE:LOCATION_REQUEST` messages in `SmsReceiver.handleLocationRequest`: `BLOCKED` contacts are silently dropped, `TRUSTED` contacts (or anyone, if "auto-approve" is on) get their location shared automatically, everyone else gets a share/decline notification.

### Do Not Disturb window for location requests
A start/end hour pair in `RelayPreferences` (`SettingsScreen`). `SmsReceiver.isWithinDndWindow` checks the current local hour against the window (handling wraparound, e.g. 22→7) and suppresses the location-request notification/auto-share entirely during that window.

### Read receipts
When `readReceipts` is enabled, opening a chat sends a `TYPE:READ_RECEIPT|MSG_ID:<timestamp>` SMS back to the sender. The receiving side's `SmsReceiver` parses it and calls `markReadUpToSync`, which stamps `read_at` on all of that contact's own sent messages with a timestamp ≤ the receipt (plus a 2-minute slack window to absorb clock skew) — it's a "read up to this point" cursor, not a per-message ack.

### Lightweight groups
Local-only construct: a `groups` table and a `group_members` join table. There is no group SMS concept at the protocol level — sending to a group just sends the same individually-formatted SMS to every member's phone number in a loop (`MapViewModel.sendPinToGroup`) and mirrors the outcome into a `group_messages` table for the shared group chat view. Incoming messages are also fanned into any group the sender belongs to (`SmsReceiver`, lines ~102–138) via a raw join query.

## The SMS wire protocol

Non-Relay messages (bank OTPs, carrier texts, messages from people without labels) are just plain text and render as ordinary chat bubbles. Relay-specific messages are recognized by a `TYPE:` prefix, parsed with regexes in `SmsMessageParser`:

| Format | Meaning |
|---|---|
| `TYPE:LOCATION\|LAT:...\|LNG:...\|EXPIRY:...\|LABEL:...` | A shared pin. `EXPIRY`/`LABEL` are optional. |
| `TYPE:LOCATION_REQUEST` | "Please share your location." |
| `TYPE:LOCATION_DECLINED` | Response to a request that was declined. |
| `TYPE:READ_RECEIPT\|MSG_ID:<timestamp>` | Read-up-to cursor. |

Because this rides on ordinary SMS, it is plain text end to end — see **Security notes** below.

## Data storage

Everything lives in one on-device SQLite database (`relay.db`, opened via a hand-written `SQLiteOpenHelper`, no Room/ORM): `contacts`, `messages`, `groups`, `group_members`, `group_messages`. Schema changes are plain `ALTER TABLE` migrations gated on `DB_VERSION` (currently 9). Media files (compressed images/videos) are stored as regular files under the app's cache dir and referenced by path in the `media_uri` column.

## Tech stack

- Kotlin, Jetpack Compose (Material 3)
- Android SDK 26+ (compile/target 36), JDK 17
- SQLite via `SQLiteOpenHelper` (no Room)
- Kotlin Coroutines + `StateFlow` for UI state
- OSMDroid for map rendering
- No backend, no analytics, no network calls except OSM tile fetches and carrier MMS upload

## Project structure

`app/src/main/java/com/relay/app`

- `ui/` — screens (`chat`, `contacts`, `map`, `settings`), shared components, theme, navigation graph
- `data/` — models, repositories, SQLite contract + helper
- `sms/` — SMS `BroadcastReceiver`/sender, location-request/DND logic, pin-expiry alarm
- `mms/` — hand-rolled MMS PDU builder, sender, receiver, media compression
- `util/` — `SharedPreferences` wrapper (`RelayPreferences`), SMS protocol parser

## Required permissions

`SEND_SMS`, `RECEIVE_SMS`, `READ_SMS`, `RECEIVE_MMS`, `READ_MMS`, `ACCESS_FINE_LOCATION` (+ coarse/background), `CAMERA`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`. Relay needs to be set as the device's default SMS app to receive messages reliably.

## Build and run

1. Clone the repo, open in Android Studio, let Gradle sync.
2. `./gradlew assembleDebug` or run from the IDE.
3. APK lands at `app/build/outputs/apk/debug/app-debug.apk`.
4. A physical device is strongly recommended — SMS/MMS don't work meaningfully on the emulator.

### Sharing with friends (quick path)
Build the debug APK, send it directly, have them enable "install unknown apps," install, grant permissions, and set Relay as the default SMS app. For real distribution, sign a release build and use Play internal testing.

## Security architecture

Relay encrypts on three separate layers, each addressing a different threat:

**1. End-to-end message/media encryption (transport).** Each install generates one long-term X25519/HPKE keypair (via Google Tink's hybrid encryption, `com.relay.app.crypto.RelayCrypto`), with the private key stored in SharedPreferences wrapped by an Android Keystore-backed master key. Public keys are exchanged opportunistically over SMS (`TYPE:PUBKEY|KEY:...`) the first time you message a new contact; once both sides know each other's key, every structured payload — text, location pins, location requests, read receipts, MMS media — is hybrid-encrypted (`TYPE:ENC|CT:...` for SMS, a magic-byte-prefixed envelope for MMS parts) before it becomes the wire body, so carriers and anything downstream of them see only ciphertext. There's no shared ratchet state to desync, since Tink's HPKE mode generates a fresh ephemeral key per message — a deliberate tradeoff given SMS can arrive out of order or duplicated. Until a contact's key is known, messages to them still go out in plain text (opportunistic encryption, not enforced).

- **Sender authentication.** Tink's HPKE here is *base mode*: it proves a message was encrypted to your public key, not who encrypted it. Since your public key is visible to anyone you've ever exchanged a handshake with, and SMS sender IDs are trivially spoofable, that gap would otherwise let anyone who knows your key forge a message that decrypts cleanly while appearing to be from a different, already-trusted contact. To close it, each device also holds a long-term Ed25519 signing keypair; every outgoing encrypted SMS is signed with it (`TYPE:ENC|CT:...|SIG:...`), and the receiver verifies that signature against the contact's stored signing key before trusting the message as authentically theirs. A message that decrypts but fails or lacks that check is still shown (not dropped), just visually marked as unverified — this covers a legitimate contact who paired before this feature existed and hasn't re-paired/rotated since. **This signing check currently only covers SMS text (`RelaySecureSend`/`SmsReceiver`); MMS media (`MmsSender`/`MmsReceiver`) shares the same underlying encryption and has the identical unauthenticated-sender gap, not yet closed.**
- **Forward secrecy.** There's no Double Ratchet — decryption always uses the current long-term identity private key, so if that key is ever extracted (lost/seized device, forensic recovery), every message encrypted to it since the last rotation becomes retroactively readable. To bound that exposure, the encryption keypair (not the signing keypair, which is a long-term anchor and never rotates) auto-rotates every 30 days, with a manual "Rotate encryption key now" available in Settings; a retired key is kept for a 7-day grace period to decrypt messages already in flight, then permanently deleted. **This means: a key compromise exposes at most the last ~30 days of a conversation, not its entire history — but it is not the same guarantee as a real ratchet, and anyone using this for something where even a 30-day exposure window is unacceptable should not treat this as forward-secure messaging.**

**2. Encrypted local storage (at rest).** `relay.db` is opened through SQLCipher (`net.zetetic:android-database-sqlcipher`) instead of the platform SQLite implementation, with a random 256-bit passphrase generated once and stored via Keystore-backed `EncryptedSharedPreferences` (`RelayDbPassphrase`). Received MMS media is separately encrypted at rest with a Tink AEAD keyset (`RelayFileCrypto`) as it's cached; display code (Coil, video thumbnailing) decrypts a short-lived plaintext copy on demand rather than ever holding a permanent plaintext copy in the primary cache. Note: media *we send* is intentionally left as a plaintext cache copy (see "known gaps" below) — only received media gets this treatment.

**3. App lock.** If enabled in Settings and the device has biometrics/PIN/pattern configured, `MainActivity` gates all content behind a `BiometricPrompt` challenge on cold start and whenever the app resumes from the background.

**Backup scoping.** `relay.db` and all the SharedPreferences files holding key material/passphrases are explicitly excluded from Android's auto backup (`data_extraction_rules.xml` / `backup_rules.xml`), so none of it round-trips through cloud/ADB backup.

### Known gaps / not attempted
- **MMS media has no sender authentication.** See above — the SMS text path is signed and verified, MMS media is not yet.
- **Outgoing media is not at-rest encrypted.** Encrypting the same file `MmsSender` reads to transmit would have meant sending ciphertext keyed to a device-local secret nobody else has — silently breaking every sent photo. Fixing this properly would need a separate plaintext-for-send / ciphertext-for-cache pair of files; not done here.
- **No delivery receipts.** `RelaySecureSend`/`SmsSender` report whether the *send call* threw, not whether the SMS/MMS was actually delivered (would need `sentIntent`/`deliveredIntent` `PendingIntent`s).
- **No chat-history export or backup-loss warning.** `relay.db` (which holds both key material and message history in the same file) is deliberately excluded from Android's auto backup so the keys never leak into a cloud/ADB backup — but that means a phone swap or reinstall silently loses all message history too, with no export path and no warning anywhere in the app that this will happen.
- **Group sends have no rate-limit awareness.** Sending to a group fans out one individual SMS per member per message/pin with no throttling — this will start hitting carrier spam-filtering/throttling at group sizes above a handful of members.
- **No license file** — still needs one before any public release (see below).
- **No automated tests** exist anywhere in the tree (no `app/src/test`, no `app/src/androidTest`) — not for the hand-rolled MMS PDU builder, the SMS wire-protocol parser, or any of the crypto/DB code, including the sender-authentication and key-rotation logic added most recently.
- **This has never been compiled, not once, including everything described above.** It was written without Android SDK/JDK access throughout — every session of work on it, not just the original one — so treat all of it, especially the newest crypto/DB changes, as unverified until it's actually built and run on a device. In particular, verify the exact `net.sqlcipher.database.SQLiteOpenHelper` password-based `getReadableDatabase`/`getWritableDatabase` overload signatures (implemented from memory, not checked against the library), and the DB migration path from an older installed version (`DB_VERSION` has moved 7→9 across recent sessions; only tested by code inspection, not by actually running an upgrade).

This is still a hobby/friends project, not an audited secure messenger — it no longer sends everything in the clear and no longer lets a spoofed sender impersonate a trusted contact, but it has not been proven to work at all yet.

## Current limitations

- MMS behavior varies by carrier/device — there's no universal guarantee of size limits or delivery.
- `compileSdk 36` on AGP 8.3.2 may print compatibility warnings.
- Background execution / battery optimization behavior varies by OEM (affects the pin-expiry alarm and location-share service).

## License

Add your preferred license (MIT/Apache-2.0/etc.) before public release.
