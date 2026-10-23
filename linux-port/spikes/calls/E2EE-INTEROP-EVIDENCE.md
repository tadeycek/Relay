# WP02-AC5 — Frame-level E2EE interop: Android `FrameCryptor` ⇄ Linux `webrtc-java` transform

**Verdict: INTEROPERABLE (positive).** Android's native `org.webrtc.FrameCryptor` (AES_GCM) output can be encrypted/decrypted byte-for-byte by a ~60-line standard-library AES-GCM implementation on Linux (`RTCRtpSender/Receiver.setTransform`), in both directions, locally and through the live Cloudflare SFU. Synthetic generated tones, emulator-5556 (`localbeat_wp11_16k`) + native Linux; not microphone/listening/physical-device proof.

## 1. API facts (javap, AAR `io.github.webrtc-sdk:android:144.7559.15`, `webrtc-java:0.20.0`)
Android (Java): `FrameCryptorFactory.createFrameCryptorKeyProvider(shared, ratchetSalt, ratchetWindow, uncryptedMagicBytes, failureTolerance, keyRingSize, discardWhenNotReady, PBKDF2|HKDF)`, `createFrameCryptorForRtpSender/Receiver(factory, rtp, participantId, AES_GCM, provider)`; `FrameCryptor{setEnabled,setKeyIndex,getKeyIndex,setObserver,dispose}`; `FrameCryptorKeyProvider{setSharedKey,setKey(participant,idx,bytes),ratchet*,export*,setSifTrailer}`; observer states `NEW OK ENCRYPTIONFAILED DECRYPTIONFAILED MISSINGKEY KEYRATCHETED INTERNALERROR`. Only algorithm: `AES_GCM`.
**Custom Android frame transform: no Java API.** `RtpSender.setFrameEncryptor(FrameEncryptor)` / `RtpReceiver.setFrameDecryptor` exist, but `FrameEncryptor`/`FrameDecryptor` are interfaces with only `long getNativeFrameEncryptor()` (a native C++ pointer) — implementing a custom layout needs JNI C++ (no NDK on this host). There is no encoded-insertable-streams / `setTransform` on `RtpSender/RtpReceiver` in this AAR. ⇒ **Android must use FrameCryptor; Linux must adapt to it** (Q3 answer: a Relay-defined layout is NOT feasible on Android without native code).
Linux: `RTCRtpSender.setTransform(RTCEncodedFrameTransformer)` / `RTCRtpReceiver.setTransform(...)`; `transform(RTCEncodedFrame)`; frame: `getData()`, `setData(ByteBuffer|byte[]|byte[],off,len)`, `drop()`, `getSize/Timestamp/Ssrc/PayloadType`; audio adds `getSequenceNumber/AudioLevel/ContributingSources`. **Read AND replace works on both send and receive** (verified live: payload grows by 30 bytes on send; decrypted payload set on receive is decoded to the right tone). Also `RTCEncodedAudioFrameInjector` exists (not needed). Transform must run on the callback thread (frame has owner-thread check).

## 2. Observed wire layout (Q1) — verified by experiment, not assumed
Linux receiver captured 60 Android-encrypted frames and tried a hypothesis matrix (key schemes × AES-128/256 × clear-header 0–4 × AAD header/none × 13/14-byte trailers). Exactly one authenticated, 60/60, for AES-128 **and** AES-256 shared material (`evidence/e2ee-layout-scan-{128,256}.json`):
```
frame = hdr[1] | AES-128-GCM(ciphertext || 16-byte tag) | IV[12] | ivLength[1]=12 | keyIndex[1]
hdr  = first byte of the Opus payload (TOC), UNENCRYPTED, used as GCM AAD (tag authenticates it)
key  = PBKDF2-HMAC-SHA256(password=shared key material (16 or 32 B), salt=ratchetSalt given to the provider, 100000 iterations) -> 16 bytes (AES-128 ALWAYS, even for 256-bit material)
IV   = 12 B in trailer; contains SSRC and RTP timestamp (observed in 60/60 frames; endian-agnostic test), all 60 distinct; receiver reads it from the trailer, so the sender may use any unique IV
```
Overhead = 1 clear byte stays + 16 tag + 14 trailer = **+30 bytes/frame** (frames 90–134 B on the wire, Opus 20 ms). `keyIndex` in trailer is what the receiver selects the key with.
- HKDF provider mode: **not matched** with the tested parameters (empty info, salt-as-info) → unresolved; use PBKDF2 (`FrameCryptorKeyDerivationAlgorithm.PBKDF2`).
- There is no way to give FrameCryptor a raw AES key: material always goes through the PBKDF2 derivation (Linux reproduces it).
- ratchet / per-participant `setKey` / sifTrailer / magic bytes: **not tested** (shared-key mode only).

