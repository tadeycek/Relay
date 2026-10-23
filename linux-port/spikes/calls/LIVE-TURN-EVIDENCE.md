# Live Cloudflare TURN/TLS443 — Linux native proof

Date: 2026-10-23 UTC. WP02 remains **doing**; this is a standalone feasibility probe, not production calls.

## Actual execution

```sh
RELAY_SPIKE_LIVE=1 PYTHONDONTWRITEBYTECODE=1 \
  python3 linux-port/spikes/calls/live_turn.py
PYTHONDONTWRITEBYTECODE=1 python3 linux-port/spikes/calls/test_live_turn.py -v
```

The runner reads mode-0600 ignored `.local/realtime.json`, requests real 600-second Cloudflare TURN credentials,
compiles the existing Linux native probe against the already-pinned distribution jars into ignored private classes,
and passes only ephemeral ICE credentials in the child environment. It never sends long-term credentials to Java or
Gradle, follows redirects, or prints raw provider responses. Native diagnostics stay in mode-0600 private logs.
The raw SDP/ICE size-export option is explicitly removed from the child environment.

Both peers use **RELAY-only**, with the sole TURN URL `turns:turn.cloudflare.com:443?transport=tcp` and normal
certificate verification. Two distinct data-channel messages and three seconds of generated audio were exchanged.
`transport.selectedCandidatePairId` is followed to the actual candidate-pair records; gathering a relay candidate
alone is not accepted as proof. Both selected pairs must have local/remote `candidateType=relay`, local
`relayProtocol=tls`, successful pair state and positive bytes sent/received. Each receiver also decodes nonzero PCM.
The JVM exits normally after native disposal. Issued credentials were revoked; provider HTTP **204** confirmed this.

Exact numeric output: [evidence/live-turn.json](evidence/live-turn.json). Each success carries UTC start/completion
 timestamps so an older success cannot be mistaken for a failed rerun. A failed rerun does not rewrite past evidence.
This is actual live output, not a fixture.
The separate `test_live_turn.py` fixture is clearly marked offline parsing/acceptance only and is not provider evidence.
Its **10 tests passed**. An in-memory mutation allowing host candidates caused the exact host-rejection test to fail;
restoring the unmodified module passed. This acceptance guard uses a mutation check, not a claimed first-run RED.
The initial opt-in test failed because the runner was absent, before the runner was added.

## Failures found rather than hidden

- Python urllib's default User-Agent received HTTP403, plain-text Cloudflare **1010**, before reaching credential
  validation. An explicit honest `Relay-WP02-Probe/1.0` User-Agent allowed generation and revocation.
- Native gathering initially exceeded the 25-second bound despite successful TURN allocations. Native diagnostics
  showed numerous Docker/virtual interfaces being enumerated. Restricting **this probe** to the default route with
  `PortAllocatorConfig.setDisableAdapterEnumeration(true)` made gathering and the exchange complete, without a timeout
  increase or relaxed certificate validation. This is not yet a production interface-selection policy.
- The successful run still emitted numeric ICE error701 events for unsuccessful candidates before finding the
  successful route. These were not suppressed or misrepresented as a wholly error-free candidate search.

## Boundaries

- Linux↔Linux only; Android live results, when available, are separate evidence.
- Synthetic tones and decoded PCM are not microphone/headset/listening tests.
- There were **no host firewall changes**. TLS443-only relay configuration is verified, but an independently
  enforced UDP-blocking test is **not**. `sudo -n true` requires a password and unprivileged `unshare -Ur true`
  is not permitted here; neither constraint was bypassed.
- No billing/plan change or Worker deployment. This small test does not establish account-wide egress/billing or
  whether a payment method existed before resource creation.
- Java's short-lived ICE credentials are in its process environment and can be inspected by the same OS user/root;
  absence from CLI arguments is not isolation from the host user. If parsing fails before the username is recovered,
  or revocation is unreachable, the 600-second TTL remains the fallback; successful evidence requires confirmed revocation.
- No SFU media or frame-E2EE claim is made by this test. No dependency or checksum pins changed.
