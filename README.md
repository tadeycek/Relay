# Relay

## 1.15 preview: loading artwork update

Version 1.15-preview (versionCode 16) adds all five supplied loading images, including
the enhanced artwork in the 1.5% slot. It retains the delivery fixes from 1.14.

Active/last-active sharing and its labels have been removed. Upgrading removes only the retired
activity-history table; saved contacts, messages, keys and pending deliveries remain. Old peers'
activity controls are silently ignored. Group key preparation runs separately from outgoing
message delivery and outside the database writer/group lock. A queued error notification cannot
keep the Send button busy. Known-key encryption failures abort rather than skipping the inner layer.
Local queue acceptance is distinct from receipt by another phone; network and Android scheduling
still determine remote delivery.

After a successful calculator unlock, a local screen lasts three seconds, independently of the
messaging transport. All five slots use the supplied images with **60%, 20%, 13%, 5.5%, 1.5%** chances.
The fifth slot uses the enhanced artwork. All images fill
the same phone-screen frame without stretching; excess edges are cropped. The selection/deadline
survive rotation; the ten-minute private-session deadline is unchanged. Disable it under
**Account → Appearance → Three-second loading screen**. Internet audio calling is not implemented
in this checkout.

## Historical 1.11 follow-up

Fixes for #31 and #33–#37 add seven-day local message/attachment retention, precise
in-app photo-save references and supported screenshot photo references, non-badging
notification channels, draft acceptance that preserves newer typing, a single chat
options dot, and approximate encrypted active/last-active status for verified contacts.
Activity sharing can be disabled under Privacy; it only renews in resumed
private screens and expires after a minute. Universal external capture/copy detection
is unavailable. Explicit Save photo exports a user-confirmed plaintext copy; private
viewing otherwise stays protected. Retention cannot erase exported copies/relay data.
See [verification](rebuild/VERIFICATION.md) and [installation](rebuild/DISTRIBUTION.md)
for the checks and remaining phone acceptance. Existing 1.10 direct group delivery is retained.


## Calculator entry

The Android launcher is **Calculator**, with the supplied calculator icon. It opens a working basic
calculator with decimal arithmetic, precedence, parentheses, percentages, clear and delete. On first
use, enter **four digits, then +** to set the private entry code and open messaging. Later, press C,
enter the same four digits and +. Each successful entry starts a fixed **10-minute session**, including
time in the background; use within that window does not extend it. Expiry closes the private UI,
even while viewing media, and requires the code again. Rotation preserves the original deadline;
process death does not preserve a session. Calculated
results do not count as code attempts. **Ten incorrect four-digit submissions in a row erase all of
Relay's data on the phone** (messages, contacts, photos and keys), exactly like clearing the app's
data: the calculator stays, and messaging starts again from a new code with a new identity that has to
be paired again. A correct code resets the count. PIN verification and the failure count are stored in
Keystore-backed encrypted preferences, excluded from backup. This UI gate does not secure a rooted
or otherwise compromised device.

Choose **People → New group**, enter a name and select up to sixteen verified friends. Only
active, unblocked friends with accepted encryption keys can be invited. Invitations and replies
travel through the existing encrypted messaging transport. Messages, reactions, deletions, typing and
name updates go **directly from the sender to every member**, so they arrive without waiting for the
creator's phone. Only membership changes come from the creator. Group members can see one another's
shared names and messaging IDs, but are not automatically verified contacts.
The creator controls additions/removals; other members can delete the group locally. Deleting your
own group closes it for everyone. Old local-only groups remain readable; create a new group for
shared conversations. Install **1.8-preview or newer on every participant's phone** for group photos,
videos and deletion controls. Use the attachment button to send media and **Members** in the group
header to view membership; the creator can add/remove verified friends there. Failed group media
downloads stay pending and retry when reopening the group or reconnecting.

