# WP02 — five-participant SFU load and egress estimate

Date: 2026-10-24 UTC. Branch: `feat/linux-port`. Detailed numeric report:
[SFU-LOAD-EVIDENCE.md](../spikes/calls/SFU-LOAD-EVIDENCE.md). This is a generated-audio load
measurement through the live Cloudflare SFU, not a production call engine and not account billing.

## Outcome

Attempt 2 of a bounded two-attempt case **passed**: five independent Linux native participants (one
JVM process each), each publishing one generated tone and subscribing to the other four — **20
directed streams**. Every stream passed its own per-receiver RTP and decoded PCM energy assertion
(PCM RMS 8,422–8,450 against a 500 threshold). All **25 track records** were force-closed and read
back `inactive` from the exact sessions. No packet loss; jitter was 0.001 s on all streams.

Attempt 1 failed at publish after 45.41 s: participants 0–2 connected, participant 3's child exited
without a reply. Its four sessions were force-closed; three read back inactive and one read back
HTTP 410 expired. An earlier invocation (before the 5xx retry policy) failed with provider HTTP 500
at the same point. The pass is real but stability is not proven — two of three attempts failed while
adding the fourth participant.

Key timings: participants connected at 0.97, 2.11, 3.19, 4.12 and 5.21 s from case start; time to
full media 8.38 s; observation window 10.008 s; total 20.13 s. Per participant: 1,400–1,820 PCM
frames pushed, 665–877 Opus packets sent, 0.70–0.82 s CPU total. Five processes used **120 threads**
and **~392 MiB** peak RSS; the measured window consumed ~14% of one host core. No exhaustion signal.

Commands: `RELAY_SPIKE_LIVE=1 PYTHONDONTWRITEBYTECODE=1 JAVA_HOME=/home/tadej/tools/jdk-21.0.12.1+1
python3 -B sfu_load_suite.py` from `linux-port/spikes/calls`. Offline guards: 13/13 tests passed and
8/8 mutations killed. Numeric evidence: `spikes/calls/evidence/sfu-load-attempt{1,2}.json`,
`sfu-load-suite.json`, `sfu-load-unit.txt`, `sfu-load-guard-mutations.json`.

## Parent rerun (independent)

The parent re-ran the same live suite on the same source from `linux-port/spikes/calls`. It **passed on
attempt 1**: 5 participants, **20/20 directed streams with 20 distinct publisher->participant pairs**,
20.03 s, native exit normal, cleanup `sessions=5, requested=25, inactive_records=25, close_ok=5,
readback_ok=5`. That run's numbers replaced this suite's own `attempt1`/`sfu-load-suite.json` artifacts
(observed 810,243 payload bytes over 10.009 s across 20 streams, wire estimate 1,050.91 kbit/s); the
delegate's earlier `attempt2` numbers quoted above are retained as a historical measured snapshot. Two
of the delegate's three live attempts failed while adding the fourth participant, so the pass is real
but stability is still unproven — treat this as "works most runs", not a stable SFU implementation.

## Egress estimate (ESTIMATE)

Provider-observed inbound RTP payload: **648.18 kbit/s** total across 20 streams over 10.008 s
(**32.41 kbit/s** per stream). Adding 50 B/packet wire overhead gives **1,052 kbit/s** total
(**52.62 kbit/s** per stream). Per participant payload: **972,263 bytes/minute**.

| Participants | Streams | Wire MB/hour (EST) | GB/hour (EST) | 1,000 GB room-hours (EST) | $/room-hour after free (EST) |
|---:|---:|---:|---:|---:|---:|
| 5 | 20 | 473.6 | 0.4736 | 2,111.5 | $0.0237 |
| 17 | 272 | 6,440.9 | 6.4409 | 155.3 | $0.322 |

Assumptions: Opus mono ~32 kbit/s, DTX off, continuous tone, no video, all N x (N-1) streams active,
50 B/packet overhead, 1,000 GB = 1e9 bytes, $0.05/GB. The 17-participant row is **extrapolated from
the five-participant measurement, not measured**.

Against `02-research-findings.md` §E (48 kbit/s wire, 21.6 MB/stream-hour, 432 MB/hour for 5,
5.9 GB/hour for 17, ~2,300 five-person and ~170 full-group room-hours per free TB): measured wire is
**23.68 MB per stream-hour**, about **9.6% above** the plan. The five-participant case is 473.6 vs
432 MB/hour (+9.6%) and the 17-party extrapolation 6.44 vs 5.9 GB/hour (+9.2%). The measured
throughput is the same order as §E but slightly above its wire budget, not below it; the §E figures
are ~9–10% optimistic for this continuous-tone, no-DTX case. This does not contradict the
architecture. TURN-relayed participants could add egress and were not measured. No account-wide
billing/egress figure is claimed; these are provider-observed RTP payload bytes plus an explicit
wire-overhead assumption.

## Limits and what did not run

- **17 participants is extrapolated, not measured.** No 17-party connection, memory or duration run.
- No long-duration stability or memory-growth test; one ~20 s passing run after one failed attempt.
- No Android↔SFU, no microphone capture or listening quality, no E2EE (the SFU terminates SRTP).
- No TURN-relayed group participants; no dashboard/account egress or billing verification.
- No documented provider hard cap was proven. Two failures occurred while adding the fourth
  participant (HTTP 500 and native ICE/exit), but the passing attempt created five sessions and 25
  tracks, so these look transient rather than a measured 3- or 4-participant limit. Cloudflare's
  30-second no-packet track expiry was not exercised because the tone ran continuously.

The basic five-participant, 20-stream SFU load feasibility is now measured. The 17-participant
product cap remains unmeasured, and reliability remains "passes most runs" rather than stable.
