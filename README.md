# Relay

## Calculator entry and Control Panel

The Android launcher is **Calculator**, with the supplied calculator icon. It opens a working basic
calculator with decimal arithmetic, precedence, parentheses, percentages, clear and delete. On first
use, enter **four digits, then +** to set the private entry code and open messaging. Later, press C,
enter the same four digits and +. Each successful entry starts a fixed **10-minute session**, including
time in the background; use within that window does not extend it. Expiry closes the private UI,
even while viewing media, and requires the code again. Rotation preserves the original deadline;
process death does not preserve a session. Calculated
results do not count as code attempts. Three incorrect four-digit submissions lock messaging until
the owner sends an unblock. The calculator keeps working. The original code is required afterwards.
PIN verification, counters, recovery credentials and the separate recovery identity are stored in
Keystore-backed encrypted preferences, excluded from backup. This UI gate does not secure a rooted
or otherwise compromised device.

Choose **People → New group**, enter a name and select up to sixteen verified friends. Only
active, unblocked friends with accepted encryption keys can be invited. Invitations and replies
travel through the existing encrypted messaging transport. The creator forwards members' replies;
the creator's phone must reconnect for those replies to reach the rest of the group. Group members
can see one another's shared names and messaging IDs, but are not automatically verified contacts.
The creator controls additions/removals; other members can delete the group locally. Deleting your
own group closes it for everyone. Old local-only groups remain readable; create a new group for
shared conversations. All participants need **1.5-preview or newer**. Group chats currently support
text and quoted replies; photo/video attachments remain available in individual chats.

Touch and hold a message to quote it, then type and send; **Cancel reply** clears the quote.
Quoted snapshots also work in individual photo/video captions. Text notifications and the chat list
show the reply text rather than protocol fields. Typing indicators use encrypted, short-lived signals
rather than the persistent outbox, expire after eight seconds, and can be turned off in
**Account → Privacy and Tor → Typing indicators**. They reveal typing activity to approved recipients.

Opening an explicitly requested gallery/camera/profile picker now retains the current navigation
while the original ten-minute session is active. Cancelling or selecting returns to the same chat;
expiry and process death still require the entry code. Device verification for these new flows is
pending; see **rebuild/VERIFICATION.md**.

Encrypted profile updates keep approved contacts’ display names and photos in sync, including after reconnect. Locally chosen contact aliases are preserved.

The connection-service notice is not started while the app is open and is removed when returning. Background delivery still uses Android’s required foreground-service notice when the activity is no longer visible. Rotation and dialogs that only pause the activity do not start that service.

Message notifications use app-rendered content with the sender name, their rounded-square profile photo (or generated avatar), and the latest supplied rounded purple phone badge, and up to five unread messages kept separately for each conversation. Tapping dismisses only the notification through a private receiver; it does not open the app, delete a message, or mark it read. There are no reply actions. Android attributes them to
**Viber**. The application settings and installer also use that name; the launcher activity remains **Calculator** with its calculator icon. Previews expose message text to anyone allowed to see notifications on the device;
system lock-screen settings also apply. The content uses a decorated custom notification to avoid OEM circular avatar masks; native conversation-priority behavior may differ, and Android still controls the outer header and spacing. This remains the Relay build (package `com.relay.app`), using its own messaging transport.

Choose **Account → Profile photo → Choose photo and share** to share your own avatar with
mutually approved contacts. Photos are reduced to a JPEG of at most 128 px / 6 KiB, sent inside the
existing encrypted conversation, and stored encrypted at rest. There is no public avatar URL or
additional image-server request. New profile sharing requires an unblocked, mutually approved contact with no pending key review. Removing the photo sends a newer removal update; stale updates cannot restore it.
Recipients can still save any photo you share. Both peers need this updated app for profile photos.

Run `owner/run.sh` to open the native Linux **Control Panel**, with the supplied figure icon in
`owner/assets/control-panel.png`. The helper installs the pinned rust-nostr Python SDK in an ignored
local virtual environment on first use. Python with Tk is required. Install the app-menu shortcut
with `python3 owner/install-desktop.py`. It uses `com.relay.ControlPanel.desktop`, backing up and
removing this checkout's obsolete Calculator Owner shortcut.

