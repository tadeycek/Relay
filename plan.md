> **Superseded.** This file described the original SMS/MMS-based E2E encryption plan. The app now uses internet
> messaging over Nostr; the current design, decisions and status are in `rebuild/` (start with `rebuild/README.md`
> and `rebuild/PROGRESS.md`) and the root `README.md`. The original summary is kept below for history.

**Note that the original plan also differed from what was built:** it proposed `libsignal-client` with a Double
Ratchet, but the code always used Google Tink (HPKE + Ed25519 signatures, key rotation) with no ratchet.

---

Original summary of the Relay E2E encryption design (SMS era):

**Goal:** Turn Relay from "no-backend SMS/MMS messenger" into "no-backend SMS/MMS messenger with optional end-to-end encryption," without losing the ability to text anyone by phone number with zero setup.

**Transport:** Stays exactly as-is — SMS/MMS only, no server, no internet-based delivery. Encryption happens client-side before handing ciphertext to the existing `SmsSender`/`MmsPduBuilder`.

**Pairing:** In-person only, via QR code. Each phone encodes its public identity key + a signed prekey into a QR code; both people scan each other's.

**Trust model:** The phone number is just a routing address, not the trust anchor. Someone who only knows/enters a number (no QR handshake) can only send/receive plain unencrypted SMS with that contact.

**Safety-number-style check:** A "contact's key changed" warning for when a paired contact's identity key suddenly changes.
