# 01. Problem and goals

## What Relay is today

Per `README.md`: an Android SMS/MMS messenger with map-based location sharing. No accounts, no backend. Location pins,
location requests, read receipts and key exchange are encoded as `TYPE:...` text inside ordinary SMS bodies
(`util/SmsMessageParser.kt`). Optional end-to-end encryption wraps those bodies as `TYPE:ENC|CT:...|SIG:...`
(`sms/RelaySecureSend.kt`, `crypto/RelayCrypto.kt`). Keys are exchanged by in-person QR (`util/QrContactCode.kt`).

## Why the SMS transport is a dead end

The owner will not make Relay the default SMS app. That has concrete consequences on Android:

| Capability | Default SMS app | Not default |
|---|---|---|
| Write messages to the system SMS/MMS provider | Yes | **No** |
| Receive `SMS_DELIVER` / `WAP_PUSH_DELIVER` | Yes | **No** |
| Receive `SMS_RECEIVED` (read-only copy) | Yes | Yes |
| Send SMS via `SmsManager` | Yes | Yes |
| Send MMS | Yes | Yes (with limits) |
| Reliable MMS receive | Yes | **Fragile** (must watch `content://mms` after the stock app downloaded it) |
| Hide ciphertext from the stock messenger | Yes | **No**, the stock app also shows `TYPE:ENC|CT:...` |

The last row is the deal breaker: every encrypted message would show up as garbage in the user's normal messaging app,
and Relay cannot delete it.

The current code was built around default status (Phase 4 commit added eligibility, the SENDTO filter,
`HeadlessSmsSendService`, `SMS_DELIVER` and `WAP_PUSH_DELIVER` receivers).

Additional SMS drawbacks that hold regardless of default status:

- Every message costs money on per-SMS plans. A 500-character encrypted message is roughly 5 to 6 data-SMS segments
  (estimate: about 128 usable bytes per segment; about 100 bytes of crypto overhead from AEAD tag/nonce plus an
  Ed25519 signature and framing).
- MMS media is capped by carriers (the code already limits images to 900 KB and video to 30 s).
- Carrier sees metadata. SMS sender IDs are spoofable. SIM swaps and reassigned numbers are a trust risk
  (already noted in `plan.md`).
- Needs a SIM and cell coverage. Does not work over Wi-Fi only.

## Goals for the rebuild

1. Talk to a friend over the internet in Relay, with **no server of our own**.
2. **Offline delivery**: a message sent while the friend's phone is off is delivered when it comes back.
3. Feels like a normal messaging app: background delivery, notifications, read receipts, groups, media.
4. **Not the default SMS app**, and no SMS permissions at all in the final app.
5. Keep what already works: QR pairing and key verification, Tink crypto, key rotation, encrypted local database
   (SQLCipher), biometric lock, map and location features.
6. Content end-to-end encrypted; metadata exposure minimised, with an **optional Tor mode** to hide IP addresses.

## Non-goals

- Texting arbitrary phone numbers. Both sides need Relay. (Trade-off accepted; an SMS invite intent can soften it.)
- Being a Signal-grade metadata-private messenger. The design reduces metadata leakage but does not eliminate it.
- Running our own always-on infrastructure. (A tiny optional notification bridge is discussed in 04/08 as a possible
  later addition, not a starting requirement.)
- iOS. Android only.
- RCS. Google's RCS APIs are allowlist-only and not available to third-party apps.