**No separately hosted HTTP/HTTPS service is needed for recovery.** The panel receives encrypted
reports and sends encrypted, signed unblock commands through public Nostr relays. Only the panel
holds the owner signing key. Each phone pins that owner's public key and checks that a command
matches its own installation. Older commands cannot clear a newer lockout. Phones use a recovery
identity separate from their conversation identity; the panel receives neither conversation keys,
contacts, messages nor PINs. Both the panel and phone need internet; an offline phone waits until
it reconnects. Public relay retention and availability are outside this project's control.

**Main logs** shows the latest 300 access reports, owner actions and consent changes. Double-click
an entry to open **Individual installations**, or select a random recovery ID there and click
**Unblock selected**. Up to 100 access events per installation and 1,000 owner/consent actions are
retained. Received times show when reports reached the panel, including delayed offline reports;
access state does not prove a phone is currently online. Account → Security shows the recovery ID.
Reinstalling or clearing app data creates a new identity/installation and loses local history.

Optional diagnostics are **off by default**. Account → Security asks the phone user to explicitly
agree before sending manufacturer/model, Android/app versions, network type and this app's
camera/location/notification permission states. No extra Android permission is requested. Only the
latest report is kept, expiring after 7 days without updates. Turning sharing off requests deletion
when the phone reconnects; signed diagnostic revisions stop older opt-in messages undoing a later
revocation. These are self-reported summaries, not attestation or a comprehensive leak scan.
**Relay recovery does not reveal the phone's connection IP to Control Panel.** Relay providers can
see connecting addresses unless Tor is used. No separate IP-discovery request is made.

`owner/run.sh --prepare` creates the private owner identity under git-excluded `.local/owner/` and
writes only its public key into the build configuration. The Android build helper does this first.
The distributable APK contains the owner public key, with no local service token or owner private
key. Use the existing Control Panel on this computer to manage that APK. Protect and back up the
private owner state separately; a fresh source checkout generates a different owner identity.
Publishing the owner private key would let other people issue recovery commands.

Recovery follows the phone's Tor policy and waits when Tor is required but unavailable. Legacy HTTP
helpers remain only for regression/compatibility checks; the configured relay mode opens no owner
web server. The new app does not use an HTTP owner endpoint.

Build a test APK with `rebuild/dev/build.sh -PrelayPreview=true assembleDebug`. Package it using
`python3 rebuild/dev/package.py --apk app/build/outputs/apk/debug/app-debug.apk --output <new-folder>`.
The numbered package puts the APK first, then instructions, verification, full Android/Control Panel
source and checksums. See [DISTRIBUTION.md](rebuild/DISTRIBUTION.md) and
[VERIFICATION.md](rebuild/VERIFICATION.md) for the measured scope and remaining limits.

Relay is an Android messenger with built-in location sharing. Messages travel over the internet as
end-to-end encrypted Nostr private messages (NIP-17), without a phone number or SMS/MMS permissions.
Contacts are added by expiring QR invitations and mutual approval. Control Panel tracks anonymous
installation access and manages recovery, with separately consented diagnostics.

> **Status:** test build, not an audited production release. Current build/test results and what still
> needs physical-device verification are recorded in [VERIFICATION.md](rebuild/VERIFICATION.md).

## Features and how they work

