# Relay

Relay is an Android messenger with map-based location sharing. Messages travel over the **internet** as end-to-end encrypted Nostr private messages (NIP-17) through public relays, so there is no server of our own, no accounts, no phone number, and no SMS/MMS permissions. Contacts are added by scanning each other's QR codes in person.

> **Status:** rebuilt from an SMS/MMS app (see `rebuild/`). It compiles, lints without errors, and has 92 passing JVM unit tests, but **it has not been run on a device or against live relays**. Treat everything that touches the network, the Keystore or Android background behaviour as unverified. See `rebuild/PROGRESS.md` for exactly what is and is not verified.

## Features and how they work

### Messaging over the internet
- Outgoing messages are written to a **durable outbox** (SQLite) and sent by the transport with exponential backoff, up to 7 days. Chat bubbles show queued / sent / failed / read.
- `NostrTransport` wraps each message per NIP-17 (rumor → seal signed by you → gift wrap with a throwaway key) and publishes it to the recipient's inbox relays (their kind 10050 list, else relays from their QR code and your own list). Relays see an encrypted blob for a public key, not who sent it.
- Incoming messages arrive over a long-lived relay subscription, are unwrapped and authenticated (the seal signature proves the sender's key), de-duplicated persistently, then stored.
- **Offline delivery:** relays hold messages until the recipient reconnects. How long free public relays keep them is **unmeasured** (`rebuild/07-phases.md`, Phase 0, has a debug tool for it).
- **Closed-app delivery:** a foreground service (`ConnectionService`) keeps the relay connection alive, with a 15-minute WorkManager poll as a safety net. Nostr relays cannot wake a sleeping phone, so this costs battery and shows a persistent low-priority notification; it can be turned off in Settings.

### Pairing and trust
- Each install has a Nostr identity (secp256k1) plus the Tink keys below. **My code** shows a QR containing your name, Nostr key, Tink public keys and up to 3 relay hints — no phone number.
- Scanning a code trusts those keys immediately and marks the contact **verified (met in person)**. Contacts who only know your key and message you first appear as **unverified**: they get no automatic media download and can never trigger an automatic location share.
- Old-style QR codes from the SMS version are rejected; contacts from that era stay visible with their history but must re-pair by QR.

### Map, pins and location requests
`MapScreen` (OSMDroid) is the home screen. Dropping a pin sends a `TYPE:LOCATION` body (label, expiry) to a contact or group. Location requests can be shared automatically only for **Trusted** contacts, or for **verified** contacts if auto-approve is on; otherwise you get a Share/Decline notification. Do-Not-Disturb hours and "who can request my location" apply.

### Photos and video
Files are compressed, encrypted with a fresh AES-256-GCM key, and the ciphertext is uploaded to a **Blossom** media server; only a small reference (URL, SHA-256, key, type) travels in the message. The receiver checks the hash before decrypting, stores the file encrypted at rest, and only downloads from verified contacts. Limits: images 3 MB / 2048 px, video 12 MB / 60 s. If an upload fails the user must retry. The default Blossom servers are **unverified**.

### Groups
Lightweight, local groups; sending fans out one message per member (NIP-17 discourages groups beyond ~10). There is no group membership authentication.

### Tor (optional)
Settings → Privacy → "Hide my IP from relays (Tor)" routes relay and media connections through Orbot's SOCKS proxy. It **fails closed**: with Tor on and Orbot not running, nothing is sent. Map tiles are still fetched directly (documented in the app). Off by default. Embedded Tor is not built.

## Security architecture

Layers, from outside in:

1. **Transport (Nostr NIP-17/NIP-44/NIP-59).** Content and sender are hidden from relays; relays still see the recipient's public key, message size/timing and the connecting IP (unless Tor is on).
2. **Relay's own end-to-end layer (Tink).** A long-term X25519/HPKE keypair per install (private key wrapped by an Android Keystore master key) encrypts each body once the contact's key is known; a separate never-rotated Ed25519 signing key authenticates key rotations. The encryption key auto-rotates every 30 days (manual rotate in Settings; retired key kept 7 days). There is **no Double Ratchet**, so a stolen key exposes up to ~30 days of history, and NIP-17 has no forward secrecy of its own.
3. **At rest.** `relay.db` is SQLCipher-encrypted (passphrase in Keystore-backed `EncryptedSharedPreferences`); received media is encrypted with a Tink AEAD keyset; the Nostr secret key is in `EncryptedSharedPreferences`. All of these, plus the media directory, are excluded from cloud backup and device transfer.
4. **App lock.** Optional biometric/PIN gate on cold start and resume.

Threat-model notes: metadata (who talks to whom, when, message size) is partly visible to relays; a compromised phone defeats all of it; scanning the wrong QR code securely connects you to the wrong person. This is a hobby/friends project, not an audited secure messenger.

### Known gaps
- **Never run on a device or against real relays.** Relay retention, foreground-service survival through Doze/OEM killers, battery cost, and real Tor behaviour are unmeasured.
- Outgoing media is kept as a plaintext cache file (needed to display and to upload); only received media is encrypted at rest.
- No chat-history export or backup: a phone swap loses history and identity (keys are Keystore-bound), and contacts must re-pair.
- Media uploads are not retried automatically.
- Public relay/Blossom defaults are placeholders whose policies were not verified.
- No `.onion` relays are bundled; relay list editing UI is not built (defaults/preferences only).
- Group sends have no membership authentication or rate awareness.
- No license file yet.
- No instrumented (on-device) tests; the JVM tests cover the pure logic (codecs, crypto helpers, policies, download checks), not Android/Keystore/network integration.

## Tech stack
- Kotlin, Jetpack Compose (Material 3), Android SDK 26+ (compile/target 36), JDK 17
- `org.rust-nostr:nostr-sdk-kmp-android:0.44.8` (alpha-quality Kotlin bindings; wrapped behind `com.relay.app.transport`)
- SQLCipher, Google Tink, WorkManager, OSMDroid, CameraX + ML Kit (QR), zxing
- Kotlin coroutines; no backend, no analytics

## Project structure (`app/src/main/java/com/relay/app`)
- `transport/` — `Transport` interface, `NostrTransport`, Nostr identity, payload codec, outbox policy, dedupe, Tor route policy
- `messaging/` — runtime (connect, receive, drain outbox), incoming handler, outbox worker, foreground service, poll worker, notifiers, policies
- `media/` — encrypted media protocol, AES-GCM helper, Blossom client, send/receive
- `crypto/` — Tink E2E layer and at-rest file crypto
- `data/` — models, repositories, SQLCipher helper and schema (DB v12)
- `ui/` — screens (map, chat, contacts, QR, settings), components, theme, navigation
- `sms/` — historical package name; now holds the outbox entry point (`RelaySecureSend`), QR pairing, location-share service, alarm receivers
- `util/` — preferences, message-body protocol (`SmsMessageParser`, historical name), QR codec

## Build and run
The build files pin `org.gradle.java.home=/opt/android-studio/jbr`; on other machines override it (`-Dorg.gradle.java.home=<JDK 17>`).

1. JDK 17, Android SDK platform 36 (Android Studio provides both).
2. `./gradlew assembleDebug` (or `rebuild/dev/build.sh :app:assembleDebug` under WSL, see `rebuild/09-dev-environment.md`).
3. `./gradlew testDebugUnitTest` runs the JVM unit tests.
4. Two real phones are needed to test a conversation; no SIM is required. A debug-only relay probe is at `com.relay.app/.lab.RelayLabActivity` (launch with `adb shell am start`).

The debug APK is ~84 MB because it bundles native libraries for four ABIs; use ABI splits or an AAB for release.

## Current limitations
- Background execution and battery optimisation vary by OEM.
- `compileSdk 36` on AGP 8.3.2 may print compatibility warnings.

## License
Add your preferred license (MIT/Apache-2.0/etc.) before public release.
