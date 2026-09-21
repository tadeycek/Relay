# 07. Phases

Work is ordered so the two biggest unknowns (relay retention, Android background behaviour) are answered first, and
SMS code is removed last. The app stays buildable throughout. Work on a branch off `main` (current branch is
`fix/audit`).

## Phase 0: Spike (throwaway, 1 to 2 days)

Goal: prove the concept and measure unknowns before touching real code.
- Standalone tiny Android app or a scratch module: create keys, connect to 3 public relays, publish and receive one
  NIP-17 message between two physical phones. Try `nostr-sdk-jvm` first; note API stability, ABI/APK size impact.
- Measure: publish latency, reconnect after airplane mode, rate-limit errors.
- **Retention test**: send, switch the recipient phone off for 1 hour, 24 hours, 72 hours, 7 days. Record which relays
  still return the wrap.
- **Background test**: does a foreground service keep the socket alive on the test phone(s) overnight and through
  Doze? Battery drain per hour.
- Exit criteria: >=2 relays return messages after 24 h; foreground service survives an overnight run on the test
  devices; SDK usable from Kotlin without blocking issues. If not: reconsider (see 03: own relay, or email model).

## Phase 1: Transport foundation

- Add `Transport` interface, `NostrTransport`, relay pool with health tracking, payload codec (v1 JSON), outbox with
  retry/backoff, dedupe. Injectable proxy/socket factory (for Tor later).
- Nostr identity generation and Keystore-wrapped storage.
- Unit tests for codec, dedupe, ordering, outbox state machine. Instrumented test for key storage.
- Exit: two debug builds exchange text via public relays, with SMS code still present but unused.

## Phase 2: Pairing and contacts

- QR v3 encode/decode (keep v1/v2 decode), pairing stores `npub`, Tink keys and relay hints.
- DB migration 9 -> 10, contact model changes, "legacy SMS contact" state.
- Key-change warning re-keyed on `npub`.
- Exit: pair two phones by QR only; existing contacts survive the migration.

## Phase 3: Messaging features

- Text, read receipts (id based), location pins/requests/declines with expiry, trust levels and DND gating (logic
  moves from `SmsReceiver` into a transport-agnostic `IncomingMessageHandler`).
- Notifications, unread counters, live UI refresh (existing `NEW_MESSAGE` broadcast or a Flow).
- Group chat via per-member fan-out.
- Exit: feature parity with the current app for text, pins, receipts and groups.

## Phase 4: Background delivery

- Foreground service holding relay connections; notification channel and user-visible explanation; restart on reboot
  and network change; WorkManager polling safety net.
- Battery and reliability measurement across two or more phone brands if available.
- Decision gate: is reliability acceptable? If not, design the optional notification bridge (04 section 6, option C).
- Exit: messages arrive with the app closed and the screen off, within an agreed latency, on the test devices.

## Phase 5: Media

- Encrypt-then-upload to Blossom, kind 15 file messages, download/decrypt/cache, thumbnails.
- Fallback server list, size limits, failure UX.
- Exit: send and receive images (and short video) end to end.

## Phase 6: Key rotation and hardening

- Move `KeyRotationReceiver` broadcast onto the transport (signed rotation payload), grace-period behaviour preserved.
- Rate limits, spam/unknown-sender handling ("message requests"), relay-list (kind 10050) publish and fetch.
- Security review pass (`/security-review`), backup-rules check, log scrubbing.

## Phase 7: Tor option

- Phase 7a: Orbot SOCKS integration and the settings UI/status/explanations from 05, with leak tests.
- Phase 7b (optional): embedded Tor.
- Exit: with Tor on and a packet capture running, no traffic leaves except to the Tor entry (including DNS and map
  tiles or documented exceptions).

## Phase 8: Remove SMS, release prep

- Delete SMS/MMS code and permissions (list in 06), drop default-SMS flow.
- Update `README.md`, `CLAUDE.md`-adjacent docs, `plan.md`; write the user-facing "how Relay works and what it does
  not hide" text.
- Migration test from a real v9 install. Release checklist.

## Test plan (cross-cutting)

- **Delivery matrix:** both online; recipient offline 1 h / 24 h; sender offline then online (outbox); one relay down;
  all relays down then recovered; duplicate delivery; out-of-order delivery.
- **Security:** wrong-key message rejected; spoofed sender rejected; replayed wrap deduped; rotation while messages are
  in flight; key-change warning; secrets absent from backups and logs.
- **Networks:** Wi-Fi to Wi-Fi, cellular to cellular, Wi-Fi to cellular, captive portal, VPN on, airplane toggle.
- **Devices:** at least two physical phones (a real SIM is no longer needed for messaging). Emulator usable for UI.
- **Regression:** map, pins, groups, app lock, DB migration.

## Success criteria for the rebuild

1. No SMS/MMS permissions and no default-SMS prompt in the shipped app.
2. A message sent to a phone that is off is delivered within a minute of it coming online (relay retention permitting).
3. With the app closed, a message notification appears within an agreed latency on the tested devices.
4. All existing user-facing features still work, with encryption on for every paired contact.
5. Tor mode passes the leak test in Phase 7.
