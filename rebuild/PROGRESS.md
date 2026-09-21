# Rebuild progress log

Branch: `feat/rebuild`. Each phase is two commits. Status labels: **built** (compiles, unit tests where the logic is pure),
**device-unverified** (needs a real phone/relay to confirm; no device was available while this was written).

## Environment used for building

No JDK or Android SDK existed on the machine, so a toolchain was installed under `~/tools` in the WSL Fedora
distro: Temurin JDK 17, Android SDK (platform 36, build-tools 36.0.0 and 34.0.0, platform-tools), standalone
Gradle 8.14. The Android SDK licences were accepted non-interactively (`sdkmanager --licenses`) to allow this.
Use `rebuild/dev/build.sh <gradle args>`.

## Phase 0: Spike

- Built. Nostr SDK dependency `org.rust-nostr:nostr-sdk-kmp-android:0.44.8` resolves and compiles; the API surface
  guessed from the SDK sources matched on the first compile (Keys, NostrSigner, Client, EventBuilder.privateMsgRumor,
  giftWrapTo, UnwrappedGift, Filter, fetchEventsFrom).
- Debug APK builds with native libs for all four ABIs. **Debug APK is ~83 MB** (all ABIs, unshrunk); the SDK AAR alone
  is ~17 MB, roughly 4 to 6 MB per ABI. Release builds should use ABI splits/AAB.
- Debug-only `RelayLabActivity` (`adb shell am start -n com.relay.app/.lab.RelayLabActivity`) implements the retention
  probe: "Send probe" then "Check probe" after 1 h, 24 h, 72 h, 7 d.
- **Device-unverified:** relay retention, background survival, real latency. The Phase 0 exit criteria in
  `07-phases.md` are therefore **not yet confirmed**; run the lab on two phones before trusting later phases.
- Fixed a pre-existing compile error on `fix/audit` (`Manifest.permission.READ_MMS` does not resolve on SDK 36).

## Phase 1: Transport foundation

- Built, 17 JVM unit tests: `Transport` interface, `RelayPayload`/`PayloadCodec` (JSON envelope `{v,id,ts,body}`),
  `OutboxPolicy` (backoff 5 s to 15 min, 7-day give-up), `SeenIds` (LRU dedupe), `Backoff` (reconnect schedule).
- Built, **device-unverified**: `NostrTransport` (NIP-17 send/receive over rust-nostr, reconnect loop, SOCKS proxy hook),
  `NostrIdentity` (secp256k1 key in EncryptedSharedPreferences), `Transports` singleton, relay list in prefs.
- **Deviation from `04-architecture.md`:** the payload is an envelope whose `body` is the existing `TYPE:...` string
  rather than typed JSON per message kind. This keeps every existing parser and screen working. Typed payloads can
  replace it later behind `PayloadCodec` (version field allows it).
- Backup rules now also exclude the previously missing signing / previous / temp-rotation Tink keysets.
- Known limit: no self-copy of sent messages (NIP-17 recommends one wrap to the sender for multi-device); not needed
  for a single-device app.

## Phase 2: Pairing and contacts

- Built, 27 JVM unit tests: DB v10 (additive migration), `Contact.nostrPubkey/relayHints/hasPhone/subtitle`,
  `OutboxRepository`, QR v3 codec (v1/v2 still decode), pairing flow.
- **Design choice:** the `contacts.phone` column stays `NOT NULL UNIQUE`; internet-only contacts store
  `nostr:<hex>` there. Rebuilding the table would have cascade-deleted messages (foreign keys are on), so the
  placeholder is the safe route. UI uses `Contact.subtitle` instead of `phone`.
- QR v3 carries no phone number: `RELAYQR:3|NAME|NPUB|KEY|SIGKEY|RELAYS`. Profile setup now needs only a name.
- After scanning a v3 code, our `TYPE:PUBKEY` handshake is queued in the outbox (durable) for the transport to deliver.
- **Device-unverified:** the migration on a real v9 database and the scanner flow end to end.