Touch and hold a message to open **Reply / Copy / Delete**. Choose Reply, then type and send;
**Cancel reply** clears the quote. Delete can remove a local copy, or request removal of a message
you authored from compatible recipient apps. Author-scoped tombstones cover delayed delivery.
Older group messages without stable event ids support local deletion only. Saved copies,
screenshots, quoted snapshots and relay ciphertext are not guaranteed to disappear.
Quoted snapshots also work in individual photo/video captions. Text notifications and the chat list
show the reply text rather than protocol fields. Typing indicators use encrypted, short-lived signals
rather than the persistent outbox, expire after eight seconds, and can be turned off in
**Account → Privacy → Typing indicators**. They reveal typing activity to approved recipients.

Opening an explicitly requested gallery/camera/profile picker now retains the current navigation
while the original ten-minute session is active. Cancelling or selecting returns to the same chat;
expiry and process death still require the entry code. Device verification for these new flows is
pending; see **rebuild/VERIFICATION.md**.

Encrypted profile updates keep approved contacts’ display names and photos in sync, including after reconnect. Locally chosen contact aliases are preserved.

**Account → Security → Emergency: keep calculator only** requires the original four-digit code.
Wrong confirmation codes count toward lockout. After confirmation, the app attempts author-scoped
remote history deletion for up to 15 seconds, then erases local conversations, media, contacts,
preferences and Keystore keys, cancels work/notifications/shortcuts, disables messaging components,
and closes. Reopening leaves a functional calculator without an unlock gate. The compiled messaging
code stays in the APK; this is a local data wipe and disabled mode, not partial uninstallation.
Reinstalling/clearing data can reset it. Offline recipients, old versions, removed memberships,
blocked contacts, saved/forwarded/quoted copies and relay retention can prevent complete remote
erasure. Undelivered deletion requests are erased too. Use test devices until phone checks pass.

The continuous background connection service and its “running in the background” notice have been
removed. Old settings and boot/background callbacks cannot start it. The app cancels its legacy
connection notice/channel on startup. Normal message notifications still work. Messages arrive
while the process is alive or during ordinary WorkManager checks (15-minute minimum, possibly
much later under battery restrictions); reopen the app for prompt delivery after Android stops it.
There is no hidden foreground-service workaround or instant closed-app-delivery guarantee.

Message notifications use Android’s native conversation layout with the sender name, their profile photo (or generated avatar), and up to five unread messages kept separately for each conversation. The latest supplied purple phone image is registered as the application icon for Android’s app badge. The sender bitmap contains no painted badge, preventing a duplicate overlay. Tapping dismisses only the notification through a private receiver; it does not open the app, delete a message, or mark it read. There are no reply actions. Android attributes them to
**Viber**. The application settings and installer also use that name; the launcher activity remains **Calculator** with its calculator icon. Previews expose message text to anyone allowed to see notifications on the device;
system lock-screen settings also apply. The custom content layout from 1.6 has been removed because it added a separate app icon beside the sender photo on the reference phone. Android controls the final avatar mask, badge placement, header and spacing; exact pixel matching across phones is not guaranteed. This remains the Relay build (package `com.relay.app`), using its own messaging transport.

Local preview 1.10 adds QR image import without camera permission and **Download my QR code**.
The exported code retains its original nonce and 30-minute expiry; image imports use the same
preview, confirmation and mutual pairing checks as scanning. Only unused, unexpired invitations
work, and restarting the issuer process invalidates its unconsumed invitation.

The private code, app lock and original session deadline still apply after the loading screen. In **People → group → Edit**,
the creator can choose/preview/save, replace or remove a **Group picture**. It is shared over
private encrypted messages and stored in SQLCipher, with monotonic revisions and no public URL.

Choose **Attach → Animated GIF / sticker** to send a local GIF (up to 3 MiB, 2048 px); its animation
bytes are preserved in chats and the viewer. No external GIF-search provider is contacted.
Hold a message to set/change an emoji reaction or remove your reaction. Reactions identify the
participant and synchronize through authenticated, durable private controls. All participants
need 1.10 for these features.

