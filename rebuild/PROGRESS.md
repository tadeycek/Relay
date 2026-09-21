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
