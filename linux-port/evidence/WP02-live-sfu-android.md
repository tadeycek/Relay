# WP02 - Android + Linux through the live Cloudflare SFU

Date: 2026-10-23 UTC. Branch `feat/linux-port`. **WP02 stays `doing`.**

One Android native peer (`org.relay.callsprobe`, webrtc-sdk 144.7559.15, owned emulator-5556) and one Linux native
peer (webrtc-java 0.20.0) each publish a generated tone to a real Cloudflare SFU and subscribe to the other:
2 directed streams. The Python coordinator owns the SFU secret and all provider calls; SDP reaches Android only over
the bounded loopback ADB-reverse bridge. No secret, SDP or session id appears in evidence.

## Parent independent rerun (fresh build, fresh emulator boot)

```sh
python3 -m unittest test_sfu_android            # 31 tests OK, also under python -O
RELAY_SPIKE_LIVE=1 python3 SfuAndroid.py --case none|android|linux
```

All three cases passed on the first attempt (delegate's run needed 2 attempts for the positive case; its first attempt
failed with the known Linux ICE connection flake).

- Positive: Android<-Linux 429 pkts / 34,587 B / RMS 8,320; Linux<-Android 410 pkts / 33,336 B / RMS 8,061.
- Android source silent: Linux<-Android RMS 0.10 rejected (RTP still 413 pkts); Android<-Linux RMS 8,333 passes.
- Linux source silent: Android<-Linux RMS 0.08 rejected (RTP still 425 pkts); Linux<-Android RMS 8,057 passes.
- Every run: tracks force-closed and read back inactive (4/4), Android disposal marker seen, Linux exit normal,
  exact ADB reverse mapping removed, emulator stopped, package not running afterwards.

Delegate evidence: `../spikes/calls/SFU-ANDROID-EVIDENCE.md`, `../spikes/calls/evidence/sfu-android-*.json`
(RED capture, mutation results, build and unit logs). Mutations: disabling the energy check or threshold makes the
silent cases fail as "silent source not rejected"; wrong platform labels are rejected.

## Boundaries

Emulator only, synthetic audio, local ICE only. Not E2EE (the SFU can read the media), not microphone or listening
quality, not a physical phone, not a production call engine. Connection flakiness (~1 in 3 earlier runs) is seen
and not solved. WP02 AC2 is now met for Android+Linux publish/subscribe; per-minute egress comparison is still
unmeasured. Open: FrameCryptor/E2EE interop (AC5), independent UDP-blocking, billing/egress, 5-party and
17-party load, physical devices.
