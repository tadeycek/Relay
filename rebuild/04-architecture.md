# 04. Target architecture

Design principle: **swap the transport, keep the product.** Everything above "how bytes reach the other phone" should
change as little as possible. Introduce a `Transport` interface so Nostr is one implementation and others (Iroh fast
path, data-SMS fallback) can be added later without touching UI or repositories.

```
UI (Compose)  ->  ViewModels  ->  Repositories  ->  MessagingService  ->  Transport (interface)
                                                        |                    |-- NostrTransport (v1)
                                                        |                    |-- (later) IrohTransport, SmsTransport
                                                        v
                                                   Payload codec  (JSON payloads, versioned)
                                                   Crypto (Tink inner layer, optional)
                                                   Outbox (retry, dedupe, ordering)
```

## 1. Identity and keys

Each install holds three keypairs (two already exist):

| Key | Purpose | Status |
|---|---|---|
| Nostr identity (secp256k1, `npub`/`nsec`) | Address on the network, signs seals, decrypts NIP-44 | **New** |
| Tink HPKE X25519 | Inner payload encryption, rotated periodically | Existing (`RelayCrypto`) |
| Tink Ed25519 signing | Long-term anchor that authenticates rotations and messages | Existing (`RelayCrypto`) |

Storage of the Nostr secret key: Android Keystore has no secp256k1, so generate the key in software and store it
encrypted with a Keystore-backed AES-GCM key. The repo already does this pattern with Tink `AndroidKeysetManager` and
`android-keystore://` master keys, so reuse that (a Tink AEAD keyset holding the wrapped secret). Confirm the key is
excluded from Android backup (`allowBackup` is currently `true`; see 08).

Should the Nostr identity rotate? Recommendation: **no**. The `npub` is the stable address (like a phone number was).
Rotation stays on the Tink layer, which already has grace periods and signed rotation broadcasts.

## 2. Pairing (QR)

- QR format goes from `RELAYQR:2|PHONE:..|NAME:..|KEY:..|SIGKEY:..` to a v3 that carries the `npub`:
  `RELAYQR:3|NAME:..|NPUB:..|KEY:..|SIGKEY:..|RELAYS:<optional comma list>`.
- `PHONE` becomes optional or is dropped. A phone number is no longer routing or trust.
- Scanning is in person, so the QR itself is the trust anchor (public keys arrive out of band, no key server, no
  spoofable sender ID). This removes the SIM-swap and number-reassignment concerns from `plan.md` entirely.
