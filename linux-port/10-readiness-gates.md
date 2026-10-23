# 10. Pre-implementation review and binding readiness gates

Status: planning only. This review corrects assumptions in the initial design. **Where this file conflicts with
an earlier proposal or estimate, these gates take precedence.** No package may ship by treating a hypothesis as
verified. The selected product architecture remains Compose Desktop, existing Cloudflare messaging, P2P 1:1 audio,
and Cloudflare Realtime SFU group audio, with Linux packages and an unsigned macOS DMG.

## G1 — Shared-identity linking is a proposal, not a security boundary (WP01, WP04, WP05)

The shared-identity/per-device-cursor proposal replaces the earlier chat's separate-identity fan-out proposal.
It avoids requiring all contacts to upgrade. However, **a `device=primary` query parameter is not proof of being
the primary phone**: every device holding the Nostr secret can sign it. Likewise, `O:primary` in an E2E sync body
is not cryptographic proof of phone origin. Unlinking a cursor does not revoke a shared identity key.

Before implementing linking, WP05 must add a device authorization contract: separate per-device authentication
keys enrolled by a phone-only administration key, bound to the shared mailbox identity. Only that administration
key may enroll/revoke devices. Legacy NIP-98 identity auth for an enrolled identity must be restricted to the
primary and disabled after the primary migrates; otherwise it bypasses revocation. Legacy **senders** still send
to the same recipient pubkey and need no update. Enrollment binds device id, device public key, identity,
creation time and protocol version; each request signs method, exact URL, body digest, timestamp and nonce.
The server must check revocation on HTTP and existing WebSocket operations, including media and SFU proxy calls.

This prevents an unlinked device from continuing to use this server but cannot take away the Nostr/Tink secrets
it already learned. Full cryptographic revocation requires rotating/replacing the shared identity and re-pairing.
Never describe cursor removal as erasing keys from a remote computer. WP05 remains blocked until tests demonstrate
that a desktop cannot forge primary authority and that revoked keys cannot reconnect or fetch blobs.

The key-export/link-bundle path is also a security gate: prove Tink private-key export/import works with the actual
Android API and does not require unsafe production debug exports. A link bundle must be sealed/authenticated,
rate-limited and expire. Do not put link codes or proofs in server access logs, crash logs or evidence. Link-slot
GET should use a proof header rather than a query parameter to reduce URL leakage. Retry must not consume a bundle
before the desktop can durably store it; use a short retrieval lease plus explicit completion instead of deleting
on the first network read. Exercise lost-response recovery and interrupted enrollment.

## G2 — Cursor lifecycle and media recipient snapshots (WP04)

Persist a per-identity high-water mark and last acknowledged floor before deleting deliveries. The current queue
cannot reconstruct a previous ack position from already-deleted rows. A new device must start at a precise,
transactional enrollment boundary: messages after the snapshot/head must not disappear during registration.
Bootstrap snapshots and subsequent contact/group sync events need an ordered barrier and monotonic revisions.

Dormancy must be evaluated **before** `last_seen` is refreshed. Set a visible gap watermark before allowing ack;
never silently delete missing events on resume. Reactivation must not lower the deletion floor.

Media snapshots must use the same dormancy policy or a desktop disconnected after upload will hold every file
for eight days. Test upload→new-device enrollment→confirm races, repeat uploads, stale recipient snapshots and
revoked devices. Do not claim all history is server-free: pending ciphertext and operational metadata remain until
ack/expiry, with revocation and dormant-device exceptions stated in the UI.

## G3 — Sync and attachment bounds (WP03, WP05, WP09)

There is **no assumed existing chunking rule** for arbitrary sync bodies. Specify a bounded, versioned codec before
WP05: unique sync id, revision, part index/count, total bytes, content hash, expiry, duplicate handling and maximum
concurrent assemblies. Prefer individually bounded contact/group events instead of bulk messages. Wrapped SENT
copies must fit the **16 KiB body** after base64; use a compact binary/JSON schema rather than recursively nesting a
maximum-size `RelayPayload`. Malformed/unknown/oversized events are rejected without losing the inbox cursor.

