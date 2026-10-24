# WP07 desktop link evidence — becoming a linked device

Status: **the desktop link flow works end-to-end against a real local Worker; no phone involved.**
Date 2026-10-24, branch `feat/linux-port`, base `2b11c17`. The implementing agent ran out of provider
credit mid-task; the parent diagnosed two harness faults, fixed one test bug and one contract bug, and
re-ran everything.

## What exists (`desktop/`)

- `DesktopLinkFlow.kt` — pure flow: ephemeral HPKE keypair + device-auth keypair (0600), 32-hex device
  id, link code, `LinkQr.encode`, poll `GET /v1/link/<slot>` with `x-relay-link-proof`, open the bundle,
  map every `LinkError` to a user-facing state, import, then `DELETE` the slot **only after** the local
  commit succeeded.
- `DesktopLinkHttp.kt`, `DesktopLinkKeys.kt`, `DesktopStoreLinkImporter.kt` — real HTTP, key storage,
  idempotent import (no duplicate contacts, newer local settings not overwritten).
- `Main.kt` — a real "Link this computer" screen replaces the old developer placeholder; the window
  default is now 900×800 with a 760×740 floor.
- After linking the transport signs with the device auth key and sends `?device=<id>`.

## Verification (parent run)

```sh
unset RELAY_WORKER_DIR            # see "Harness faults" below
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 RELAY_E2E=1 \
       RELAY_E2E_PERSIST=$PWD/.local/wp07-worker-state
./gradlew --no-daemon -Dorg.gradle.java.home=$JAVA_HOME --console=plain -q --rerun-tasks \
  :core:test :desktop:test :app:testDebugUnitTest :app:assembleDebug
./gradlew ... :desktop:createDistributable
```

- `:desktop:test`: **35 tests, 0 failures** (17 before; `DesktopLinkFlowTest` 17 with a fake HTTP layer,
  plus the real-Worker `DesktopLocalWorkerLinkE2ETest`).
- `:core:test` 252, `:app:testDebugUnitTest` 187, `:app:assembleDebug` OK, `:desktop:createDistributable` OK.
- **End-to-end against `wrangler dev --local` (the current server source):** a test "phone" publishes the
  sealed bundle, the desktop fetches/opens/imports it, the identity is migrated to an admin key, the
  desktop is enrolled under the QR's own device id, and it exchanges an encrypted message with a JVM peer
  as the linked device. Measured: `linkMs=26`, `deliveredMs=329`, 2 contacts, 1 group imported.
  (Local Worker on one machine; these are not network figures.)

## Screenshot — `wp07-desktop/link-screen.png`

Captured from the packaged app (`xwd -id <window>`, since `import -window root` hits `BadMatch` here).
It shows: title "Link this computer", the instruction line, a **QR image**, the grouped code, a live
"Waiting for the phone… (9 checks)  expires in 9:47", buttons "Copy link code" and "New code", and
"This computer: homelab Linux". No developer placeholder copy remains.

The QR was decoded from the screenshot with OpenCV (scratch venv, not a repo dependency):
`RELAYLINK:2:<64-hex code>:<32-hex device id>:<160-char ephemeral key>:<64-char auth key>` — the exact
v2 contract the phone parses. This is a decode of the rendered pixels, not a phone camera scan.

First capture, at the old 800×600 default, **clipped the card and squeezed both buttons until their text
vanished**; that was a real defect, fixed by the larger window and minimum size.

## Harness faults found and fixed (not product bugs)

1. My shell still exported `RELAY_WORKER_DIR` pointing at an old WP06 Worker snapshot without the link
   endpoints, so the E2E test hit 404s. Unset it; the test now uses the repo's Worker.
2. The agent's dead Worker kept running on port 8791 with persisted state, so the WP06 E2E failed with
   "Recipient is not on the mailbox". Stopped it and recreated the state directory.
3. The test allowlisted the admin key as a mailbox member; the server correctly refuses an admin key that
   is also a member. The test now allowlists only the identity key and the peer.

## Contract bug found

`LinkQr` carried a 16-hex device id; the server accepts only 32 lowercase hex for a linked device and
answers `400 bad_request` otherwise. Fixed in `:core` (`LinkQr.DEVICE_ID_CHARS = 32`); the E2E test now
enrols the QR's own id instead of substituting one.

## NOT RUN

- No phone, no phone-camera scan, no real two-device link.
- No `.deb`/AppImage, no real OS keyring, no deployed Worker.
- Passphrase/Argon2id (WP06 AC4) and scheduled retention (AC5) remain open.
- Typed-code entry (no camera) is not implemented; the code is shown grouped plus "Copy link code".
- The packaged app's GL init prints a Skiko warning and falls back to software rendering, as in WP06.
