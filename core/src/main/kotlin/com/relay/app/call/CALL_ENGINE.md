# `com.relay.app.call` — 1:1 call engine and `TYPE:CALL` codec (WP12)

Pure Kotlin/JVM, no WebRTC, no Android, no threads, no clock or random reads outside the injected ports.
Hosts: Android (WP13) and desktop (WP14). Specs: `linux-port/06-calls-1on1.md`, `05-protocol-changes.md` §6,
`10-readiness-gates.md` G4. Wire table and transition table: `linux-port/evidence/WP12.md`.

## Pieces

| Type | Role |
|---|---|
| `CallBodyCodec` | `encode(CallPacket): String`, `decode(wire, CallReceiveContext, NonceReplayGuard?): CallParseResult` (never throws), `splitIce(...)`. |
| `NonceReplayGuard` | Bounded (default 256) replay memory keyed by (authenticated sender, nonce). One per device process. |
| `CallEngine` | The state machine: `handle(CallEvent): List<CallEffect>`. One call at a time. |
| `CallRuntime` | Optional glue that interprets effects against the ports below and feeds results back. Use it unless you need a different loop. |
| Ports | `ClockPort`, `RandomPort`, `SignalPort`, `TurnCredentialPort`, `PeerConnectionPort`, `TimerPort`, `CallUiPort`. |

## What the host MUST do (the engine cannot)

1. Pass `CallReceiveContext.senderPubkey` = the identity that signed the NIP-17 seal. Never a field of the body.
2. Only feed packets from verified contacts (also expressible as `CallEngineConfig.isAllowedPeer`).
3. Serialise everything: `dispatch`, `onWire` and every port callback on ONE thread/dispatcher.
4. Send `SignalMode.LIVE_AND_STORED_URGENT` (the first `invite`) as a `signal` frame AND a stored delivery with
   `urgent`. All other packets are `LIVE` only (never stored).
5. Treat a stored `invite` found in the inbox like a live one: `onWire` rejects it with `EXPIRED` once older than its
   `expires` (+ skew). Never ring for it any other way. Never open the microphone before `OpenPeerConnection`
   (which only follows a local accept).
6. Deliver `SignalPort.send` to **all** sockets of `toIdentity`; that includes this identity's own other devices when
   `toIdentity` is the local identity (the self-signal, below). Receivers filter by `device`/`to`.

## Events (host → engine)

| Event | Meaning |
|---|---|
| `StartCall(callee)` | User dials. Rejected (`CommandRejected`) when a call is live, the callee is self, or malformed. |
| `AcceptIncoming` / `DeclineIncoming` | User answers/declines the ringing call. `HangUp` while ringing-in is a decline. |
| `HangUp` | Cancel (ringing-out), decline (ringing-in), end (connecting/active). Idempotent. |
| `SetMuted(muted)` | Active only: sets the mic and sends a `mute` hint. |
| `SignalReceived(sender, packet)` | A packet that passed `CallBodyCodec.decode` (use `CallRuntime.onWire`). |
| `TimerFired(callId, timer)` | A timer started by `StartTimer` fired. Stale/cancelled timers are ignored. |
| `IceServersReady(callId, servers)` / `IceServersFailed(callId)` | Result of `RequestIceServers` (`GET /v1/call/ice`). |
| `LocalDescription(callId, gen, kind, sdp)` | The peer connection produced an offer/answer. |
| `LocalCandidate(callId, gen, candidate)` | A local ICE candidate was gathered (drop the empty end-of-candidates marker; the codec rejects it). |
| `PeerState(callId, CONNECTED/DISCONNECTED/FAILED/CLOSED)` | Reduced ICE connection state. |
| `PeerFailed(callId)` | createOffer/setRemoteDescription/etc. threw. Ends the call as failed. |

## Effects (engine → host), applied in order

`StateChanged` is ALWAYS the last effect of a batch and appears at most once per batch.