On Android 14+, open chats show that screenshot notices are enabled. Supported system screenshot
callbacks create an authenticated, timestamped chat event. Android cannot detect every capture
method; ADB/instrumentation captures are not a device acceptance test. Android 8–13 shows an
unavailable notice and does not monitor the gallery as a substitute. Notices are registered only
while the conversation's activity is visible, and stop on exit/session expiry.

Chats start with the newest 200 messages, then **Load older messages** expands history while
retaining the reading anchor. See [the performance investigation](rebuild/PERFORMANCE-2026-10-17.md)
for host-only benchmark results, current hosting comparisons and outstanding phone/relay tests.

Preview 1.9 adds labelled **Mute / Unmute** controls with an enabled/muted bell icon in each contact/group header.
Mute persists per conversation, cancels its existing preview and suppresses future message/media
alerts; messages and unread counts still update. Unmute enables future alerts without replay.
Both chat types open at the newest message. Scrolling away from the bottom stops following;
returning within 48 dp enables following again. Stable row keys retain the older reading position.
Text is accepted by a local transaction before the composer clears. Attachments first commit an
encrypted local copy, queued bubble and persistent upload job; upload/dispatch retries run in the
background for up to seven days. Failed local preparation leaves the draft available to retry.
Queued means local acceptance; sent means relay acceptance, never proof of recipient delivery.
Group names use separate monotonic profile revisions, leaving membership/permissions unchanged.
Current names apply to historical sender labels; saved reply quotes retain their original snapshot.
Each member announces their own name directly to the other members.

Choose **Account → Profile photo → Choose photo and share** to share your own avatar with
mutually approved contacts. Photos are reduced to a JPEG of at most 256 px / 8 KiB, sent inside the
existing encrypted conversation, and stored encrypted at rest. There is no public avatar URL or
additional image-server request. New profile sharing requires an unblocked, mutually approved contact with no pending key review. Removing the photo sends a newer removal update; stale updates cannot restore it.
Existing reduced photos cannot regain detail: select the original photo again to share the higher-quality version. Older clients may ignore a photo exceeding their previous 128 px / 6 KiB limits; upgrade both peers. Recipients can still save any photo you share. Both peers need this updated app for profile photos.

Build a test APK with `rebuild/dev/build.sh -PrelayPreview=true assembleDebug`. Package it using
`python3 rebuild/dev/package.py --apk app/build/outputs/apk/debug/app-debug.apk --output <new-folder>`.
The numbered package puts the APK first, then instructions, verification, full Android and server
source and checksums. A build needs the delivery server's address in `.local/mailbox.properties`
(`url=https://…`); see [server/cloudflare](server/cloudflare/README.md). Release builds refuse to build
without it, and builds without it fall back to the old public Nostr relays (development only). See [DISTRIBUTION.md](rebuild/DISTRIBUTION.md) and
[VERIFICATION.md](rebuild/VERIFICATION.md) for the measured scope and remaining limits.

Relay is an Android messenger with built-in location sharing. Messages travel over the internet as
end-to-end encrypted packets (the NIP-17 gift-wrap format) through **Relay's own delivery server** on
Cloudflare, without a phone number or SMS/MMS permissions, and without any public relay. Contacts are
added by expiring QR invitations and mutual approval.

> **Status:** test build, not an audited production release. Current build/test results and what still
> needs physical-device verification are recorded in [VERIFICATION.md](rebuild/VERIFICATION.md).

## Features and how they work

