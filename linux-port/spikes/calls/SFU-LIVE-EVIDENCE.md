# WP02 — actual Cloudflare SFU generated-audio proof

> Historical delegate snapshot. The [parent follow-up](../../evidence/WP02-live-sfu.md) subsequently executed
> the complete final-source four-run live suite successfully. Earlier tables/failures below are retained as recorded;
> the numeric `evidence/sfu-*` success reports now contain that newer parent run. One successful sweep does not
> erase the observed intermittent failures or prove long-duration stability.


## Result and limit

**Actual provider media was established:** three independent Linux native
PeerConnections/factories, each publishing one generated tone and receiving the
two other publications: **six directed media streams**. This is not the existing
API-only smoke. All six receiver-specific native RTP reports and AudioTrack PCM
sinks passed. The positive run completed in **13.07 s** and exited normally after
native disposal. Each of its nine allocated flows was force-closed, then the
exact session GETs returned three inactive track records per participant.

**Repeatability remains open.** Later reruns encountered native SFU connection
timeouts and one HTTP error during publication. A fresh four-run live suite did
not complete reliably. Do not describe this as a stable/production-ready SFU
implementation or an all-green final live sweep. The retained positive and three
negative reports below are actual earlier runs, not fabricated/replayed provider
responses. The final collection invocation recompiled the latest sources and
revalidated these existing numeric reports; it made no new live requests.

## Safe positive measurements

Participant and publisher numbers are local zero-based labels, not provider IDs.
PCM RMS is in signed 16-bit sample units. Each row is attached to the receiving
transceiver MID mapped from the provider's successful subscription result.
Native RTP statistics are requested for that specific RTCRtpReceiver.

| Publisher → participant | Inbound packets | Inbound bytes | Decoded samples | PCM RMS |
|---|---:|---:|---:|---:|
| 1 → 0 | 447 | 35,883 | 431,040 | 8,398.83 |
| 2 → 0 | 445 | 35,883 | 431,520 | 8,419.68 |
| 0 → 1 | 429 | 34,425 | 412,320 | 8,422.43 |
| 2 → 1 | 428 | 34,506 | 412,320 | 8,436.11 |
| 0 → 2 | 412 | 33,048 | 396,960 | 8,400.77 |
| 1 → 2 | 412 | 33,048 | 396,960 | 8,380.88 |

The media gate requires the exact stream count without duplicate directed pairs,
connected peers, at least 10 inbound packets, 100 inbound bytes, 4,800 decoded
samples, and mean square PCM energy at least 500². It does not accept mere nonzero
comfort-noise/startup samples.

## Real silent-source negatives

Each negative ran three real native participants through the SFU, but generated
zero PCM at one selected publisher. The affected two subscriptions still received
RTP and decoded samples, yet their RMS was approximately **0.08**, and the exact
`PCM energy assertion` rejected them. The four subscriptions from the other two
publishers passed independently. All nine track records were inactive on exact
readback in each negative, and all three negative native processes disposed and
exited normally.

| Silent publisher | Expected affected receivers | Runtime | Result |
|---|---|---:|---|
| 0 | 1, 2 | 13.39 s | Energy rejection verified on both; other four pass |
| 1 | 0, 2 | 13.62 s | Energy rejection verified on both; other four pass |
| 2 | 0, 1 | 12.82 s | Energy rejection verified on both; other four pass |

The final local suite also passed **8 unit tests** and killed **7/7 focused guard
mutations**: energy, RTP, stream count, per-track error, live opt-in, redirects,
and response-size cap. Initial core guards were observed failing before their
implementation; the later safety tests have explicit mutation-established RED.
Synthetic unit/mutation module copies stay under ignored `.private/`.

## Run commands

From `/home/tadej/repos/Relay/linux-port/spikes/calls`, with the existing pinned
WebRTC jars installed under `build/install/relay-calls-spike/lib/`:

```sh
# Entire new live attempt: compile, unit tests, guard mutations, then four live runs.
RELAY_SPIKE_LIVE=1 JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 python3 sfu_media_suite.py

# Revalidate the actual retained private numeric results without contacting Cloudflare.
JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 python3 sfu_media_suite.py --collect-existing

# Focused live attempt using compiled private classes (negative exits 1 by design).
RELAY_SPIKE_LIVE=1 python3 live_sfu_media.py --output .private/sfu-positive.json
RELAY_SPIKE_LIVE=1 python3 live_sfu_media.py --silent-publisher 0 --output .private/sfu-silent-0.json

python3 -m unittest test_sfu_media -v
python3 check_sfu_media_mutations.py
```