## Phase 3: Messaging features

- Built, 42 JVM unit tests total: `IncomingMessageHandler`, `OutboxWorker`, `MessagingRuntime`, `Outgoing`,
  `InnerEnvelope`, notifiers, pure policies (`TimestampPolicy`, limiters, `DndWindow`, `IncomingClassifier`).
- Sending: `RelaySecureSend.sendWithId` routes internet contacts to the durable outbox and legacy contacts to SMS.
  Text, pins, location requests/declines, read receipts and group pin fan-out all go through it. Bubbles show
  queued / sent / failed / read.
- **DB is now v11** (a `seen_payloads` table was added after v10 was committed; no build with v10 was ever installed).
- **Design choice:** control messages (location request, receipts, key announcements) leave no message row, so replay
  protection needed its own persistent table. Without it, the 3-day relay replay window would re-trigger auto-share.
- **Known gaps:** groups still fan out per member only for pins (unchanged behaviour); media is blocked for internet
  contacts until Phase 5; the app still asks for SMS permissions on launch until Phase 8; POST_NOTIFICATIONS is not
  yet requested at runtime on Android 13+ (notifications are skipped silently without it) - fixed in Phase 8.
- **Device-unverified:** everything that touches a relay or the Keystore.

## Phase 4: Background delivery

- Built: `ConnectionService` (foreground service, type `remoteMessaging`), `BootReceiver`, `PollWorker` (WorkManager
  every 15 min as a safety net), network-loss watcher that forces a fresh connection, Settings "Connection" section
  (status, Reconnect, background toggle with an honest battery note).
- **Verified against the SDK 36 `android.jar`:** `FOREGROUND_SERVICE_REMOTE_MESSAGING` and
  `ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING` exist, resolving that open item from `08-...md`.
- **Transport hardening:** the connection loop now uses a generation counter so a superseded loop (the native
  notification call may not be cancellable) can never tear down its replacement's client; "online" now means at least
  one relay actually connected (`connect()` does not block); added `Transport.reconnect()`.
- **Still unverified, and the biggest remaining risk:** whether the foreground service survives Doze and OEM task
  killers overnight, and its real battery cost. Whether a foreground service may be started from `BOOT_COMPLETED` for
  this type on Android 15+ is also unconfirmed (the start is wrapped in try/catch).
- **Not built:** a notification bridge (FCM/UnifiedPush). Decision gate from the plan stands: build it only if the
  foreground service proves unreliable in device testing.

## Phase 5: Media

- Built and unit-tested: `MediaBody` (strict reference codec), `MediaCrypto` (AES-256-GCM, tamper/wrong-key/truncation
  cases), `BlossomClient.download` (hash check, size cap incl. streamed bodies, bounded redirects, error status;
  tested against a local raw-socket server), classifier support.
- Built, **device/server-unverified:** `BlossomClient.upload` (BUD-02 `PUT /upload` with a per-upload throwaway
  kind 24242 auth event), `MediaSender`/`MediaReceiver`, chat wiring.
- **Deviation from `04-architecture.md`:** the reference travels as a `TYPE:MEDIA|...` body inside the existing
  envelope, not as a NIP-17 kind 15 file message. Kind 15 would tie us to other clients' conventions we could not
  verify; the private format is easy to migrate later via the payload `v` field.
- **Privacy decisions:** media is only auto-downloaded from contacts we hold a key for (a sender-chosen URL would
  otherwise reveal our IP); a separate throwaway key signs each upload so servers cannot link uploads to an identity;
  received files are stored encrypted at rest and never at a path derived from sender input.
- **Limits:** if an upload fails the user must retry (uploads are not in the durable outbox; only the small reference
  message is); no resumable/chunked upload; no transcoding (video is only size/length checked: 12 MB / 60 s, images
  3 MB / 2048 px). Default Blossom servers are **unverified** (limits, auth, payment, retention unknown).