Key rotation requires more than enqueue order: the new key event must be decryptable with the old key or an
independent stable self-sync key; using the new HPKE key to encrypt its own delivery is circular. Keep epoch ids,
retry failures, and test concurrent rotation, restart, failed local commits and out-of-order live delivery.
Process by durable sequence rather than arrival order. File/media SENT copies must include sender-device
confirmation so the sender's own pubkey does not hold every upload until expiry.

100 MiB generic files are a **proposed product cap**, not an existing feature. WP09 must use independent authenticated
parts and bind file id, index, count, total size and version in AEAD associated data (or an authenticated manifest).
Verify a full plaintext digest after assembly. Do not expose partially authenticated plaintext; stage privately
and publish only after final tag verification. Test zero-byte files (support explicitly), collisions, symlinks,
permissions, cancellation, disk exhaustion and per-account storage quotas. Memory and CPU measurements include
both the client and the server's current whole-body hash/auth path. Existing photos may use the JPEG container;
new file parts use a versioned raw encrypted format and must not assume container compatibility.

## G4 — Call signalling and wake-up semantics (WP02, WP11–WP14)

`answered_elsewhere` sent by the caller to the callee is **not** a self-authenticated message; validate it against
the established caller/call id. The winning callee device sends an authenticated self-sync cancellation too.
All packets bind caller, callee, device, call id, nonce, expiry and media mode. Bound decompression output and
candidate counts; deflate cannot be an unbounded allocation primitive. Validate glare and simultaneous accepts.

Urgent FCM must be tied to a short-lived server call record with caller/recipient/global abuse budgets, not a
freely supplied flag that lets any member trigger repeated high-priority pushes. FCM is best effort; a fresh invite
gets a maximum ring deadline and is never guaranteed to ring within five seconds. Reusing the message wake path
is a hypothesis, not evidence of incoming-call reliability. Android restrictions for background microphone access,
Telecom registration, notifications, force-stop and battery management need real-device tests; never open the mic
until a user accepts. Sideloading does not guarantee full-screen-intent permission.

## G5 — SFU room authorization, cleanup and E2EE (WP15–WP17)

Room secrets alone do not enforce group membership or removal. Freeze an authorized participant list at call
creation with a signed roster revision; authorize current devices against enrolled identities. Updating the group
roster must revoke a removed member's room sessions, close its tracks and subscriptions and rotate capabilities.
Fail closed on failed cleanup rather than merely hiding a publication. Do not proxy arbitrary provider URLs or
operations. Whitelist audio-only operations, validate ownership and source-track room membership, and bound SDP,
track counts, request concurrency and retries. If adding this list reveals group membership to the server, record
that metadata trade-off explicitly (it supersedes the claim that the server never knows the roster).

Use one room per group/revision with an atomic create-if-absent rule: concurrent starters must not create split
calls. Store only the minimal durable recovery/cleanup state. An empty room is not cleaned up solely by a
last-leaver message; crash/timeout alarms must close provider tracks, remove authorizations and wipe state with
bounded retries. Heartbeat loss alone does not stop flowing SFU media. A stale banner should query authorized
live state even for calls lasting longer than ten minutes. Control messages are metadata, not permanent chat
history; don't replay stale invites as active calls.

Cloudflare's documented 30-second no-packet track expiry means mute/DTX behaviour must be measured. If the track
expires, republish and rebind subscribers; do not assume disabling a microphone sends sufficient packets.
The SFU is not an audio mixer: each endpoint must receive and mix multiple tracks. Test 5 participants first and
load-test the proposed **17-participant product cap** before claiming that capacity. There is no unlimited group
size guarantee.

Group E2EE is not built into generic WebRTC SRTP. Android FrameCryptor and Java encoded transforms are not assumed
wire-compatible. WP02/WP17 must pin a reviewed frame format/key derivation, nonce uniqueness, replay handling,
key epochs and rekey-on-removal. No silent mixed-mode downgrade. A non-E2EE group voice release remains visibly
labelled **encrypted in transit, not end-to-end**, and must be explicitly accepted before release. E2EE is not a
prerequisite for producing this plan, but is a security release gate, not an incidental UI feature.

