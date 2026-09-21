# 03. Options compared

Requirements recap: no default-SMS-app, no ciphertext in the stock messenger, offline delivery, normal-app feel,
no server of our own, keep the existing crypto and QR pairing.

## Summary table

| # | Option | Stock app sees it | Own server needed | Offline delivery | Closed-app wake-up | Media | Verdict |
|---|---|---|---|---|---|---|---|
| 1 | Plain SMS/MMS (today), not default app | **Yes** (ciphertext) | No | Yes (carrier) | Yes | Poor | Rejected |
| 2 | Data SMS, not default app | No | No | Yes (carrier) | Yes | Text only, practically | Fallback idea only |
| 3 | RCS | n/a | n/a | n/a | n/a | n/a | Impossible (allowlist) |
| 4 | Pure P2P (WebRTC / Iroh / libp2p) | No | No | **No** | Needs FGS/push | Good | Only if both online |
| 5 | Briar-style P2P + Mailbox | No | Yes (Mailbox device) | Yes | Partial | Limited | Server in disguise |
| 6 | Own relay server + FCM | No | **Yes** | Yes | Yes | Good | Best UX, but a server |
| 7 | **Nostr NIP-17 over public relays** | No | **No** | **Yes** | FGS now, bridge later | Via Blossom | **Chosen** |
| 8 | Email as transport (Delta Chat model) | No | No (uses mail servers) | Yes | Yes (IMAP push) | OK | Strong runner-up |
| 9 | Matrix / XMPP | No | No (public servers) | Yes | Yes (push gateways) | Good | Heavier, more moving parts |

## Notes per option

### 1. Plain SMS, not default
Works technically (receive via `SMS_RECEIVED`, send via `SmsManager`, own history in SQLCipher). Fails the "stock app
also shows the ciphertext" requirement, and incoming MMS is fragile. Detailed table in 01.

### 2. Data SMS
The one SMS variant that satisfies "not default" and "stock app doesn't show it". Cost and limits:
- About 5 to 6 segments for a 500-character encrypted message; short chat lines fit in 1 to 2.
- Text only in practice. Images would need dozens of segments.
- Carrier/MVNO support and cross-network delivery must be tested with the actual SIMs.
- Needs a SIM, coverage, and per-SMS pricing awareness.
Kept as a possible future "no internet" fallback, not the core.

### 4. Pure P2P
Great privacy, nothing in the middle. Loses offline delivery and closed-app wake-up. Iroh is the nicest library
(dial by public key, relay fallback, about 90 percent direct). Can be added later as a **fast path** next to Nostr for
live sessions (e.g. live location sharing).

### 6. Own relay server plus FCM
The conventional design (Signal, WhatsApp). Reliable and simplest to make feel native. Rejected only because the
owner wants no infrastructure to run. Remains the escape hatch if public relays prove too unreliable: a single small
store-and-forward service plus an FCM or UnifiedPush bridge (Supabase is already connected in this workspace and could
host a minimal version).

### 7. Nostr (chosen)
Why it wins:
- Offline delivery is built in: relays store events until the recipient asks for them.
- No accounts. Identity is a keypair. That matches the QR pairing model.
- NIP-17 hides the sender from relays and randomises timestamps.
- Group fan-out already exists in Relay (one message per member), which matches NIP-17's one wrap per recipient.
- Works on any public relay; the user can point at their own relay later.
- rust-nostr provides JVM/Android bindings covering NIP-17/44/59.
Why it is not perfect:
- Public relay retention and reliability are unproven (Phase 0 test).
- No standard push wake-up (see 04).
- Bindings are alpha.
- Metadata (public keys, timing, size, IP) is visible to relays unless Tor is used.

### 8. Email transport (Delta Chat model)
The strongest alternative if Nostr disappoints. Existing servers, existing push (IMAP IDLE / provider push), no new
protocol. Cost: heavier protocol surface (SMTP/IMAP), account credentials on the phone, and more metadata in headers.
Delta Chat itself is open source and is the obvious reference implementation.

## Why not simply "use Signal / Matrix / Briar"
Relay's value is the map-centric location sharing plus its own crypto and QR pairing UX. The goal is to keep that
product and swap only the transport underneath.
