# WP02 — live Cloudflare TURN follow-up

Date: 2026-10-23 UTC. Branch: `feat/linux-port`. **WP02 remains doing.**

## Independently reproduced by parent

Real Cloudflare-issued ephemeral credentials, Linux `webrtc-java:0.20.0`, and Android
`io.github.webrtc-sdk:android:144.7559.15` on owned emulator-5556 (AVD `localbeat_wp11_16k`).
No production call code, root application state, host firewall, billing/plan or Worker deployment was changed.

```sh
# Owned emulator launched via tracked background terminal; no other device was used:
"$HOME/Android/Sdk/emulator/emulator" -avd localbeat_wp11_16k -port 5556 \
  -no-window -no-audio -no-boot-anim -no-snapshot -memory 1024 -cores 2 -gpu host -skin 480x800

PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s linux-port/spikes/calls -p test_cross_platform.py -v
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/turn-cross-platform-test.py
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/check_cross_platform_mutations.py --turn
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/check_cross_platform_mutations.py
RELAY_SPIKE_LIVE=1 PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/live_turn.py
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/test_live_turn.py -v
```

Parent rerun results:

- Android↔Linux live TURN native suite: **3 passed**, no failures/skips. Positive and two independent real
  silent-source mutations; RTP continues for the muted source but the correct receiver rejects decoded energy.
- Both live endpoints select **relay/relay**, local **relayProtocol=tls**, using only
  `turns:turn.cloudflare.com:443?transport=tcp`. Positive parent run received **353 packets / 28,444 RTP bytes**
  on Android and **402 packets / 32,860 RTP bytes** on Linux; both decoded high-energy generated PCM.
- [Parent six measured rows](../spikes/calls/evidence/turn-cross-platform-parent.json) were parsed and counted
  programmatically from actual numeric native results; no provider stats were synthesized.
- Local Android↔Linux regression and both silent-source mutations: **3 passed**, no failures/skips.
- Cross-platform offline acceptance **10 passed**; provider credential helper **5 passed**, including a new
  parent RED→GREEN redirect-rejection test; Linux-only TURN offline acceptance **10 passed**.
- Linux↔Linux live forced-TURN audio and two data-channel messages passed, following actual selected-pair stats,
  with normal native disposal/JVM exit. [Actual Linux-only metrics](../spikes/calls/evidence/live-turn.json).
  Host-candidate acceptance guard additionally checked by an in-memory mutation (expected unit failure), then restored.
- All parent live credentials revoked with provider HTTP204. No long-term key/token was passed to Android, Java
  arguments or evidence. Exact ADB reverse cleanup and Android/Linux native cleanup markers were verified.
  Parent stopped its owned emulator after testing.

[Delegate Android live run](../spikes/calls/TURN-CROSS-PLATFORM-EVIDENCE.md) and
[Linux live details/failures](../spikes/calls/LIVE-TURN-EVIDENCE.md) retain their own execution snapshots.
Parent changes after delegation: reject provider redirects in generation/revocation; its new guard failed before the
handler existed, then passed; live positive/mutation suite was rerun after this change. Source/libs remain synthetic
feasibility probes, not shipping product integration.

## Review corrections and final rerun

Independent Android review found two blockers: an earlier native/ADB cleanup error could skip revocation, and a
revoke call embedded in `assert` disappeared under Python optimization while a success marker still printed.
Both were corrected using nested `finally` cleanup and an unconditional revoke call with an explicit status check.
New offline tests execute the actual cleanup AST, including optimized compilation: **20 combined cleanup/acceptance
tests passed**, plus **10 cleanup tests passed with `python -O`**. The fix agent established RED before each fix;
parent independently reran both sets.

After those fixes the parent booted its owned emulator again and reran the **entire 3-test live native suite**:
positive plus both real silence mutations passed; three revocations again confirmed HTTP204. Final numeric rows:
[cleanup-fixed live metrics](../spikes/calls/evidence/turn-cross-platform-cleanup-fixed.json). The owned emulator
was stopped and subsequent `adb devices` showed no devices. A fresh independent cleanup re-review passed with no
remaining blockers, independently running all 10 cleanup tests normally and under `-O`. The earlier measured snapshot
is retained, not rewritten to hide this correction. No production-app code or real device was involved.

## Acceptance boundaries

- **AC1 partial:** actual Android↔Linux generated media passes both direct and forced Cloudflare TURN/TLS443.
  Independently enforced UDP-blocking remains open. Sole TLS443 configuration is not firewall enforcement.
  `sudo -n true` needs a password and unprivileged user namespaces are denied; no host rules were changed.
- **AC2 open in this evidence:** this is TURN, not an SFU publish/subscribe or dashboard-egress measurement.
- **AC3 remains partial:** previously measured actual encrypted Linux signalling fits the current envelope cap;
  final Android/TURN signalling codec specimens are still separate work.
- **AC4 narrowly observed:** existing account accepted creation of SFU app/TURN key without an upgrade or payment
  step in the MCP workflow. Whether a payment method already existed, free-tier eligibility, dashboard accounting
  and hard spending limits are not established by these requests.
- **AC5 open:** no FrameCryptor/encoded-transform interop or group-frame E2EE. TURN transport verification is not
  evidence that the SFU cannot read group media.
- Physical phones, microphones/headsets/listening quality, minimum Android version and macOS remain untested.

No new dependency pins were introduced. No merge into `main` is authorized or performed.
