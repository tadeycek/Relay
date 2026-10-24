# WP02 — five-participant SFU load and measured egress estimate

Date: 2026-10-24 UTC. Branch: `feat/linux-port`. Scope: `linux-port/spikes/calls/**` only.
This is a load measurement of generated audio through the live Cloudflare SFU. It is **not** a
production call implementation, an account billing statement, or E2EE evidence.

## Result

A five-participant case ran on attempt 2: **five independent Linux native participants**, each in
its own JVM process, each publishing one generated tone and subscribing to the other four. The run
produced **20 directed streams** (5 x 4), every stream passed its own per-receiver RTP and decoded
PCM energy assertion, and all **25 track records** were force-closed and read back inactive from the
exact sessions. Attempt 1 failed during publish and is recorded below; its data is not used.

One pass does not prove stability: across this session's three live attempts, two failed while
adding the fourth participant (one provider HTTP 500, one native ICE/exit) and one passed. That is
consistent with the known intermittent SFU connection flake, not a measured hard participant cap.

## Harness

New files (existing 3-party harness left untouched):

- `src/main/java/relay/spike/SfuLoadProbe.java` — copy of the proven `SfuProbe` peer/ownership
  design, one peer per process, with outbound sender counters and inbound jitter/packet-loss
  attributes added to `METRICS`.
- `sfu_load_suite.py` — five-process coordinator, per-process `/proc` sampling, bounded two-attempt
  case, exact cleanup readback and egress estimate.
- `test_sfu_load.py` — 13 offline acceptance tests.
- `check_sfu_load_mutations.py` — 8 guard mutations.

Exact commands:

```sh
cd /home/tadej/repos/Relay/linux-port/spikes/calls
RELAY_SPIKE_LIVE=1 PYTHONDONTWRITEBYTECODE=1 \
  JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1 python3 -B sfu_load_suite.py
```

The suite compiles `CallsProbe.java` + `SfuLoadProbe.java` into `.private/sfu-load-classes`, runs
the offline unit tests and mutation checker, then runs at most two live attempts. It made fresh
provider requests (`live_executed_this_invocation=true`). Credentials were read programmatically
from the 0600 `.local/realtime.json`; no secret, SDP, ICE candidate or session id is printed or
committed. Native stderr and raw signalling remain in `.private` only.

## Guard tests and mutations

- `python3 -B -m unittest test_sfu_load -v`: **13 tests, 13 passed**.
- `python3 -B check_sfu_load_mutations.py`: **8/8 mutations killed**.
- Mutations: stream count, exact pair set, PCM energy threshold, stale observation window,
  inactive-record count, cleanup disposition, egress arithmetic, zero-length window.

Unit log: `evidence/sfu-load-unit.txt`. Mutation report: `evidence/sfu-load-guard-mutations.json`.

## Attempts

| Attempt | Result | Stage | Error | Elapsed | Streams | Cleanup |
|---:|---|---|---|---:|---:|---|
| 1 | failed | publish | native exited (ICE/transport) | 45.41 s | 0 | 4 sessions; 4 requested, 3 inactive, 1 expired |
| 2 | **passed** | media | — | 20.13 s | 20 | 5 sessions; 25 requested, 25 inactive |

Attempt 1 connected participants 0, 1 and 2 (1.17 s, 2.30 s, 3.46 s from case start) and failed while
adding participant 3; its child exited without a reply. All four sessions created by that attempt
were still force-closed; three read back inactive and one read back HTTP 410 expired. The failed
attempt's allocations were cleaned, not reused. Attempt 2 is a fresh set of five sessions.

An earlier invocation, before the 5xx retry policy was added, failed at publish with provider
HTTP 500 after 4.87 s (4 sessions created; 4 requested, 4 inactive). Its numeric JSON was overwritten
by the retained two-attempt invocation; this sentence is the retained record of that outcome.

## Attempt 2 timings

| Participant | Connected from case start | Connect from its process start |
|---:|---:|---:|
| 0 | 0.97 s | 0.96 s |
| 1 | 2.11 s | 0.97 s |
| 2 | 3.19 s | 0.90 s |
| 3 | 4.12 s | 0.80 s |
| 4 | 5.21 s | 1.00 s |

Time to full media (all 20 streams at >=10 packets and >=4,800 decoded samples): **8.38 s**.
Observation window: **10.008 s**. Total run including cleanup: **20.13 s**.

## Per-stream RTP and decoded PCM (attempt 2)