## Phase 6: Key rotation, unknown senders, hardening

- Built and unit-tested: `InboxRelays` (NIP-17 kind 10050 parse/publish/target selection), `LocationRequestPolicy`,
  `Contact` model rules. Built, device-unverified: publishing our kind 10050 list, looking up recipients' lists,
  rotation announcements queued through the outbox.
- **Security review (self-review of the new code; the `/security-review` command was not run).** Findings acted on:
  1. A stranger who knows our key could announce any name and, with "auto-approve location requests" on, receive an
     automatic location share (the SMS-era behaviour applied to any number). New `qr_verified` column (**DB v12**) is
     set only by scanning the contact's QR in person; `LocationRequestPolicy` never auto-shares to an unverified
     contact unless the user explicitly marked them Trusted.
  2. Auto-downloading a sender-chosen media URL reveals our IP to that host; media is now fetched only for
     `qr_verified` contacts (others get a placeholder).
  3. Unverified contacts are labelled "unverified" in lists (a self-declared name such as "Mom" is otherwise
     indistinguishable from a real contact).
  4. Received media directory excluded from backup/device transfer.
  5. Lint: the 6 lint errors present are all pre-existing (CameraX opt-in marker, and missing
     `uses-feature` for telephony/camera). The telephony ones disappear with SMS removal in Phase 8; the camera
     `uses-feature` will be added there.
