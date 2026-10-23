# WP02 AC5 - frame-level E2EE interop, Android FrameCryptor + Linux webrtc-java

Date: 2026-10-23 UTC. Branch `feat/linux-port`. WP02 stays `doing`.

**Result: interoperable.** Android `FrameCryptor` (AES-GCM) frames are decrypted by a standard AES-GCM implementation on
Linux via `webrtc-java` `setTransform`, and Linux-encrypted frames are decrypted by Android. Full write-up with the
experiment that found the layout: `../spikes/calls/E2EE-INTEROP-EVIDENCE.md`.

Observed layout (found by testing a matrix; exactly one combination authenticated 60/60 captured frames):
`hdr[1] (first Opus byte, clear, used as GCM AAD) | AES-GCM ciphertext || 16-byte tag | IV[12] | ivLen=12 | keyIndex`.
Key: `PBKDF2-HMAC-SHA256(shared material, ratchet salt, 100000 iterations)` -> 16 bytes (always AES-128, even from
256-bit material). Overhead 30 bytes per frame. HKDF mode was not reproduced; PBKDF2 is the working mode.

## Parent independent rerun (fresh strict build, fresh emulator boot, outputs to scratch)

- Offline: 24 Python tests OK; mutation checker 12/12 killed, baseline PASS.
- Local Android<->Linux, one attempt each, all PASS: `match` (both decode the tone, Linux 401 frames ok / 0 failed);
  `wrong_key_linux` and `wrong_key_android` (RMS 0 on both, Linux 400 frames failed / 0 ok, Android DECRYPTIONFAILED);
  `plain_android` (plaintext sender detected by the encrypting receiver).
- Live Cloudflare SFU, Android + Linux, one attempt each, both PASS: `match` (Android<-Linux RMS 8,324;
  Linux<-Android RMS 8,052, 407 frames ok / 0 failed); `wrong_key` (RTP still flowing, 430/408 packets, but RMS 0.0 both
  directions, Linux 406 frames failed / 0 ok). All tracks inactive on readback, emulator stopped, no reverse mapping left.
- The delegate additionally reported (not re-run by the parent): tamper drop both directions, key-index rotation
  0 -> 1 mid-call (150 frames idx0, 350 idx1), DTX frames encrypted (1 byte -> 31 bytes), zero-length frames pass in clear.

## What this decides

- WP17 is feasible with Android FrameCryptor + webrtc-java; use this layout and PBKDF2 derivation, with a per-sender
  16-byte material derived from the group key at app level, and the key-index trailer as the rotation channel.
- The Android Java API gives no custom frame layout (FrameEncryptor/Decryptor only expose native pointers; no setTransform).
  A Relay-defined layout would need JNI C++/NDK, which is not available here. So follow FrameCryptor's layout.
- **No replay protection at frame level**: Android accepts a re-sent old valid frame. WP17 needs an app-level
  counter/replay check at least on the Linux side.
- Zero-length frames at stream start pass unencrypted (3 per run); harmless but note it.

## Not covered

Emulator only, synthetic tones, 2 parties. Not tested: per-participant setKey, ratcheting, SIF/magic bytes, video, more than
2 parties, key distribution over Relay's existing E2E channel (WP17 work), physical devices, macOS. SFU key-share
and removal-rekey design are still WP17 work. The SFU cannot read media only if keys never reach it; this spike
generates keys in the test coordinator-free path and does not prove the product's key distribution.