Cumulative values cover each subscription's life; `window_*` values are the measured 10.008 s
observation window. Jitter is the native inbound-RTP `jitter` attribute (seconds); packet loss is
`packetsLost`. Every row had jitter **0.001 s** and **0** lost packets. PCM RMS is in signed 16-bit
sample units; all rows exceed the 500 threshold.

| Publisher -> participant | Packets | Bytes | Window packets | Window bytes | Jitter (s) | Lost | PCM RMS |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1 -> 0 | 641 | 51,435 | 504 | 40,500 | 0.001 | 0 | 8,444.92 |
| 2 -> 0 | 641 | 51,435 | 507 | 40,581 | 0.001 | 0 | 8,441.63 |
| 3 -> 0 | 641 | 51,435 | 507 | 40,581 | 0.001 | 0 | 8,442.16 |
| 4 -> 0 | 641 | 51,435 | 507 | 40,581 | 0.001 | 0 | 8,441.45 |
| 0 -> 1 | 618 | 49,572 | 505 | 40,581 | 0.001 | 0 | 8,422.71 |
| 2 -> 1 | 618 | 49,572 | 507 | 40,581 | 0.001 | 0 | 8,422.44 |
| 3 -> 1 | 618 | 49,572 | 507 | 40,581 | 0.001 | 0 | 8,422.84 |
| 4 -> 1 | 618 | 49,572 | 507 | 40,581 | 0.001 | 0 | 8,422.19 |
| 0 -> 2 | 596 | 47,952 | 505 | 40,581 | 0.001 | 0 | 8,434.33 |
| 1 -> 2 | 598 | 47,952 | 504 | 40,500 | 0.001 | 0 | 8,437.34 |
| 3 -> 2 | 598 | 47,952 | 507 | 40,581 | 0.001 | 0 | 8,437.79 |
| 4 -> 2 | 598 | 47,952 | 507 | 40,581 | 0.001 | 0 | 8,437.15 |
| 0 -> 3 | 579 | 46,575 | 504 | 40,500 | 0.001 | 0 | 8,450.36 |
| 1 -> 3 | 580 | 46,494 | 504 | 40,500 | 0.001 | 0 | 8,446.35 |
| 2 -> 3 | 581 | 46,575 | 506 | 40,500 | 0.001 | 0 | 8,450.02 |
| 4 -> 3 | 581 | 46,575 | 506 | 40,500 | 0.001 | 0 | 8,449.76 |
| 0 -> 4 | 564 | 45,360 | 504 | 40,500 | 0.001 | 0 | 8,434.06 |
| 1 -> 4 | 567 | 45,441 | 505 | 40,581 | 0.001 | 0 | 8,437.48 |
| 2 -> 4 | 566 | 45,360 | 506 | 40,500 | 0.001 | 0 | 8,437.38 |
| 3 -> 4 | 566 | 45,360 | 506 | 40,500 | 0.001 | 0 | 8,438.05 |

Per-stream window throughput is **32.37–32.44 kbit/s payload**, median **32.44 kbit/s**. The 20
streams are distinct publisher/subscriber pairs; no self-pairs or duplicates are present.

## Per-participant native process measurements

`frames_pushed` counts 10 ms PCM pushes from the generator; `packets_sent` counts Opus RTP packets
(about 20 ms each, so roughly two PCM frames per packet). CPU is `/proc/<pid>/stat` utime+stime.
Peak RSS is `VmHWM`; threads is `/proc/<pid>/status`.

| Participant | Frames pushed | Packets sent | Bytes sent | Header bytes sent | Packets received | Bytes received | CPU total (s) | CPU in 10 s window (s) | Peak RSS (KiB) | Threads |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0 | 1,820 | 877 | 70,606 | 17,840 | 2,564 | 205,740 | 0.82 | 0.31 | 80,036 | 24 |
| 1 | 1,707 | 820 | 66,014 | 16,700 | 2,472 | 198,288 | 0.76 | 0.27 | 80,420 | 24 |
| 2 | 1,592 | 766 | 61,636 | 15,620 | 2,390 | 191,808 | 0.74 | 0.28 | 80,528 | 24 |
| 3 | 1,489 | 719 | 57,834 | 14,680 | 2,321 | 186,219 | 0.75 | 0.30 | 81,024 | 24 |
| 4 | 1,400 | 665 | 53,473 | 13,600 | 2,263 | 181,521 | 0.70 | 0.27 | 79,828 | 24 |

Totals: **120 native threads** (24 per JVM), **401,836 KiB peak RSS** (~392 MiB), **3.77 s** total
CPU across all five processes. Window CPU totals 1.43 s over 10.008 s, about **14.3% of one core**
for the whole five-participant case. No packet loss and no jitter growth were observed.