### Messaging over the internet
- Outgoing messages are written to a **durable outbox** (SQLite) and sent by the transport with exponential backoff, up to 7 days. Chat bubbles show queued / sent / failed / read.
- `NostrTransport` wraps each message per NIP-17 (rumor → seal signed by you → gift wrap with a throwaway key) and publishes it to the recipient's inbox relays (their kind 10050 list, else relays from their QR code and your own list). Relays see an encrypted blob for a public key, not who sent it.
- Incoming messages arrive over a long-lived relay subscription, are unwrapped and authenticated (the seal signature proves the sender's key), de-duplicated persistently, then stored.
- **Offline delivery:** relays hold messages until the recipient reconnects. How long free public relays keep them is **unmeasured** (`rebuild/07-phases.md`, Phase 0, has a debug tool for it).
- **Closed-app delivery:** a foreground service (`ConnectionService`) keeps the relay connection alive, with a 15-minute WorkManager poll as a safety net. Nostr relays cannot wake a sleeping phone, so this costs battery and shows a persistent low-priority notification; it can be turned off in Settings.

### Pairing and trust
- Each install has a Nostr identity (secp256k1) plus the Tink keys below. **My code** shows a QR containing your name, Nostr key, Tink public keys and up to 3 relay hints — no phone number.
- A displayed invitation expires after 30 minutes and is single-use. Scanning it starts mutual approval; both devices must agree before the contact is marked **verified (met in person)**. The display rotates expired/used codes, and terminating the issuer app process invalidates unused codes. Existing contacts are unaffected by invitation expiry. Contacts who only know your key and message you first appear as **unverified**: they get no automatic media download and can never trigger an automatic location share.
- Old-style QR codes from the SMS version are rejected; contacts from that era stay visible with their history but must re-pair by QR.

### Location sharing (no map)
There is no map screen. In a chat, **Share my location** sends your current position (it expires after the time set in Settings) and **Request location** asks for theirs; a shared location appears as a card with coordinates and **Tap to open in Maps**, which hands it to your own maps app. Requests can be answered automatically only for **Trusted** contacts, or for **verified** contacts if auto-approve is on; otherwise you get a Share/Decline notification. Do-Not-Disturb hours and "who can request my location" apply. Replying while the app is closed cannot read the location (no background location permission).

### Photos and video
Files are compressed, encrypted with a fresh AES-256-GCM key, and the ciphertext is uploaded to a **Blossom** media server; only a small reference (URL, SHA-256, key, type) travels in the message. The receiver checks the hash before decrypting, stores the file encrypted at rest, and only downloads from verified contacts. Limits: images 3 MB / 2048 px, video 12 MB / 60 s. If an upload fails the user must retry. The defaults are `blossom.ditto.pub` and `blossom.data.haus`; encrypted binary upload/download, hash verification, decryption and cleanup were tested at the 3 MiB photo limit. Availability and retention can change. Gallery access uses selected-URI grants and survives session expiry. Photos retain their aspect ratio and camera orientation. Tap a photo to view/zoom it, or a video to play it, inside the app. Playback stops on close, backgrounding or session expiry. Private plaintext view copies are removed when their bubbles leave composition and leftover copies are removed on process startup. A direct peer connection is not required.

### Groups
Shared groups use a creator-controlled roster and encrypted private fanout to up to sixteen verified friends. The creator checks submitting members and forwards their posts; members authenticate the creator rather than independently authenticating the original author. Creator connectivity is required for forwarding. See the group instructions above and rebuild/VERIFICATION.md for current test limits.

### Tor (optional)
Settings → Privacy → "Hide my IP from relays (Tor)" routes relay and media connections through **Orbot** (the Guardian Project's Tor app), which must be installed and started separately: Relay does not include Tor. Setup is written out under the switch in the app: install Orbot, open it and tap Start, then turn the switch on; Relay expects Orbot at 127.0.0.1:9050. It **fails closed**: with Tor on and Orbot not running, nothing is sent and the status shows "Waiting for Tor". Off by default. Embedded Tor is not built, and the real Tor path has not been tested.

## Security architecture

Layers, from outside in:

1. **Transport (Nostr NIP-17/NIP-44/NIP-59).** Content and sender are hidden from relays; relays still see the recipient's public key, message size/timing and the connecting IP (unless Tor is on).
2. **Relay's own end-to-end layer (Tink).** A long-term X25519/HPKE keypair per install (private key wrapped by an Android Keystore master key) encrypts each body once the contact's key is known; a separate never-rotated Ed25519 signing key authenticates key rotations. The encryption key auto-rotates every 30 days (manual rotate in Settings; retired key kept 7 days). There is **no Double Ratchet**, so a stolen key exposes up to ~30 days of history, and NIP-17 has no forward secrecy of its own.
3. **At rest.** `relay.db` is SQLCipher-encrypted (passphrase in Keystore-backed `EncryptedSharedPreferences`); received media is encrypted with a Tink AEAD keyset; the Nostr secret key is in `EncryptedSharedPreferences`. All of these, plus the media directory, are excluded from cloud backup and device transfer.
4. **App lock.** Optional biometric/PIN gate on cold start and resume.

Threat-model notes: metadata (who talks to whom, when, message size) is partly visible to relays; a compromised phone defeats all of it; scanning the wrong QR code securely connects you to the wrong person. This is a hobby/friends project, not an audited secure messenger.

### Known gaps
- **Physical devices still need tests.** Two-emulator pairing and text replies work through public relays. Relay retention, physical-camera QR scanning, foreground-service survival through Doze/OEM killers, battery cost and real Tor behavior remain unmeasured.
- Outgoing media is kept as a plaintext cache file (needed to display and to upload); only received media is encrypted at rest.
- No chat-history export or backup: a phone swap loses history and identity (keys are Keystore-bound), and contacts must re-pair.
- Media uploads are not retried automatically.
- Public relay/blob-server retention and continued availability are not guaranteed.
- No `.onion` relays are bundled; relay list editing UI is not built (defaults/preferences only).
- Shared groups depend on the creator for availability and attribution of forwarded messages; there is no independent per-author group signature or forward-secure group key schedule.
- No license file yet.
- Android tests cover Calculator access, Keystore persistence, notifications and layout; the opt-in two-emulator harness checks live conversations. Real-phone and Tor integration still need device tests.

## Tech stack
- Kotlin, Jetpack Compose (Material 3), Android SDK 26+ (compile/target 36), JDK 17
- `org.rust-nostr:nostr-sdk-kmp-android:0.44.8` (alpha-quality Kotlin bindings; wrapped behind `com.relay.app.transport`)
- SQLCipher, Google Tink, WorkManager, CameraX + ML Kit (QR), zxing
- Kotlin coroutines; separate owner service for the installation/access reports described above

## Project structure (`app/src/main/java/com/relay/app`)
- `transport/` — `Transport` interface, `NostrTransport`, Nostr identity, payload codec, outbox policy, dedupe, Tor route policy
- `messaging/` — runtime (connect, receive, drain outbox), incoming handler, outbox worker, foreground service, poll worker, notifiers, policies
- `media/` — encrypted media protocol, AES-GCM helper, Blossom client, send/receive
- `crypto/` — Tink E2E layer and at-rest file crypto
- `data/` — models, repositories, SQLCipher helper and schema (DB v13)
- `ui/` — bottom-bar shell (People, Messages, Account), screens (messages, people/contacts, account, chat, QR, settings sections), components, theme, navigation
- `sms/` — historical package name; now holds the outbox entry point (`RelaySecureSend`), QR pairing, location-share service, alarm receivers
- `util/` — preferences, message-body protocol (`SmsMessageParser`, historical name), QR codec

## Build and run
The build files pin `org.gradle.java.home=/opt/android-studio/jbr`; on other machines override it (`-Dorg.gradle.java.home=<JDK 17>`).

### This computer

The active checkout is `Relay-rebuild`, branch `feat/rebuild`.
The separate `../Relay` directory contains the earlier `main` branch and is not this app.

Installed user-local tools: JDK 17 at `~/.local/share/relay-tools/jdk-17`, Android SDK at
`~/Android/Sdk` (platform 36, build tools, platform tools, command-line tools and emulator),
and the Gradle 8.14 wrapper distribution. Android Studio is optional and is not installed.
The system Java installation is unchanged.

From this checkout:

```sh
rebuild/dev/build.sh                                      # Build the debug APK
rebuild/dev/build.sh testDebugUnitTest lintDebug           # Run existing checks
rebuild/dev/run-emulator.sh                               # Install and open on Relay_API_35
source rebuild/dev/env.sh                                # Make adb/emulator available in this terminal
```

The launcher only targets the dedicated emulator, not a connected physical phone.
Keep its terminal open if it starts the emulator. This Fedora host uses Mesa llvmpipe
with `-gpu host -feature -Vulkan`; the bundled renderers and the NVK hardware path
crashed. The launch helper includes the working settings. Local logs and screenshots
are in the git-excluded `.local/` directory.

Current verification and known limits are recorded in [VERIFICATION.md](rebuild/VERIFICATION.md).
The original setup check used upstream commit `bb88bff`; the working preview adds the changes above.

### Other development machines

1. JDK 17, Android SDK platform 36 (Android Studio provides both).
2. `./gradlew assembleDebug` (or `rebuild/dev/build.sh :app:assembleDebug` under WSL, see `rebuild/09-dev-environment.md`).
3. `./gradlew testDebugUnitTest` runs the JVM unit tests.
4. Two separate installs are needed to test a conversation; no SIM is required. On dedicated emulators, install the app and test APK on each, then run `python3 rebuild/dev/test-two-emulators.py`. This uses fixture names Alice/Bob and test code 4826; see its help before running. A debug-only relay probe is at `com.relay.app/.lab.RelayLabActivity` (launch with `adb shell am start`).

The debug APK is ~84 MB because it bundles native libraries for four ABIs; use ABI splits or an AAB for release.

## Current limitations
- Background execution and battery optimisation vary by OEM.
- `compileSdk 36` on AGP 8.3.2 may print compatibility warnings.

## License
Add your preferred license (MIT/Apache-2.0/etc.) before public release.