## G6 — Capacity and dependency claims are measured gates (WP01, WP02, WP04, WP09, WP16)

Official limits checked during this review:
- Workers Free: **100,000 HTTP requests/day, 10 ms CPU/request, 128 MB memory**. Network waits aren't CPU time.
- Durable Objects Free: **100,000 requests/day, 13,000 GB-s/day**, separate from Workers requests. Incoming WS
  messages have a 20:1 compute billing ratio; outgoing messages do not incur request charges.
- DO SQLite Free: rows read/written and stored-byte quotas are separate; indexes/deletions/auth replay records,
  alarms and storage operations must all be included. Existing README costs are pilot measurements, not universal
  per-message constants. Hibernation avoids idle duration only when handlers allow it.
- SFU/TURN egress allowance is separate from all of the above. Realtime egress and current account activation /
  payment-method requirements are checked on the account in WP02; do not assume an automatic hard spending cap.

Earlier chat statements that CPU "isn't an issue", a file costs simply 26 rows, and room logic is a few hundred
lines were not measured guarantees. Signature verification, hashing large bodies, retries, replay protection,
indexes and alarms must be profiled. Gate rollout on p95/p99 CPU, maximum heap, row writes and duration, plus
quota-failure UX. Keep all estimates labelled estimates. Use the calculated room formula in 02 with protocol
headers/TURN overhead and no assumed silence savings for the safety budget.

Every dependency/version/licence named in 02 is a **candidate**, not an implementation lockfile. Confirm POMs,
transitive native licences and the actual published APIs in the spike. The Willena driver uses SQLite3 Multiple
Ciphers with SQLCipher-compatible modes; it is not automatically the same binary/database format as Zetetic's
Android SQLCipher. New desktop databases may use a different local cipher format, but prove WAL/journal encryption,
wrong-key failure and migrations. `org.json`, Android BuildConfig references and KMP JVM/Android resolution are
explicit extraction checks. A small native runtime existing in a jar does not prove it loads on the target OS.

## G7 — Linux and unsigned macOS release gates (WP10, WP18)

AppImage and DEB are Linux-only. DMG is a separate Mac artifact built on macOS; no App Store submission or Apple
Developer paid identity is planned. Apple Silicon is the first target. Ad-hoc signing may still be required by the
runtime even though the release is called "unsigned" (no Developer ID, no notarization). Record the actual result.
Use Apple's Privacy & Security → Open Anyway route only for a trusted, checksum-verified artifact; do not advise
users to disable Gatekeeper globally or override a malware warning.

Set minimum supported OS/JDK/glibc and pin the Compose/Kotlin/JDK versions only after resolving compatibility.
Mac Keychain and microphone grants, Intel Mac support (not claimed), tray behaviour and cross-update access remain
target-platform tests. A headless CI build cannot verify a downloaded DMG's Gatekeeper flow or audible calls.
WP18 release requires a real Apple Silicon acceptance run; recording 'not run' is honest but **does not satisfy**
that acceptance criterion. Unsigned app updates must preserve stored keys and DB access despite identity changes.
Linux AppImage FUSE/extract-and-run and native-library extraction paths need clean-system testing too.

## Sources checked in this review

- https://developers.cloudflare.com/workers/platform/limits/ (Workers CPU, requests, memory)
- https://developers.cloudflare.com/durable-objects/platform/pricing/ (DO requests, duration, WS billing)
- https://developers.cloudflare.com/realtime/sfu/platform/pricing/ (separate SFU/TURN billing)
- https://developers.cloudflare.com/realtime/sfu/platform/limits/ (tracks/timeouts)
- https://support.apple.com/en-us/102445 (Gatekeeper and Open Anyway)

## Planning validation vs implementation evidence

This branch contains documentation only. Structural checks validate links, package ids, dependencies and acceptance
criterion counts. No Android/desktop build, live call, provider configuration, real-device acceptance or quota
benchmark was performed by writing these documents. All implementation WPs remain `planned`.
