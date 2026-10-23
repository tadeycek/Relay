# Android ↔ Linux forced Cloudflare TURN/TLS443 evidence

## Outcome

Real generated-audio exchange **PASS** on Android emulator `emulator-5556`
(`localbeat_wp11_16k`) and native Linux. Both endpoints configured with only
`turns:turn.cloudflare.com:443?transport=tcp` and RELAY ICE policy. Both selected
candidate pairs were **relay/relay**, with local `relayProtocol=tls` exposed and
verified on **both** platforms.

This proves the TLS443-only configuration works with no UDP TURN or direct ICE
choice configured. It does **not** prove an independent firewall blocked UDP;
no host firewall, billing, deployment, or provider resources were changed.
Generated PCM is not physical microphone capture or a listening-quality test.

## Reproduce

From repository root, with the ignored `.local/realtime.json` populated and the
owned AVD booted on port 5556:

```sh
export JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1
export ANDROID_HOME=/home/tadej/Android/Sdk
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" -Dorg.gradle.jvmargs=-Xmx768m --max-workers=1 --dependency-verification=strict -p linux-port/spikes/calls installDist
./gradlew --no-daemon -Dorg.gradle.java.home="$JAVA_HOME" -Dorg.gradle.jvmargs=-Xmx768m --max-workers=1 --dependency-verification=strict -p linux-port/spikes/calls/android assembleDebug
python3 -m unittest discover -s linux-port/spikes/calls -p test_cross_platform.py -v
python3 linux-port/spikes/calls/turn-cross-platform-test.py
python3 linux-port/spikes/calls/check_cross_platform_mutations.py
python3 linux-port/spikes/calls/check_cross_platform_mutations.py --turn
```

Both strict-verification builds succeeded, without new dependencies. Offline
acceptance tests: **10 passed**, including per-platform direct/non-TLS negatives.
Provider shape/secret-safe failure/revocation tests: **4 passed**. Local native suite:
**3 passed**. Live native TURN suite: **3 passed** (positive and each independent
silent-source mutation). Linux JVM exited normally and Android disposal marker
was observed; no `System.exit`.

## Actual live suite metrics

| Run / receiver | RTP packets | RTP bytes | Decoded samples | PCM square sum | Generated frames |
|---|---:|---:|---:|---:|---:|
| Positive / Android | 352 | 28363 | 380160 | 24143399080470 | 780 |
| Positive / Linux | 403 | 32921 | 390720 | 24675108619561 | 800 |
| Silent Android / Android | 353 | 28444 | 376800 | 24249969165344 | 777 |
| Silent Android / Linux | 402 | 14159 | 387840 | 4481 | 800 |
| Silent Linux / Android | 353 | 12246 | 376800 | 2420 | 774 |
| Silent Linux / Linux | 403 | 32911 | 387840 | 25219148521810 | 800 |

All six rows: connected, selected local and remote candidates `relay`, local
relay protocol `tls`. Both muted sources still transported RTP and produced
nonzero comfort-noise PCM. Each mutation failed **only** the expected receiver's
`decoded energy below tone threshold` assertion; nonzero samples alone would
have been an invalid acceptance test.

## Signalling and credentials

The host requests short-lived credentials via Bearer-authenticated POST to the
Cloudflare TURN key `credentials/generate-ice-servers` endpoint, `ttl=600`, with
honest `User-Agent: Relay-WP02-Probe/1.0`. Handles dictionary or list `iceServers`.
Only the exact TLS443 URL is selected. API token/key stay host-side; ephemeral
username/password travel to Linux over stdin, then Android over the bounded
loopback bridge (ADB reverse). No credentials in command-line extras, no raw
SDP/candidates or provider response bodies in public output/evidence.

Issued test credentials have a 600-second TTL; native connections close after
each bounded run. No long-lived credentials were created. All three final live
suite credentials were explicitly revoked with HTTP204 responses after native
cleanup, including both expected-failure mutations. Earlier exploration credentials
were bounded by their 600-second TTL (last earlier expiry Unix `1792774310`).
Private runtime logs/metrics stay ignored. The owned reverse mapping was removed
with exact readback after each run; only `org.relay.callsprobe` was force-stopped.
The emulator's measured page size was 16384 bytes. After the final run,
`adb -s emulator-5556 emu kill` stopped the owned emulator; subsequent `adb devices`
confirmed it absent. No other emulator or device was touched.

## Findings and limitations

- Waiting for ICE gathering COMPLETE timed out even though usable TLS relay
  candidates existed. In relay-only mode the bounded exchange now starts after
  its first relay candidate; selected-pair stats still prove the actual path.
  The final Linux live configuration also disables adapter enumeration, using
  only the default route rather than enumerating Docker bridges. Final runs
  gathered one Linux and one Android candidate.
- A separate Python TLS connection to `turn.cloudflare.com:443` completed TLSv1.3.
- Provider default urllib User-Agent was blocked (parent independently identified
  HTTP403/code1010); explicit honest User-Agent resolves it.
- Android AGP reports its existing compileSdk36 compatibility warning; build passes.
- Synthetic emulator/native interoperability only; physical Android hardware,
  microphone capture, listening quality, independent UDP firewall enforcement,
  and production-app calling remain outside this evidence.