## 3. Per-case results (local ADB-reverse loopback; `e2ee_run.py <case>`; 1 attempt each, all first-attempt PASS; 8 s runs)
Acceptance = receiver decoded PCM with RMS>500 **and** ≥50 % of energy at the expected tone (Goertzel) — RMS alone is insufficient: undecrypted ciphertext decoded as Opus gives **loud noise** (plain_android: Android RMS 9339, tone share 0.00; plain_linux: Linux RMS 9475, tone 0.00).
| case | Android receiver | Linux receiver | result |
|---|---|---|---|
| A match (AES-128 material) | RMS 8353, tone 0.99, states OK | RMS 8105, tone 1.00, 400 ok/0 fail | PASS |
| A match, 256-bit material | RMS 8362, tone 0.99 | RMS 8107, tone 1.00, 400/0 | PASS (still AES-128 derived key) |
| B wrong key on Linux | RMS 0, DECRYPTIONFAILED | RMS 0, 0 ok/401 fail | PASS (both rejected) |
| B wrong key on Android | RMS 0, DECRYPTIONFAILED | RMS 0, 0 ok/401 fail | PASS |
| B plaintext Android→encrypted Linux | (garbage) RMS 9339, tone 0.00 | RMS 0, 0 ok/400 fail | PASS (Linux detects) |
| B encrypted Android←plaintext Linux | RMS 0, DECRYPTIONFAILED×2 + MISSINGKEY | (garbage) RMS 9475, tone 0.00 | PASS (Android detects) |
| C tamper: Linux flips 1 ciphertext bit per frame | RMS 0, DECRYPTIONFAILED | tone OK | PASS (Android drops) |
| C tamper on Linux receive | tone OK | 0 ok/400 fail, RMS 0 | PASS (Linux GCM rejects) |
| C replay: Linux resends one old valid ciphertext frame | states OK (no DECRYPTIONFAILED); decode not a tone | tone OK | **replay NOT rejected** at frame layer |
| rotate keyIndex 0→1 at +3 s (both senders; receivers not told) | phase-2 RMS>500, OK | trailer idx seen {0:150,1:350}, 500 ok/0 fail | PASS |
| DTX (`usedtx=1` in both SDPs, silent source) | OK | 47 ok/0 fail | see §4 |
Dev iterations (not hidden): first wrong-key runs FAILED the acceptance only because the Android observer reported nothing with ratchetWindow=16/failureTolerance=-1; with `ratchetWindowSize=0, failureTolerance=0` DECRYPTIONFAILED is reported. (Which of the two options suppresses it was not isolated.) An initial scan with a 1-byte-trailer hypothesis authenticated 0/60 until the 2-byte trailer (ivLength+keyIndex) was found.

## 4. DTX / silence
With `usedtx=1`, Android DTX frames are 1 byte (Opus TOC). Observed: the 19 Android 1-byte DTX frames arrived as the 30–60 B bucket (1 clear + 16 tag + 14 trailer = 31 B) and **all authenticated on Linux** → small frames are **encrypted/authenticated, not passed in clear**; GCM over an empty plaintext with AAD=TOC works. Zero-length frames (3 per run, at stream start) are passed through **unencrypted** (nothing to protect; both FrameCryptor and our code skip them). Linux's own 1-byte DTX frames (19) were encrypted to 31 B and Android reported only `OK`. Cost: DTX packets grow 1 B → 31 B. No break below header size (header = whole frame).

## 5. D — through the live Cloudflare SFU (`SfuE2ee.py`, reuses SfuAndroid coordinator by patching, no edits to SFU files)
Coordinator owns SFU secret and the per-run key (private pipe/loopback bridge only). `RELAY_SPIKE_LIVE=1`, UA `Relay-WP02-Probe/1.0`, tracks force-closed, readback `inactive` for all 4 sessions, reverse removed, Android cleanup marker, normal Linux exit.
| case | attempt | result | android←linux | linux←android |
|---|---|---|---|---|
| match | 1 | PASS | 424 pkts, RMS 8315, tone@440 | 410 pkts, RMS 8054, 407 ok/0 fail, tone@660 |
| wrong_key (Linux) | 1 | PASS (expected negative) | 423 pkts flowing, RMS 0, DECRYPTIONFAILED | 405 pkts flowing, RMS 0, 0 ok/402 fail |
The SFU forwards the encrypted Opus payload untouched, so SFU-terminated media encryption is not a barrier to frame-level E2EE. No connection flakes seen (known ~1/3 flake did not occur in 4 session-pairs).

## 6. Tests
- `test_e2ee_accept.py` (13) + `test_e2ee_run.py` (5) + `test_sfu_e2ee_accept.py` (6) = **24 python tests OK** (RED first: `evidence/e2ee-accept-red.txt`, `evidence/sfu-e2ee-accept-red.txt` = module-missing import errors).
- `E2eeSelfTest` (Java, no WebRTC): 27 checks incl. RFC 5869 HKDF + PBKDF2-SHA256 vectors, bit-flip in ciphertext/header/IV rejected, wrong key, unknown key index, plaintext frame, tiny frame (`evidence/e2ee-selftest.txt`; RED 24/3 then GREEN 27/0).
- Mutation check `check_e2ee_mutations.py` → `evidence/e2ee-guard-mutations.json`: 12 mutants (incl. key-mismatch/invalid-side assertion removed, tone-ratio dropped, RMS dropped, failure-evidence dropped, SFU variants) all KILLED; unmutated baseline PASS. One SFU mutant initially SURVIVED (overlap), a targeted test was added, re-run: all killed. RED established by mutation check, not first-run failure, for the acceptance logic that was written alongside tests.
- Scripts refuse `python -O` (acceptance uses `assert`).

## 7. Limits / not verified
Emulator only (no physical device); single attempt per case; 128-bit derived key only (no AES-256 via FrameCryptor); per-participant keys, ratcheting, SIF/magic bytes, video, >2 parties untested; HKDF mode not reproduced; Android observer counts are state transitions, not per-frame counts; replay protection absent at this layer.
