# 02. Research findings

Confidence labels used below:

- **[verified]** confirmed by a source fetched or searched on 2026-09-21 (linked at the bottom).
- **[knowledge]** from general Android/protocol knowledge, **not** re-checked. Verify before relying on it.

## A. Core networking facts (why a middle piece is needed)

- Phones sit behind carrier-grade NAT, have changing IPs, and sleep. Two phones usually cannot connect directly.
  [knowledge]
- Peer-to-peer over WebRTC/QUIC works when both apps are open, using STUN/hole punching. Some NAT pairs (often
  cellular to cellular) fail and need a relay (TURN or equivalent). [knowledge]
- Delivery while the recipient is offline **requires something that stores the message** (a mailbox). Pure P2P systems
  say this themselves: "Delivery of offline messages is not possible without an additional intermediate station
  (mailbox)". [verified, freie-messenger.de]
- Waking a sleeping Android app needs a push channel (FCM, UnifiedPush) or an app-held persistent connection.
  [knowledge]

## B. SMS-specific findings

- Only the default SMS app can write to the SMS provider and receive `SMS_DELIVER`. Other apps can only read the
  provider and get the non-abortable `SMS_RECEIVED` broadcast. [verified, Android Telephony docs via search]
- **Data SMS** (port-addressed, `SmsManager.sendDataMessage`) does not normally go to the native messaging app and is
  received via the data SMS intent. [verified, search result]
  Unverified details (from knowledge): about 128 to 140 bytes per message, app must chunk and reassemble, carriers
  or MVNOs may block or not deliver across networks, each chunk is billed.
- Cost model: total = segments x price per SMS. 500 characters encrypted is about 5 to 6 segments. Unlimited-SMS plans
  make it free, pay-per-SMS plans do not. [estimate]
- **RCS**: Google's RCS APIs are on a hidden allowlist. Third-party apps cannot send RCS directly. [verified, XDA]
- Data SMS is the only SMS-family route that avoids both "default app" and "stock app shows ciphertext", but it is
  text-only in practice, carrier-dependent and costs money. It is kept as a documented fallback idea only.

## C. Peer-to-peer libraries and apps

- **Briar**: P2P over Tor, Wi-Fi and Bluetooth. Both contacts must be online together. Briar Mailbox (a helper app on
  a spare device that buffers messages, reached over Tor) fixes reachability but is a server you run. [verified]
- **Iroh**: dial by public key, QUIC, hole punching with fallback to public relay servers run by n0 (its makers).
  About 9 in 10 network configurations get a direct connection. No DHT. No built-in offline storage.
  Cellular-specific numbers not found. [verified, Pinggy / Micrologics / iroh docs]
- **Zemzeme** (Android): example of an app combining Bluetooth mesh, libp2p direct P2P and Nostr relays. Useful as a
  design reference. [verified]
- **Delta Chat**: OpenPGP-encrypted messages over ordinary email. Developers run no servers. Optional "chatmail"
  relays give instant delivery and multi-relay redundancy. Offline delivery and push behave normally because the mail
  server is the mailbox. [verified]

## D. Nostr findings (the chosen direction)

- **NIP-17 private direct messages** use NIP-44 encryption and NIP-59 seals and gift wraps. [verified, nips.nostr.com/17]
  - kind 14 = chat message (plain text content), kind 15 = encrypted file message (with decryption metadata),
    kind 13 = seal, kind 1059 = gift wrap, kind 10050 = user's preferred DM relay list. [verified]
  - Three layers: unsigned rumor -> seal (kind 13, signed by the sender) -> gift wrap (kind 1059, signed by a random
    throwaway key). Each recipient, and the sender, gets a separate gift wrap. [verified]
  - Clients must publish only to the relays in the **recipient's kind 10050 list**. [verified]
  - Clients should randomise `created_at` up to two days in the past on seal and wrap to blunt timing analysis.
    [verified]
  - Groups over about 10 participants are discouraged because of one wrapped event per recipient. [verified]
- Relays cannot read content and (with NIP-17) cannot see the sender. They still see: a locked event addressed to a
  public key, its size, when it arrived, and the connecting IP address. [verified for sender hiding; rest is inherent]
- Public relays are free but vary in reliability and may delete old events. Retention of DMs on free relays was
  **not** established by research. It must be tested (Phase 0). [open]