- Keep the "contact's key changed" flow (`pendingPublicKey`), now keyed by `npub`.
- Optional later: pairing by a shared link (`relay://pair?...`) for remote friends, clearly marked as weaker because
  the link travels over another channel. An SMS invite through the standard `ACTION_SENDTO` intent needs no
  permissions (the user's own SMS app sends it).

## 3. Message model

Today: `TYPE:...` strings parsed by regex (`SmsMessageParser`). New: a small versioned JSON payload inside the
NIP-17 rumor, e.g.

```json
{ "v": 1, "type": "text|location|location_request|location_declined|read_receipt|key_rotation|image|video",
  "id": "<uuid>", "ts": 1758440000000, "...": "type-specific fields" }
```

Mapping of existing message types:

| Existing (`MessageType` / TYPE:) | New payload `type` | Notes |
|---|---|---|
| TEXT | `text` | Could also use NIP-17 kind 14 directly; JSON keeps one codec. |
| LOCATION (lat, lng, expiry, label) | `location` | Expiry also set as a NIP-40 `expiration` tag on the wrap (verify; relays may delete early). Local purge (`PinExpiryReceiver`) stays. |
| LOCATION_REQUEST / LOCATION_DECLINED | `location_request` / `location_declined` | Unchanged semantics; trust-level gating stays. |
| READ_RECEIPT (cursor) | `read_receipt` | Use message `id`s instead of timestamps; drop the 2-minute clock-skew slack. |
| PUBKEY (pairing + rotation) | `key_rotation` | Pairing itself is via QR now. Rotation carries new HPKE key plus Ed25519 signature over it. |
| IMAGE / VIDEO | `image` / `video` | Blob reference, see section 7. |

The rumor is wrapped per NIP-17: rumor -> seal (kind 13, signed by our `nsec`) -> gift wrap (kind 1059, random key),
one wrap per recipient plus one to ourselves so multi-device or reinstall recovery is possible later.

## 4. Inner encryption layer: keep or drop?

- **Keep (v1 recommendation):** put the existing Tink HPKE ciphertext plus Ed25519 signature *inside* the rumor
  content. Benefits: reuses tested code (rotation, grace period, signing), keeps the encrypted-contact UX, and
  a flaw in one layer does not expose content. Cost: double encryption overhead (negligible on text) and two key
  systems to maintain.
- **Drop:** rely on NIP-44 plus the seal signature only. Simpler, but discards existing rotation logic, and NIP-17 has
  no forward secrecy on its own (a stolen `nsec` decrypts everything ever sent to it).
- **Upgrade later:** replace the inner layer with a real Double Ratchet for forward secrecy (what `plan.md` originally
  intended). Out of scope for v1. Keep the inner layer behind an interface so it can be swapped.

## 5. Relays

- Default list: at least 3 reputable public relays, configurable. Publish each wrap to **the recipient's kind 10050
  relays** (NIP-17 requirement). Each user publishes a kind 10050 list of their own inbox relays; on QR pairing the
  optional `RELAYS` hint lets a contact send before the 10050 event has been fetched.
- Write to several relays, dedupe on read by event id and payload `id`.
- Health tracking: mark relays unreachable, back off, try alternates. Surface "0 relays reachable" in the UI.
- Unknowns to measure in Phase 0: how long free relays keep gift wraps, rate limits, whether some relays reject
  kind 1059 from unknown keys, reconnect behaviour on flaky mobile networks.
- Escape hatch: users (or we) can run a private relay and put it first in the list, with no protocol change.

## 6. Getting the message to a closed app (the hard part)

Nostr relays do not push to phones. Options, in order of cost:

| Approach | How it works | Drawbacks |
|---|---|---|
| **A. Foreground service** (v1) | App holds WebSocket connections to inbox relays, permanent notification. Android 14+ requires a foreground service type (likely `remoteMessaging`; verify). | Battery, permanent notification, some OEMs kill it. |
| B. Periodic polling | `WorkManager` job fetches every N minutes. | Android limits periodic work to 15 minutes minimum, so delivery is delayed. Useful as a safety net. |
| C. Notification bridge | A small server subscribes to relays for the user's `npub` and sends a content-free silent push via FCM or UnifiedPush. The app then wakes and fetches. | **It is a server**, and it learns which `npub` belongs to which push endpoint. Nostr push standards are still proposals (NIP issue #257, PR #2194). |

Plan: ship A (+ B as fallback), measure real-world reliability, and only add C if that is not good enough. C can be
self-hosted or run as a minimal Supabase edge function; treat it as optional infrastructure, not a rewrite.

## 7. Media

- Text and location go straight in the rumor. Images and video do not fit in a Nostr event.
- Flow: compress (`MediaCompressor`, limits relaxed since MMS carrier caps no longer apply) -> encrypt with a random
  key (`RelayFileCrypto` already exists) -> upload the ciphertext to a **Blossom** server (blob addressed by sha256,
  upload authorised by a signed kind 24242 event) -> send `{url, sha256, key, nonce, mime, size}` in the DM
  (NIP-17 kind 15 is the standard shape for encrypted file messages).
- Open: which Blossom server(s), retention, and size limits. Default to a small list with fallback. Ship v1 without
  media if it slows the rest.

## 8. Groups

Existing groups are local-only with sender-side fan-out (`MapViewModel.sendPinToGroup`, `groups`/`group_members`
tables), which maps directly onto NIP-17 (one wrap per member). Keep the model. NIP-17 recommends against more than
about 10 members; that matches Relay's "lightweight groups" scope. Group membership changes are local, so the group
id should travel inside the payload to group replies correctly on the receiving side.

## 9. Delivery semantics

- **Outbox table**: every outgoing payload is stored with status (`queued`, `sent_to_n_relays`, `failed`) and retried
  with backoff. This replaces `SmsManager` sent/delivered `PendingIntent`s.
- **Dedupe**: payload `id` is the idempotency key (relays may return the same wrap from several relays).
- **Ordering**: sort by payload `ts` with a per-conversation tiebreaker; NIP-17 timestamps on the wrap are deliberately
  fuzzed by up to two days, so **never** use wrap `created_at` for ordering or "since" queries. Use own `since`
  bookkeeping with generous overlap.
- **Receipts**: "delivered" = recipient client acked (optional payload), "read" = existing read-receipt cursor.
- **Replay/spoof**: authenticated by the seal signature (Nostr) and the inner Ed25519 signature; reject wraps from an
  `npub` that is not a paired contact (or route to a "message request" list, like `ContactTrustLevel.ASK`).

## 10. Location sharing

Pins and requests are ordinary payloads. **Live** location (`LocationShareService`) sends a stream of updates.
Publishing every update to public relays is wasteful; options are throttling (for example one per minute) or later
adding an Iroh/WebRTC fast path used only while both sides are online. Not in v1 scope beyond throttled payloads.

## 11. Privacy and Tor

See `05-tor.md`. In short: content protected by NIP-44 (+ Tink), sender hidden from relays by NIP-17, relays still see
recipient key, size, timing and IP unless the optional Tor mode is on. Transport code must accept a configurable
proxy from day one so Tor can be added without refactoring.

## 12. Data at rest

Unchanged: SQLCipher database (`RelayDbHelper`, `RelayDbPassphrase`), biometric lock (`AppLockGate`), encrypted media
cache. Schema changes are limited to `contacts` (add `nostr_pubkey`, make `phone` nullable), a new `outbox` table,
optional `relays` table, and message-id columns (see 06).
