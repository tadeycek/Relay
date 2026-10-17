# Verification record: 2026-10-16

Branch: `feat/rebuild`, based on `bb88bff`. This record describes test coverage, not
a guarantee that the app has no bugs or cannot be compromised.

## Completed checks

- Debug build, Android test APK build, 229 JVM tests (zero failures, errors or skips).
- Lint: zero errors, 13 warnings and 6 informational findings.
- Nine Android tests passed with relay recovery configured: real Keystore
  PIN verification, encrypted preferences, persistent lockout, owner recovery
  policy, notification attribution/no tap action, diagnostic consent, immediate
  Home/return relocking and calculator layout/arithmetic in landscape.
- Seventeen Python owner/protocol tests passed: authentication, strict report fields,
  expiry, revocation/deletion, bounded histories and database migration.
- Native Control Panel UI check passed using a temporary database: correct title,
  supplied icon, both tabs, individual diagnostic details and unblock action.
- Linux shortcut has the new `com.relay.ControlPanel.desktop` identity, Control
  Panel name and supplied figure icon. This checkout's obsolete shortcut was
  backed up and removed; launching the new desktop entry succeeded.
- Button-level emulator checks passed for decimal arithmetic, precedence,
  parentheses, percentages, negative numbers, divide-by-zero and error recovery.
  Three failures remained locked after a restart; calculator use continued; the
  Control Panel sent a signed unblock over public relays and cleared the lockout and the original code worked.
  Explicit diagnostic consent produced a report, and revocation deleted it.
- Two independent Android 15 emulator installs connected to default public Nostr
  relays, paired with approval on both devices, and exchanged text/emoji messages
  and replies. The production ML Kit decoder read the actual displayed QR image;
  decoded text was delivered through the production QR/NFC confirmation path.
  This verifies image decoding and pairing, not optical camera-to-screen capture.
- Invitation rules use a 30-minute maximum and single-use nonces. JVM tests cover
  rejection exactly at expiry, replay, malformed expiry and legacy-code bypasses.
  The issuer uses monotonic elapsed time, so changing its wall clock does not
  extend a nonce. A process restart invalidates unused invitations sooner.

## Media and preview checks

The complete two-emulator flow passed: actual QR-image decoding, mutual approval,
text/emoji messages and replies, and gallery photos in both directions. Each gallery
return showed the Calculator lock; after entering the original code, the attachment
remained selected. The recipient downloaded and decrypted the photo, and its SHA-256
matched the sender's compressed JPEG. Both conversation screens were visually checked.

Both default blob endpoints (`blossom.ditto.pub` and `blossom.data.haus`) also accepted
3 MiB encrypted test blobs. Downloaded bytes matched their hashes and decrypted to the
fixture; these separate endpoint-probe blobs were deleted successfully. No plaintext
photo is uploaded. The APK signature and Calculator label/SDK metadata were checked.

Live Control Panel recovery passed through public relays with no owner web server
listening: three wrong codes locked messaging across a restart; an actually received
command signed by an unauthorized key was rejected; the panel's signed command cleared
failures; the original code still opened messaging. A stale authentic command left a
newer lockout locked. Consented diagnostics reached the panel and were deleted after
revocation; the stored address was explicitly not observed through relays.

## Fixes in this update

The calculator adapts to landscape instead of overlapping/hiding middle key rows.
Account's Show my code opens the display rather than the scanner. Invitations
carry a versioned expiry and the display rotates expired/used codes.

Photo attachment no longer waits for a direct peer connection. The system picker
grants access to the chosen URI without requiring full-gallery or camera access.
Private navigation survives Calculator relocking, while the activity-level gallery/camera launchers
remain registered outside the private-screen lifecycle. Media uses
raw encrypted blobs with server-scoped Blossom authorization and blob-storage
endpoints, retaining support for older received media containers. Opening a chat
no longer probes direct peer IP candidates. Retired direct-transfer probes/offers are ignored,
so contacts cannot trigger legacy IP discovery or a listening socket.

The `relayPreview` build embeds only the owner's public key. The private owner key and local
service credentials are excluded. Recovery reports use a separate phone identity and the same
NIP-17 transport implementation; only a signed state from the pinned owner for the matching
installation can clear a lockout. Diagnostic revocations and their revisions commit atomically,
so an older opt-in report cannot restore deleted diagnostics. No IP address is inferred from
relay traffic, and no owner HTTP server is opened.

## Remaining limits

The packaged preview is debug signed and intended for testing. Recovery needs the original
Control Panel's private owner state and an available relay route on both sides. No private
owner state is published. Stable production signing and independent security review remain.

Physical-phone camera/NFC exchange, video capture/delivery, offline retention,
Doze/OEM background survival, battery usage, accessibility/font scaling and a real
Orbot route have not been verified in this session. Public relay/blob-server
availability and retention can change. Outgoing compressed media is still a
plaintext cache; received canonical media is encrypted at rest. The protocol has
no Double Ratchet and has not had an independent security audit.

## Reproduction

Use dedicated emulators and fresh app data; these tests set fixture names and PIN
4826 and send non-personal test text/images through public services.

```sh
rebuild/dev/build.sh -PrelayPreview=true assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
python3 -m unittest discover -s owner -p 'test_*.py'
# Install app-debug.apk and app-debug-androidTest.apk on both emulators first.
adb -s emulator-5554 shell am instrument -w -e class com.relay.app.calculator.CalculatorSecurityTest com.relay.app.test/androidx.test.runner.AndroidJUnitRunner
python3 rebuild/dev/test-two-emulators.py
```

The packaged APK is the final debug build. The test emulators retain fixture contacts and
use 4826 followed by +; a fresh installation chooses its own code.

Source tests and scripts are included. Local screenshots/logs, fixture databases,
tokens, phone identities, toolchain caches and signing keys are not distributed.
