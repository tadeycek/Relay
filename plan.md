Here's the summary of the Relay E2E encryption design:

**Goal:** Turn Relay from "no-backend SMS/MMS messenger" into "no-backend SMS/MMS messenger with optional end-to-end encryption," without losing the ability to text anyone by phone number with zero setup.

**Transport:** Stays exactly as-is — SMS/MMS only, no server, no internet-based delivery. Encryption happens client-side before handing ciphertext to the existing `SmsSender`/`MmsPduBuilder`.

**Pairing:** In-person only, via QR code. Each phone encodes its public identity key + a signed prekey into a QR code; both people scan each other's. Because pairing is synchronous and in-person, you skip Signal's usual prekey-server step entirely — no server needed anywhere in the flow.

**Crypto:** Use `libsignal-client` (Signal's open-source library) rather than hand-rolling crypto. Run the Double Ratchet per contact pair so every message uses a fresh derived key — gives forward secrecy (one leaked key only exposes one message, not the whole history).

**Trust model / key point:** The phone number is just a routing address, not the trust anchor. Someone who only knows/enters a number (no QR handshake) can only send/receive plain unencrypted SMS with that contact — they have no key material, so no decryption ability, regardless of number spoofing, SIM swaps, or number reassignment.

**Number-add stays enabled** alongside QR pairing (don't remove it — it's what makes Relay useful over Signal, since you can text people who don't have the app). To prevent false confidence, the UI needs to clearly distinguish encrypted/QR-paired contacts (lock icon) from plain-SMS contacts, and should never silently let a plain "add by number" create a second, unencrypted entry for someone you're already paired with.

**Safety-number-style check:** Need a "contact's key changed" warning (like Signal's) for when a paired contact's identity key suddenly changes — covers the case of them losing their phone or a SIM swap, so re-pairing isn't silently trusted.

I also saved this to project memory so it persists across sessions if you want to pick it back up later.