## Cleanup

Attempt 2: 5 exact sessions, **25 requested track records**, **25 inactive**, 5/5 close calls OK,
5/5 exact-session readbacks OK, all five dispositions `inactive`. The close request used
`force:true`; readback is a separate `GET /sessions/{id}`. No track remained active or missing.

## Egress estimate (ESTIMATE, not billing)

Measured payload bytes are provider-observed inbound RTP payload bytes at the receivers. The wire
estimate adds 50 B per packet (RTP 12 + SRTP auth/tag 10 + UDP 8 + IP 20); this is an assumption,
not a provider billing figure. No account-wide or dashboard egress is claimed.

Observed 10.008 s window: **810,891 payload bytes**, **10,115 packets**, **648.18 kbit/s** payload
across 20 streams (**32.41 kbit/s** per stream). Wire estimate: **1,052.44 kbit/s** total,
**52.62 kbit/s** per stream. Per participant payload: **972,263 bytes/minute** (129.6 kbit/s).
Per stream payload: **243,066 bytes/minute**.

| Participants | Streams | Payload bytes/min | Wire bytes/min (EST) | Wire MB/hour (EST) | GB/hour (EST) | 1,000 GB room-hours (EST) | $/room-hour after free (EST) |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 20 | 4,861,317 | 7,893,304 | 473.6 | 0.4736 | 2,111.5 | $0.0237 |
| 17 | 272 | 66,113,907 | 107,348,930 | 6,440.9 | 6.4409 | 155.3 | $0.322 |

Assumptions: Opus mono ~32 kbit/s, DTX **off**, continuous generated tone, no video, no FEC/RTX in
payload bytes, all N x (N-1) streams continuously active, 50 B/packet wire overhead, 1,000 GB =
1e9 bytes and $0.05/GB. The 17-participant row is an **extrapolation from the five-participant
measurement, not a measured 17-party run**.

### Comparison with the plan

`linux-port/02-research-findings.md` §E assumes ~48 kbit/s on the wire, 21.6 MB per stream-hour,
432 MB/hour for 5 people, 5.9 GB/hour for 17 people, about 2,300 five-person room-hours and 170
full-group room-hours per free 1,000 GB.

Measured wire estimate is **23.68 MB per stream-hour** (394,665 bytes/minute), about **9.6% above**
the 48 kbit/s / 21.6 MB assumption. The five-participant case is **473.6 MB/hour vs 432 MB/hour**
(+9.6%); the 17-participant extrapolation is **6.44 GB/hour vs 5.9 GB/hour** (+9.2%). Free-tier
coverage is **2,111 five-person room-hours** (vs ~2,300) and **155 full-group room-hours** (vs ~170).
The measured throughput is therefore the same order as the plan but **slightly above its wire
budget**, not below it. The gap is consistent with 32.4 kbit/s payload plus ~20 kbit/s per-packet
overhead at ~50 packets/s, versus the plan's 32 kbit/s payload plus ~16 kbit/s overhead. It does not
contradict the architecture; it makes the §E figures about 9–10% optimistic for a continuous-tone,
no-DTX worst case. TURN-relayed participants could add egress and are not measured here.

## Load note and limits

At five participants there is no sign of resource exhaustion: 120 threads, ~392 MiB total peak RSS,
~14% of one host core in the measured window, zero packet loss and 1 ms jitter. The host had 16 CPUs,
~31.8 GB RAM and load average 0.40 after the run. A 17-participant case is **extrapolated, not
measured**; a linear process-per-participant extrapolation would be roughly 408 threads and 1.3 GiB
RSS, but that is an estimate only. No long-duration stability, memory-growth or 17-party connection
test was run.

Provider-side: no documented hard participant limit was proven. The two failed attempts both failed
while adding the fourth participant (one HTTP 500 on publication, one native ICE/exit), while the
passing attempt created five sessions and 25 tracks. Treat those as transient provider/network
failures, not a measured cap. Cloudflare's 30-second no-packet track expiry was not exercised because
the tone ran continuously. No account dashboard/billing figure was checked.

## Evidence files

- `evidence/sfu-load-attempt1.json` — failed attempt 1, partial timings and cleanup counts.
- `evidence/sfu-load-attempt2.json` — passing attempt 2, all numeric rows and per-process stats.
- `evidence/sfu-load-suite.json` — attempts summary, egress estimate, guard mutation totals.
- `evidence/sfu-load-unit.txt` — 13-test unit log.
- `evidence/sfu-load-guard-mutations.json` — 8/8 killed.

Private raw signalling, native stderr and per-run journals are in `.private/` and are not committed.
