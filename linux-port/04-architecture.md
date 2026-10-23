# 04. Architecture

> Read [10-readiness-gates.md](10-readiness-gates.md) before implementation. It corrects security, lifecycle and
> compatibility assumptions below and takes precedence where the initial proposal conflicts with a gate.

## 1. Modules

```
settings.gradle
├── :core      (new, Kotlin/JVM library, no Android imports)
├── :app       (Android, existing; depends on :core)
└── :desktop   (new, Compose Multiplatform Desktop; depends on :core; Linux .deb/AppImage, macOS .dmg)
server/cloudflare (existing Worker; gains device cursors, link slot, call endpoints, CallRoom DO)
```

`:core` holds everything that has to be byte-identical across clients. Platform code supplies storage, secrets,
network sockets and notifications to it through interfaces:

```
UI (Android Compose | Desktop Compose)
  └─ ViewModels / presenters (per platform)
       └─ Repositories (per platform: Android SQLCipher | sqlite-jdbc-crypt)
            └─ :core  MessagingEngine ─ CallEngine
                 ├─ PayloadCodec, body codecs (TEXT/REPLY/MEDIA/FILE/GROUP v2/SYNC/CALL)
                 ├─ InnerEnvelope (Tink HPKE)   ← KeyMaterial interface
                 ├─ NIP-17 wrap/unwrap (nostr-sdk-kmp)
                 ├─ MailboxClient (HTTP + WS, NIP-98 auth, device cursor)
                 ├─ MediaCrypto / FileParts (streaming AES-GCM per part)
                 └─ Call signalling state machines (1:1, group control)
```

## 2. What moves into `:core`

These are the files that already have no `android.*` or `Context` dependency, according to a grep run on
2026-10-23. WP03 moves them, keeping their packages, so that the git history follows the files:

- **`transport/`**: `RelayPayload`, `Route`, `SeenIds`, `Transport`, `Backoff`, `outbox/OutboxPolicy`,
  `mailbox/MailboxProtocol`, `MailboxSession`, `MailboxConfig`.
- **`media/`**: `MediaBody`, `MediaCrypto`, `MediaContainer`, `ProfileNameBody`, `ProfilePhotoBody`.
- **`messaging/`**: `ReplyBody`, `DeletionBody`, `PurgeBody`, `ReadReceiptBody`, `ConversationEventBody`,
  `RetentionPolicy`, `IncomingClassifier`, `IncomingPolicies`, `LocationRequestPolicy`, `RefreshQueue`.
- **`groups/`**: `GroupProtocol`, `GroupAvatarBody`.
- **`pairing/`**: `PairingCore`.
- **`util/`**: `SmsMessageParser` (its `TYPE:` parsers) and `QrContactCode`.
- **`data/model/*`**.

Some files need to be split before they can move. Their Android half stays in `:app` and their pure half moves:

- **`InnerEnvelope`** uses `android.util.Base64`. Switch it to `java.util.Base64` and inject the key holder.
- **`MailboxAuth` and `MailboxMedia`** take a `Context` only to get keys. Inject a `NostrSigner` instead.
- **`RelayCrypto`**: its Tink primitive calls move. `AndroidKeysetManager` and Keystore stay in `:app`.
- **`TypingSignals`, `MessageDeletion`, `GroupDelivery`, `GroupDispatch`**: the decisions move, and the Android
  side effects stay.

Rule for the extraction: **no behaviour change in `:app`**. Existing unit tests move with their code and must pass
unchanged. WP03 is complete only when the Android app builds and its whole unit suite is green with `:core` in place.

## 3. Desktop client (`:desktop`)

