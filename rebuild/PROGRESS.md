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