- **Library**: rust-nostr has Kotlin/JVM bindings (`nostr-sdk-jvm` on Maven Central, seen at version 0.44.2, plus a
  Kotlin Multiplatform variant `nostr-sdk-kmp`). It has NIP-17, NIP-44 and NIP-59 support. Bindings are **alpha**:
  "the API will change in breaking ways". [verified, rust-nostr book and Maven search]
  Android-specific packaging (ABIs, APK size) not checked. [open]
- **Blossom** (media): HTTP blob servers addressed by sha256; `PUT /upload`, `GET /<sha256>`, authorisation via a signed
  kind 24242 Nostr event; a server can implement optional upgrades (BUDs). [verified]
  Fit with NIP-17 kind 15 file messages (encrypt file first, upload, send key + URL in the DM) is our design idea, not
  an established recipe. [knowledge]
- **Push for Nostr on Android is not solved by the protocol alone.** There is a proposal for relays to generate
  notification triggers sent to an app-specific notification server (NIP issue #257, PR #2194 "NIP 9a"); it is a
  proposal, not a standard the public relays all support. On Android you need silent push, and the push server may
  need to keep events for around 15 minutes because Android can be slow to wake the app. [verified as discussion]
  Consequence: **closed-app delivery needs either an app-held connection (foreground service) or a notification
  server.** A notification server is a server.
- UnifiedPush: an open push standard. Needs an app, a distributor app on the phone (ntfy is the easiest) and a server
  the distributor talks to (public ntfy.sh, self-hosted ntfy or Gotify). Works without a Google account. It does not
  by itself connect to Nostr relays. [verified]

## E. Tor findings

- Android's `VpnService` builds a tunnel but the tunnel must end at a server. An in-app VPN just moves trust to
  whoever runs that server. It does not fit "no server of our own". [knowledge]
- Embedding Tor (Briar does this) or using Orbot hides the user's IP from relays. Many Nostr relays also publish
  `.onion` addresses. [knowledge; Briar-over-Tor verified]
- Costs: slow bootstrap, battery and data, some relays block Tor exits, larger APK if embedded, and push channels
  (FCM/UnifiedPush) can leak the IP to the push server. [knowledge]
- Which Tor library is best to embed in 2026 (tor-android, Guardian Project / Orbot, or Arti) was **not**
  researched. [open]

## F. What "encrypted" does and does not cover (for user-facing honesty)

- Content: strongly protected end to end (NIP-44, plus Relay's Tink layer if kept).
- Not hidden: that a message exists for a public key, time and size, the sender/recipient IP addresses (unless Tor),
  pseudonymous public keys if linked to a person.
- Not protected by encryption: a compromised phone, a stolen secret key (NIP-17 has no forward secrecy on its own),
  screenshots/backups, scanning the wrong QR code, implementation bugs.

## Sources

- freie-messenger.de, serverless messengers: https://www.freie-messenger.de/en/serverlos/
- Zemzeme Android: https://github.com/whisperbit-labs/zemzeme-android
- Briar: https://briarproject.org/how-it-works/ and https://briarproject.org/news/2023-briar-mailbox-released/
- Iroh: https://github.com/n0-computer/iroh , https://pinggy.io/blog/iroh_1_0_dial_keys_not_ips/ ,
  https://micrologics.org/blog/demystifying-iroh-10-how-to-build-serverless-peer-to-peer-apps-without-the-ipfs-overhead
- Delta Chat: https://delta.chat/en/help , https://delta.chat/en/2026-03-31-zero
- NIP-17: https://nips.nostr.com/17 , https://nostrcompass.org/en/topics/nip-17/
- rust-nostr: https://rust-nostr.org/sdk/nips/17.html , https://github.com/rust-nostr/nostr-sdk-ffi ,
  https://central.sonatype.com/artifact/io.github.rust-nostr/nostr-sdk
- Nostr push proposals: https://github.com/nostr-protocol/nips/issues/257 , https://github.com/nostr-protocol/nips/pull/2194
- Blossom: https://github.com/hzrd149/blossom , https://nostrcompass.org/en/topics/blossom/
- UnifiedPush / ntfy: https://unifiedpush.org/users/distributors/ntfy/ , https://docs.ntfy.sh/subscribe/phone/
- RCS allowlist: https://www.xda-developers.com/google-messages-rcs-api-third-party-apps/
- Android SMS default-app rules / data SMS: https://github.com/kant2002/Cordova-SMS-Reception-Plugin