### Messaging over the internet
- Outgoing messages are written to a **durable outbox** (SQLite) and sent by the transport with exponential backoff, up to 7 days. Chat bubbles show queued / sent / failed / read.
- `MailboxTransport` wraps each message per NIP-17 (rumor → seal signed by you → gift wrap with a throwaway key) and hands it to **Relay's delivery server** (`server/cloudflare`, protocol in its `PROTOCOL.md`) over HTTPS, signed with your key. The wrap is built once and resent byte-for-byte on retries, so the server never stores it twice. No public relay is contacted.
- Incoming messages arrive over one WebSocket. Each packet is saved in the encrypted database before the server is told it arrived, then unwrapped and authenticated (the seal signature proves the sender's key), de-duplicated persistently and stored; the server then deletes it.
- **Offline delivery:** the server holds a message until the recipient's phone confirms it, for up to 8 days (the app refuses messages older than 7 days anyway).
- **Typing and active status** pass through the server live, socket to socket, and are never stored.
- The server sees who messages whom and when, and message sizes; content stays end-to-end encrypted.
- **Closed-app delivery:** the process receives while alive. Ordinary periodic WorkManager checks reconnect after Android kills/suspends it, with a 15-minute minimum that Android can delay much longer. No continuous foreground service or connection-status notification is used.

### Pairing and trust
- Each install has a Nostr identity (secp256k1) plus the Tink keys below. **My code** shows a QR containing your name, Nostr key, Tink public keys and up to 3 relay hints — no phone number.
- A displayed invitation expires after 30 minutes and is single-use. Scanning it starts mutual approval; both devices must agree before the contact is marked **verified (met in person)**. The display rotates expired/used codes, and terminating the issuer app process invalidates unused codes. Existing contacts are unaffected by invitation expiry. Contacts who only know your key and message you first appear as **unverified**: they get no automatic media download and can never trigger an automatic location share.
- Old-style QR codes from the SMS version are rejected; contacts from that era stay visible with their history but must re-pair by QR.
- **Joining the delivery server:** its founders are set when it is deployed. Anyone else is let in when they pair in person with someone already on it: a member who scans a newcomer's QR vouches for them, and a newcomer who scans a member's QR joins with the one-time invite the member's app registered for that code. Two newcomers can't pair with each other until one of them has paired with a member.

### Location sharing (no map)
There is no map screen. In a chat, **Share my location** sends your current position (it expires after the time set in Settings) and **Request location** asks for theirs; a shared location appears as a card with coordinates and **Tap to open in Maps**, which hands it to your own maps app. Requests can be answered automatically only for **Trusted** contacts, or for **verified** contacts if auto-approve is on; otherwise you get a Share/Decline notification. Do-Not-Disturb hours and "who can request my location" apply. Replying while the app is closed cannot read the location (no background location permission).

### Photos and video
Files are compressed, encrypted with a fresh AES-256-GCM key, and the ciphertext is uploaded to **Relay's delivery server**, readable only by the message's recipients and deleted once they all have it (or after 8 days); only a small reference (URL, SHA-256, key, type) travels in the message. References to any other server are never fetched. The receiver checks the hash before decrypting, stores the file encrypted at rest, and only downloads from verified contacts. Limits: images 3 MB / 2048 px, video 12 MB / 60 s. Accepted uploads retry in the durable background queue for up to seven days; after final failure, delete the failed bubble and resubmit if needed. Gallery access uses selected-URI grants and survives session expiry. Photos retain their aspect ratio and camera orientation. Tap a photo to view/zoom it, or a video to play it, inside the app. Playback stops on close, backgrounding or session expiry. Private plaintext view copies are removed when their bubbles leave composition and leftover copies are removed on process startup. A direct peer connection is not required.

### Groups
Shared groups use a creator-controlled roster and encrypted private fanout to up to sixteen verified friends. Group protocol v2 sends every post, deletion, purge, name update and typing signal straight from its author to each member (one relay hop). A receiver accepts an event only when the authenticated sender is its author and is on the receiver's own copy of the roster. Only the creator can send roster updates. The roster carries each member's Tink key and up to two relay hints, vouched for by the creator, who verified them by QR, so members who aren't each other's contacts can still seal to and reach one another. An event naming a newer roster than the receiver has (e.g. from a just-added member) is held, bounded and expiring, until that roster arrives. **Every participant needs a build with protocol v2**: older phones only accept group posts forwarded by the creator, so they stop receiving other members' messages. See the group instructions above and rebuild/VERIFICATION.md for current test limits.

### Tor
Tor mode is **switched off** (2026-10-18): every connection goes direct to the delivery server, so the server (Cloudflare) can see your IP address. The Orbot routing code is kept for a possible return; its settings are hidden.

## Security architecture

Layers, from outside in:

1. **Transport (NIP-17/NIP-44/NIP-59 gift wraps through Relay's delivery server).** Content is hidden from the server. Sends are authenticated, so the server sees sender, recipient, size, timing and the connecting IP.
2. **Relay's own end-to-end layer (Tink).** A long-term X25519/HPKE keypair per install (private key wrapped by an Android Keystore master key) encrypts each body once the contact's key is known; a separate never-rotated Ed25519 signing key authenticates key rotations. The encryption key auto-rotates every 30 days (manual rotate in Settings; retired key kept 7 days). There is **no Double Ratchet**, so a stolen key exposes up to ~30 days of history, and NIP-17 has no forward secrecy of its own.
3. **At rest.** `relay.db` is SQLCipher-encrypted (passphrase in Keystore-backed `EncryptedSharedPreferences`); received media is encrypted with a Tink AEAD keyset; the Nostr secret key is in `EncryptedSharedPreferences`. All of these, plus the media directory, are excluded from cloud backup and device transfer.
4. **App lock.** Optional biometric/PIN gate on cold start and resume.

Threat-model notes: metadata (who talks to whom, when, message size) is visible to the delivery server; a compromised phone defeats all of it; scanning the wrong QR code securely connects you to the wrong person. This is a hobby/friends project, not an audited secure messenger.

### Known gaps
- **Physical devices still need tests.** Two-emulator pairing, text both ways, photos and offline catch-up work through the delivery server (`rebuild/dev/test-mailbox-emulators.py`). Physical-camera QR scanning, periodic delivery through Doze/OEM killers and battery cost remain unmeasured.
- Outgoing media is kept as a plaintext cache file (needed to display and to upload); only received media is encrypted at rest.
- No chat-history export or backup: a phone swap loses history and identity (keys are Keystore-bound), and contacts must re-pair.
- Media uploads are not retried automatically.
- Everything depends on one delivery server on Cloudflare's free plan: if it is down or over its daily quota, messages wait on the phone and are sent when it is back.
- A new identity (fresh install, wipe) must be added to the server's allowlist before it can send or receive.
- Shared groups depend on the creator only for membership changes. Members trust the creator's roster for who belongs and for other members' keys and relay hints; each event is authenticated by its author's own Nostr seal. There is no forward-secure group key schedule. A just-removed member can briefly exchange messages with members who haven't received the new roster yet. Until a creator's phone refreshes a roster saved before v2, members who aren't each other's contacts are reached without the inner Tink layer (NIP-17 encryption only).
- No license file yet.
- Android tests cover Calculator access, Keystore persistence, notifications and layout; the opt-in two-emulator harnesses check live conversations. Real-phone tests are still needed.

## Tech stack
- Kotlin, Jetpack Compose (Material 3), Android SDK 26+ (compile/target 36), JDK 17
- `org.rust-nostr:nostr-sdk-kmp-android:0.44.8` (alpha-quality Kotlin bindings; wrapped behind `com.relay.app.transport`)
- SQLCipher, Google Tink, WorkManager, CameraX + ML Kit (QR), zxing
- Kotlin coroutines; OkHttp for the delivery server; the server itself is TypeScript on Cloudflare Workers (`server/cloudflare`)

## Project structure (`app/src/main/java/com/relay/app`)
- `transport/` — `Transport` interface; `mailbox/` (the delivery-server client: transport, receipt rules, raw inbox, media); `NostrTransport` (development builds without a server); Nostr identity, payload codec, outbox policy, dedupe, Tor route policy (off)
- `messaging/` — runtime (connect, receive, drain outbox), incoming handler, outbox worker, retired-service cleanup, poll worker, notifiers, policies
- `media/` — encrypted media protocol, AES-GCM helper, send/receive (delivery server; Blossom client only for builds without one)
- `crypto/` — Tink E2E layer and at-rest file crypto
- `data/` — models, repositories, SQLCipher helper and schema (DB v22)
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