| Concern | Choice |
|---|---|
| UI | Compose Desktop with Material 3 and a Discord-shaped layout: a left rail (People, Groups, Calls), a list column, a conversation pane, a member/info panel, and a bottom voice bar while in a call. It is keyboard first: Enter sends, Shift+Enter adds a newline, Ctrl+K is the quick switcher, Esc closes. |
| Storage | `sqlite-jdbc` (sqlite-jdbc-crypt) using the SQLCipher scheme. The schema mirrors the Android tables needed for messages, contacts, groups, outbox, media and file parts, with its own migration numbering. Attachments are stored as `RelayFileCrypto`-style encrypted files under `$XDG_DATA_HOME/relay/` (Linux) or `~/Library/Application Support/Relay/` (macOS). |
| Secrets | `SecretStore` interface backed by the Linux Secret Service or the macOS Keychain (LP-D05). It holds a 256-bit wrapping key. The identity bundle and DB key are AEAD-sealed with it on disk. An optional passphrase uses Argon2id via Bouncy Castle and replaces the keyring item. |
| Network | OkHttp for HTTPS and the WebSocket to `/v1/ws?device=<id>`, the same as the phone. Reconnect backoff uses `:core` `Backoff`. |
| Notifications | Linux: `org.freedesktop.Notifications` over D-Bus (dbus-java), falling back to the AWT `SystemTray`. macOS: `SystemTray`/`TrayIcon.displayMessage`. Notification text follows the phone's preview setting from the sync bundle. |
| Tray / background | Closing the window minimises to the tray, so the socket stays open and messages keep arriving. Quit is explicit. There is optional autostart: an XDG `~/.config/autostart` entry on Linux and a LaunchAgent on macOS. |
| Calls | `webrtc-java` 0.20.0 with the platform classifier. Audio devices come from its `AudioDeviceModule` (PulseAudio/PipeWire-pulse on Linux, CoreAudio on macOS). |
| Media viewing | Images decode in-process with Skia. Video uses an in-app player behind a `VideoPlayer` interface; the backend is chosen in WP08 based on licence and size. If no acceptable in-process player exists, "Open in system player" first decrypts to a private temp file and deletes it on exit, and the user is told this explicitly. |
| Packaging | Linux: `.deb` from Compose `nativeDistributions` plus an AppImage built with `appimagetool` from the jpackage app image. macOS: an unsigned `.dmg` built on a GitHub Actions macOS runner, Apple Silicon only (LP-D15, LP-D17). |

## 4. Linked-device model (LP-D02, LP-D03)

**Identity.** A Relay identity is defined by its Nostr key (address and server auth) plus its Tink HPKE current and
previous keysets and its Ed25519 signing keyset. Every linked device holds all of them. Contacts see a single
identity and need no changes.

**Device id.** Each device has a random 128-bit id written as hex. The phone is `primary`, which matches what
1.15 phones implicitly are (LP-D14). Every mailbox request and socket carries `device=<id>` in the URL, so it is
covered by the NIP-98 `u` tag.

**Linking flow** (WP05 for the phone and server, WP06 for the desktop):

1. The desktop generates an ephemeral X25519 key pair and a 16-byte link code. It shows a QR containing
   `RELAYLINK:1|E:<eph pub>|C:<code>|N:<computer name>`.
2. The phone scans it under **Account → Linked computers → Link a computer**. The phone must be unlocked and inside
   its 10-minute session, and it also asks for biometric/PIN re-confirmation.
3. The phone builds the **link bundle** and HPKE-seals it to `E`. The bundle contains:
   - the Nostr secret key;
   - the Tink keysets in cleartext Tink JSON (sealed inside HPKE);
   - the contact list (keys, names, trust, verification state);
   - group rosters;
   - profile name;
   - settings (typing on/off, notification preview, retention);
   - the new `deviceId`.
4. The phone registers the device with `POST /v1/devices` (device id plus label), then stores the sealed bundle in
   the server's **link slot**: `PUT /v1/link/<sha256(code)>`, with a 10-minute TTL, single read, ≤128 KiB.
5. The desktop polls `GET /v1/link/<sha256(code)>?proof=<code>`. This request is unauthenticated, because the
   desktop has no identity yet; knowing the code is the capability. The server deletes the slot on first read.
6. The desktop opens the bundle with its ephemeral key, writes the keys into its secret store, and connects with its
   `device` id. It starts its cursor at the server's current head (`GET /v1/inbox` returns the head, see 05 §1), so
   it doesn't replay the phone's old backlog.