- **Residual risks (not fixed):** relay URLs from a scanned QR or a published list are contacted automatically
  (validated wss:// only, max 5), which reveals our IP to those relays; a hostile QR could therefore point us at
  a relay of the attacker's choosing. Mitigated by the Tor option (Phase 7). Groups still have no membership
  authentication (a group is a local list; senders are matched by contact).

## Phase 7: Tor option

- Built and unit-tested: `RoutePolicy` (fail closed), onion-relay URL rules. Built, **device-unverified**: `TorControl`
  (Orbot SOCKS probe), proxy mode in `NostrTransport`, Tor-aware media transfers, Settings UI.
- **What is implemented:** Orbot-based Tor (Phase 7a in the plan). Embedded Tor (7b) was **not** built: which library to
  embed (tor-android / Guardian Project / Arti) was never researched, and inventing a choice would have been a guess.
- Settings: "Hide my IP from relays (Tor)" (off by default), live status, an Orbot shortcut, a "connect directly if Tor
  is unavailable" switch (off by default = fail closed), and the pros/cons panel from `05-tor.md`, including what it does
  not cover.
- Fail closed everywhere the app makes a connection we control: relay connection (WAITING_FOR_TOR, nothing sent) and
  media upload/download (refused with an explanatory message / placeholder).
- **Known leaks / gaps (documented in the UI where user-visible):** OSMDroid map tiles are fetched directly;
  nothing has been packet-captured, so the plan's Phase 7 exit criterion (no direct traffic) is **unproven**;
  the SDK's proxy mode is assumed to resolve DNS remotely (SOCKS5 with domain names) but this was not confirmed;
  the Orbot probe only proves the port is open, not that Tor finished bootstrapping.
- No `.onion` relays are bundled (none could be verified); users can add their own once relay editing exists.

## Phase 8: Remove SMS, release prep

- Removed all SMS/MMS code, permissions, the default-SMS-app flow, add-by-phone, `PhoneNumberField` and
  libphonenumber. Merged manifest checked with `aapt2 dump permissions`: **no SMS/MMS permissions remain**.
- Contacts from the SMS era stay visible with history but cannot be messaged until re-paired by QR v3. Old QR codes
  are rejected with a message. Notification permission (Android 13+) is requested once, non-blocking.
- README and `plan.md` rewritten/marked superseded. The old README claimed the code had never been compiled; it now
  has been (see below) but still has never run on a device.
- Lint: 0 errors (the pre-existing CameraX opt-in error is suppressed with a justification; the telephony/camera
  hardware-feature errors are gone with the SMS permissions and a camera `uses-feature required=false`).

## Final verification (what was actually run)

| Check | Result |
|---|---|
| `compileDebugKotlin`, `assembleDebug`, `assembleRelease` | Pass (debug 84 MB, release unsigned/unminified 76 MB, all four ABIs) |
| `testDebugUnitTest` | 92 tests, 0 failures |
| `lintDebug` | 0 errors, warnings only |
| Merged manifest permissions | No SMS/MMS permissions |
| On-device run, live relays, real Blossom server, Tor traffic capture, Doze/battery | **Not done** (no device or network test harness available) |

## Deviations from the plan, in one place

1. Payload is an envelope `{v,id,ts,body}` carrying the existing `TYPE:...` body strings, not typed JSON per kind.
2. Media uses a private `TYPE:MEDIA|...` reference, not NIP-17 kind 15 file messages.
3. Tor is Orbot-only (Phase 7a); embedded Tor (7b) not built.
4. The DB went to v12 (not v10): `seen_payloads` (replay protection) and `qr_verified` (met-in-person trust) were added
   during Phases 3 and 6 after design review. No build with an earlier schema was ever installed.
5. No notification bridge (FCM/UnifiedPush); the plan's decision gate for it depends on device measurements.
6. `SmsMessageParser` and the `sms/` package keep their historical names.

## What to do next (in priority order)

1. Run the Phase 0 lab on two phones (`RelayLabActivity`): relay retention over 1 h / 24 h / 72 h / 7 d. If public
   relays do not retain messages, the plan's fallback options apply (own tiny relay, email transport).
2. Install on two phones, pair by QR, and test: text, pin, location request, read receipt, photo, offline recipient,
   airplane mode, reboot, overnight battery with the foreground service.
3. Verify or replace the default relay and Blossom server lists; add a relay-list editor.
4. Packet-capture with Tor on to confirm nothing leaves except through Orbot (relays, Blossom, and note map tiles).
5. Decide on a notification bridge only if step 2 shows the foreground service is unreliable.
6. Run `/security-review` for a second opinion; add a license; sign a release with ABI splits.

## UI redesign: bottom navigation (People / Messages / Account), no map

Plan and decisions: `ui-navbar-plan.md`. **Change of plan during the build:** the user questioned the value of the map, so the
map tab was replaced by **People** and the map was removed (option 2 of three offered): location sharing moved into chats.

- Built and unit-tested: bottom-bar shell (Tab/route logic), `GeoLinks` (geo: URIs), `ConversationFormat` (previews, time labels,
  ordering, request rules, badge text), unread schema **DB v13**. Total JVM unit tests now 107, 0 failures.
- Removed: map screen, pin history, send-pin sheet, OSMDroid, map preferences, `ACCESS_BACKGROUND_LOCATION`. Consequence: replying
  to a location request while the app is closed cannot read the location. Group pin sending is gone (no map to pick a point); group
  chats send text only.
- Added: Messages tab (conversation list, unread badges, delivery ticks, REQUESTS section for unverified senders), Account tab
  (profile card, your Relay ID, QR entry, all settings), People tab (the contacts/groups screen), Share-my-location in chats.
- **Verified on the phone (Android 16, arm64):** app installs and launches; Settings showed "Connected to your relays" (the relay
  connection works on a real device); bottom bar and Account tab render correctly with inset handling. A crash opening Pin History
  (private ViewModel, pre-existing) was found and fixed before the map was removed.
- **Not yet verified visually:** the Messages and People tabs and any conversation content (the phone locked before those
  screenshots), the v12 to v13 upgrade beyond a clean launch, and everything needing a second phone.
- **Bugs found on the way:** group members were loaded without their Nostr key so group sends silently failed (fixed);
  the Tor panel still described map tiles (fixed).