`--collect-existing` requires the original ignored private reports. The public
numeric JSON reports remain readable without those private files. The full live
suite stops on unexpected failures; it does not replace them with earlier data.
A negative counts as expected only after checking both affected receivers,
unaffected streams, normal exit and inactive provider readback.

## Implementation and provider handling

- New `SfuProbe.java` reuses only package-private SDP/await helpers from
  `CallsProbe`; it does not modify existing probes or the build configuration.
- Generated 48 kHz mono 10 ms PCM frames encode 440/660/880 Hz tones. There is no
  microphone or speaker device acquisition. One custom source/factory per peer.
- Python stdlib coordinator owns credentials and provider requests. Java and
  Python exchange bounded SDP over private process pipes, not command-line args.
- Runtime credentials are read from the existing private `.local/realtime.json`;
  no credential values, SDP, ICE addresses or provider session IDs enter public
  reports. Explicit honest `User-Agent: Relay-WP02-Probe/1.0` avoids the default
  urllib agent's observed HTTP 403 / Cloudflare 1010 rejection.
- `POST /sessions/new` uses no body and accepts the observed **HTTP 201**, alongside
  HTTP 200. Publication and subscription follow the current documented exchange;
  request-level and individual track errors are inspected. Successful allocations
  are retained for cleanup.
- Complete local ICE gathering is required before sending offers/answers. The
  final config uses local candidates and the SFU's public remote ICE endpoints.
  Separate Cloudflare STUN gathering exceeded 25 s both with and without
  default-route-only adapter enumeration; it is not used by this SFU proof.
- HTTP redirects are disabled, each request has a 15 s timeout and 65,536-byte
  response cap; private pipe replies have their own caps/timeouts. Provider/native
  negotiation has a 120 s deadline; cleanup has a separate 90 s provider budget
  and 20 s native normal-exit wait. The generator itself stops after 150 s.
- Final cleanup reconciles an exact session GET before closing, so a failed
  response cannot hide an allocation already made by the provider. It sends
  `force:true`, inspects each close result, and verifies each allocated MID is
  inactive/closed with another exact GET. Failures retain a failed result; an
  exact HTTP 410 readback is recorded as expired, not as a successful close.
- Native peers, acquired track/receiver/sender/transceiver wrappers, custom
  sources, factories and audio modules are disposed. No `System.exit`.

## Repeat failures and remaining acceptance

`evidence/sfu-live-repeat-failure.json` records a later bounded publication
failure: three sessions, no completed media matrix, native connection timeout;
one track was closed/inactive and two exact session readbacks returned HTTP 410
(expired). It is **not** counted as a pass. Another default-route-only attempt
failed and its three exact sessions were subsequently confirmed expired (HTTP
410); retained as `evidence/sfu-live-expired-attempt.json`.

An intermediate HTTP-error publication attempt exposed a cleanup hole: GET could
show an allocated active track despite the unsuccessful tracks/new response.
The final coordinator now reconciles exact-session allocations before closure.
That earlier individual allocation's eventual expiry was not independently read
back after its private latest-session journal was superseded; do not claim every
exploratory failure had a proven successful force-close. The four retained media
runs **did** each prove all nine records inactive.

Open: reliable repeated live runs on this network, final-source full live sweep,
physical microphone/listening quality, Android SFU interoperability, actual Relay
app call integration, long-duration stability, SFU frame encryption/E2EE and
billing/egress accounting. The SFU terminates transport encryption and can see
media. No account configuration, billing, resources/deployments, emulator state,
canonical tasks/docs or existing probe sources were changed by this work.

## Evidence paths

All paths below are rooted at
`/home/tadej/repos/Relay/linux-port/spikes/calls/`:

- `evidence/sfu-live-positive.json`
- `evidence/sfu-live-silent-0.json`
- `evidence/sfu-live-silent-1.json`
- `evidence/sfu-live-silent-2.json`
- `evidence/sfu-media-unit.txt`
- `evidence/sfu-media-guard-mutations.json`
- `evidence/sfu-media-suite.json` — explicitly labels collection vs fresh live run;
  hashes describe source contents at collection, not historical run provenance.
- `evidence/sfu-live-repeat-failure.json`
- `evidence/sfu-live-expired-attempt.json`

Reference checked during implementation:
<https://developers.cloudflare.com/realtime/sfu/get-started/connection-patterns/>.