**Sync channel** (WP05). State that only the primary changes reaches the other devices as **self-deliveries**:
normal `/v1/send` deliveries addressed `to` our own pubkey. Their body is `TYPE:SYNC|…` (05 §3). Self-deliveries
travel through the same queue, so every device processes them in `seq` order relative to contacts' messages. That
ordering is what makes key rotation safe:

- The phone rotates, sends `SYNC KEYS` to itself, and only after it is `stored` broadcasts the rotation to
  contacts. Any contact message encrypted to the new key therefore has a higher `seq` than the `SYNC KEYS` that
  delivers that key to the desktop.

**Sent messages.** When any device sends a message, it adds a **sent-copy** delivery to its own pubkey in the same
`/v1/send` request:
- body `TYPE:SYNC|SENT|…`, carrying the conversation target and the original payload;
- other devices insert it as outgoing;
- the sending device skips its own copy by `origin` device id.

Typing signals and read state:
- **Typing** isn't mirrored.
- **Read state** is mirrored with `TYPE:SYNC|READ` (conversation and up-to timestamp), coalesced to at most one
  every 10 s per conversation.

**Unlinking.** On the phone, Linked computers → Remove sends `DELETE /v1/devices/<id>` and the server drops that
device's cursor. This doesn't remove the keys from a stolen computer (09 R3). The UI says so and offers **"Reset
identity"**, which is the existing wipe-and-repair path.

## 5. Message flow with two devices

```
Alice's phone (unchanged 1.15) ──/v1/send to=Bob──▶ Mailbox queue[Bob] seq 41
                                                       │ live push to every Bob socket
                              ┌────────────────────────┴──────────────────────┐
                    Bob phone (device=primary)                     Bob desktop (device=d7…)
                    stores, acks through 41                        stores, acks through 41
                              └──────────────▶ server deletes seq ≤ min(active cursors)
```

## 6. Files (LP-D07)

**Sending a file:**
1. The sender streams the file once to compute its size and choose a part count (`ceil(size / 25 MiB)`, at most 4).
2. Each part is encrypted with the per-file key and its own random IV: `iv‖ct‖tag`, the same layout as
   `MediaCrypto`. Streaming mode keeps memory bounded on phones.
3. Each part is uploaded to `/v1/media/<sha256(part blob)>` with every recipient pubkey.
4. Then the `TYPE:FILE` body (05 §2) is sent, listing the parts in order.

**Receiving a file:**
1. The receiver downloads the parts one at a time and verifies each part's hash.
2. It decrypts each part and appends it to an encrypted local file. It keeps per-part progress in the DB, so an
   interrupted download resumes from the next part.
3. It confirms each part with `DELETE ?device=` after it is stored locally.

**Where files go:**
- Phone: files go into app-private encrypted storage. **Save** exports to Downloads through the Storage Access
  Framework, and **Open** uses a temporary `content://` grant.
- Desktop: **Save as…** writes to a chosen path, and **Open** decrypts to a private temp directory.
- Drag-and-drop onto the conversation pane sends the file.
- Images and videos sent as files show a preview but stay files. Sending "as photo" still uses `TYPE:MEDIA`, which
  keeps the 1.15 behaviour.

## 7. Calls

There are two separate engines in `:core`, each with platform media adapters:

- **`CallEngine1to1`**: P2P WebRTC. Signalling uses `signal` frames carrying `TYPE:CALL` bodies, and ICE servers
  come from `/v1/call/ice`. The full spec is in 06.
- **`GroupCallEngine`**: the SFU, plus the `CallRoom` DO socket. The full spec is in 07.

**`MediaAdapter` interface** (one per platform):
- open the microphone;
- create a PeerConnection;
- set or get SDP;
- add ICE candidates;
- mute;
- read audio level stats;
- select the output device.

The Android adapter uses `io.github.webrtc-sdk:android`. The desktop adapter uses `webrtc-java`.
