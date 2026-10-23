# WP02 AC2 — live Cloudflare SFU, Android + Linux native peers

Synthetic generated tones (Android 660 Hz via `setAudioBufferCallback`, AudioRecord disabled, 10 ms pacing; Linux 440 Hz `CustomAudioSource`). SFU terminates media encryption: **not E2EE, not microphone/listening proof**. Local ICE only (no STUN/TURN) on both peers. Emulator-5556 / AVD `localbeat_wp11_16k` only.

Architecture: `SfuAndroid.py` (coordinator) owns the SFU secret and all provider calls (reuses `live_sfu_media` Provider: UA `Relay-WP02-Probe/1.0`, no redirects, size/time bounds, `wall_clock_bound`; same `validate_media` thresholds: packets>=10, bytes>=100, samples>=4800, RMS>500). Linux peer = existing `relay.spike.SfuProbe` child. Android peer = new `SfuAndroidActivity`/`SfuAndroidRun` receiving SDP only over a 127.0.0.1 `adb reverse` bridge (framed, 64 KiB bound, hello check). Secret never reaches Android/argv. Cleanup: force-close tracks, exact-session GET readback, unique journal `.private/sfu-sessions-*.json`, QUIT + Android disposal marker, exact reverse removal, force-stop only `org.relay.callsprobe`.

## Results (evidence/sfu-android-<case>-attempt<N>.json)
| case | attempt | result | android<-linux (pkts/bytes/samples/RMS) | linux<-android |
|---|---|---|---|---|
| positive | 1 | FAIL stage=publish: Linux SFU ICE FAILED/connection timeout (known flake); cleanup inactive/expired(410) | – | – |
| positive | 2 | PASS | 435 / 35073 / 425760 / 8335.17 | 412 / 33275 / 398400 / 8017.99 |
| android silent | 1 | PASS (expected negative) | 424 / 34186 / 419040 / 8322.06 | 408 / 14377 / 393600 / **0.10** rejected |
| linux silent | 1 | PASS (expected negative) | 427 / 14840 / 419040 / **0.08** rejected | 409 / 33302 / 395040 / 8048.25 |

Negatives: RTP flows on the silent stream (packets>400) yet the receiver's `PCM energy assertion` rejects; the other direction passes the full positive assertion. All cleanups `inactive`, native exit normal, Android cleanup marker seen, reverse mapping removed, emulator killed.

## Tests
`python3 -m unittest test_sfu_android` → 31 tests OK (also with `python3 -O`). RED: module absent (`evidence/sfu-android-red.txt`, first-run import failure). Mutation checks on real data (`evidence/sfu-android-mutations.json`): energy check disabled, threshold removed, wrong platform label, positive-as-negative all rejected. Strict-verified build `assembleDebug` succeeded (`evidence/sfu-android-build.txt`).

## Limits
Emulator, loopback signalling, single run per case; SFU interop flakiness ~1 in 3 observed on first positive attempt. Android edits: new activity + manifest `<activity>` entry only; `MainActivity`/`CrossPlatformRun`/build.gradle untouched.