| Effect | Host action |
|---|---|
| `SendSignal(toIdentity, packet, mode)` | `CallBodyCodec.encode(packet)` and send (see MUST 4, 6). |
| `StartRing(callId, incoming)` / `StopRing(callId)` | Ringtone (incoming) or ringback (outgoing). `StopRing` is idempotent and may arrive for a call that never rang. |
| `RequestIceServers(callId)` | Fetch TURN credentials; answer with `IceServersReady/Failed`. |
| `OpenPeerConnection(callId, role, iceServers, relayOnly)` | Create the peer connection (audio only, Opus mono 24–32 kbit/s, DTX+FEC). `relayOnly` = ICE policy `relay`. Open the mic only now. |
| `CreateOffer(callId, gen, iceRestart)` | Create+set a local offer (`iceRestart` = restart ICE); report `LocalDescription(OFFER, gen)`. |
| `ApplyRemoteOffer(callId, gen, sdp)` | Roll back any local pending offer, set the remote offer, create+set the answer, report `LocalDescription(ANSWER, gen)`. |
| `ApplyRemoteAnswer(callId, gen, sdp)` | Set the remote answer. |
| `AddRemoteCandidates(callId, gen, candidates)` | Add remote candidates (the engine already held them until the remote description for `gen` was applied). |
| `SetMicMuted(callId, muted)` | Mute/unmute the local track. |
| `ClosePeerConnection(callId)` | Close and release the peer connection and the microphone. Emitted at most once, only if it was opened. |
| `StartTimer(callId, timer, delayMs)` / `CancelTimer(callId, timer)` | One-shot timers keyed by (callId, timer); start replaces. |
| `RecordCallEvent(callId, peer, incoming, outcome, reason, durationMs)` | Write a local call event into the conversation (missed, declined, completed, failed…). Not sent. |
| `CommandRejected(reason)` | Tell the user (`busy_local`, `invalid_callee`, `id_collision`). |
| `StateChanged(state)` | Update call UI. States: `Idle`, `RingingOut`, `RingingIn`, `Connecting`, `Active`, `Ended(outcome, reason)`. |

Silent outcomes (no `RecordCallEvent`): `ANSWERED_ELSEWHERE`, `GLARE_LOST`, `LOST_RACE`.

## Timers

`RING_TIMEOUT` 45 s (caller: sends `timeout`, ends `NO_ANSWER`; callee: ends `MISSED`) · `INVITE_RESEND` 5 s (re-sends
the invite as a LIVE signal with a fresh nonce and the original expiry) · `OFFER_WAIT` 5 s (callee that accepted and got
no offer gives up silently) · `CONNECT_TIMEOUT` 15 s · `ICE_RESTART` 20 s · `ICE_FLUSH` 250 ms (candidate batching).
All durations are in `CallEngineConfig`.

## Multi-device rules (LP-D10)

* Every callee device rings. The caller takes the FIRST `accept` it receives, sends `offer` to that device only
  (`to`), and sends `answered_elsewhere{winner}` to the callee identity. Late accepters get it again (once per device).
* A declining device sends `decline` to the caller and `answered_elsewhere{reason:"declined"}` **to its own identity**
  (the only valid self-signal; the codec rejects every other self-addressed packet). Siblings end silently.
* `answered_elsewhere` from the caller is trusted only after the engine checked call id, caller, callee and the pinned
  caller device. It is not self-authenticating.
* Glare: the lexicographically lower call id survives; the other side sends `cancel{reason:"glare"}` for its own call
  and auto-accepts the winner.
* Busy: any other invite while a call is live gets `busy` and a `MISSED(busy)` record, once per call id.

Memory is bounded: ≤16 remembered ended ids, ≤64 buffered local and ≤64 remote candidates, ≤8 late-accept devices, ≤6
armed timers, ≤1024 queued runtime events, ≤256 replay nonces.

## Host contract clarifications (from the WP12 security review)

These were ambiguous enough that two hosts could disagree; they are binding for WP13 and WP14.

1. **`ClockPort` is wall-clock epoch milliseconds**, not monotonic time: `ts` and `expires` in every packet are
   absolute epoch values. A monotonic clock rejects every packet as future-dated or expired.
2. **`RandomPort` must be a cryptographically secure RNG** — call ids and nonces are the only replay defence.
3. **`Ended` is sticky.** The engine does not return to `Idle`; `StartCall` is accepted from `Ended`, which
   starts a new call. Do not wait for an `Idle` state.
4. **Glare:** when both sides call at once, the winner opens a peer connection after a local `StartCall`, so the
   host sees `Connecting` without ever seeing `RingingIn`. The microphone rule is therefore "only after the user
   accepted OR the engine reports `Connecting` for a call this device started", not "only after `RingingIn`".
5. **Effects must be applied in order and serialised.** The effects are ordered, but WebRTC operations are
   asynchronous: `AddRemoteCandidates` must never run before the remote description has been applied. The host
   queues them; the engine never waits for the platform.
6. **Filter senders before `onWire`.** The nonce is recorded in `decode`, before the engine's peer check, so a
   host must drop packets whose sender is neither the peer nor this identity before handing them to the engine.
   `isAllowedPeer` is a second line of defence, not a substitute.
7. **`Busy` from one callee device is not terminal** (LP-D10): the call keeps ringing and ends `BUSY` at the ring
   timeout. The host must keep ringing until it receives a terminal state.
8. **Cancel the in-flight TURN request when the call ends.** `RequestIceServers` may arrive before the call is
   torn down; drop a credential response whose call id is no longer current.
