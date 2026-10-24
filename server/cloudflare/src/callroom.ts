import { DurableObject } from "cloudflare:workers";
import { AUTH_WINDOW_S, verifyRequestAuth, type AuthOutcome } from "./auth";
import { proxySfu, sfuConfigFrom, type SfuConfig, type SfuOptions } from "./call";
import type { CallAuthOutcome } from "./mailbox";
import {
  CALL_PID, CALL_ROOM_ID, SHA256_HEX, SFU_SESSION_ID, SFU_TRACK_ID,
  constantTimeEqualHex, deviceParam, errorResponse, json, limitsFrom, sha256Hex, type Limits,
} from "./protocol";

/**
 * WP15 — one Durable Object per active group call.
 *
 * The room is the SFU's missing directory: it knows which members are in the call, which provider session
 * each participant owns, and which track that session publishes. Participants are hibernatable WebSocket
 * attachments plus at most one SQLite row each (LP-D13). The raw join secret never enters storage: the room
 * keeps only SHA-256 of it and compares it in constant time.
 *
 * Authorization is two independent checks: the NIP-98 signature is verified here, and the signer is resolved
 * to an enrolled device and a current member by the Mailbox object (G1/WP05). A revoked device is refused on
 * HTTP and on every mutating WebSocket frame.
 */

type AuthOk = Extract<AuthOutcome, { ok: true }>;

interface Principal {
  auth: AuthOk;
  identity: string;
  device: string;
  signer: string;
}

interface ParticipantRow {
  [key: string]: SqlStorageValue;
  pid: string;
  pubkey: string;
  device: string;
  signer: string;
  session_id: string | null;
  prev_session_id: string | null;
  prev_session_at: number;
  mid: string | null;
  track: string | null;
  muted: number;
  deafened: number;
  joined_at: number;
  last_seen: number;
  left_at: number;
}

interface CallSession {
  v: 1;
  pid: string;
  pubkey: string;
  device: string;
  signer: string;
  joinedAt: number;
  /** Per-socket id: two sockets can carry the same pid, so close events cannot be attributed by pid alone. */
  conn: string;
  /** Last Mailbox revocation/membership check for this socket. */
  checkedAt: number;
}

const nowS = () => Math.floor(Date.now() / 1000);
const nowMs = () => Date.now();
/** Provider cleanup on a participant removal path shares teardown's tight budget. */
const PROVIDER_CLEANUP_MS = 2000;
const PROVIDER_CLEANUP_BUDGET_MS = 3000;
/** The serialisation tails map holds one promise per session id; bound it so long calls cannot leak entries. */
const MAX_TAILS = 256;
const CALL_WS = /^\/v1\/call\/rooms\/([0-9a-f]{32})\/ws$/;
const CALL_ROOM = /^\/v1\/call\/rooms\/([0-9a-f]{32})$/;
const CALL_SFU = /^\/v1\/call\/rooms\/([0-9a-f]{32})\/sfu\/([a-z-]+)$/;
const CALL_END = /^\/v1\/call\/rooms\/([0-9a-f]{32})\/end$/;

function newPid(): string {
  return [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function safeSend(ws: WebSocket, frame: unknown): boolean {
  try {
    ws.send(JSON.stringify(frame));
    return true;
  } catch {
    return false;
  }
}

function safeClose(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}

function parseJson(bytes: ArrayBuffer): unknown {
  try {
    return JSON.parse(new TextDecoder().decode(bytes));
  } catch {
    return null;
  }
}

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === "object" && !Array.isArray(v);

export class CallRoom extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  private readonly limits: Limits;
  private readonly exposeMetrics: boolean;
  private readonly instance = crypto.randomUUID();
  private readonly rows = { read: 0, written: 0 };
  /** Replaceable in tests, so no request ever leaves the test runtime. */
  private sfuFetch: (request: Request) => Promise<Response> = (request) => fetch(request);
  /** The SFU app credentials, read per request so a rotated secret takes effect without a restart. */
  private sfuConfig: () => SfuConfig | null = () => sfuConfigFrom(this.env as unknown as Record<string, unknown>);
  /** Per-room join attempts and per-identity SFU attempts. Bounded by the participant cap. */
  private readonly joinOps = new Map<string, number[]>();
  private readonly sfuOps = new Map<string, number[]>();

  /** Best-effort provider cleanups that timed out or failed; logged for operators, never thrown. */
  private providerCleanupFailures = 0;
  /** One promise chain per session, so provider mutations for that session never interleave. */
  private readonly tails = new Map<string, Promise<unknown>>();
  private alarmScheduled = false;
  private tearingDown = false;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    const vars = env as unknown as Record<string, unknown>;
    this.limits = limitsFrom(vars);
    this.exposeMetrics = vars.EXPOSE_METRICS === "1";
    this.migrate();
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    let response: Response;
    try {
      const roomMatch = CALL_ROOM.exec(url.pathname);
      const wsMatch = CALL_WS.exec(url.pathname);
      const sfuMatch = CALL_SFU.exec(url.pathname);
      const endMatch = CALL_END.exec(url.pathname);
      if (request.method === "POST" && url.pathname === "/v1/call/rooms") {
        response = await this.handleCreate(request);
      } else if (request.method === "GET" && roomMatch) {
        response = await this.handleExistence(request, url, roomMatch[1]);
      } else if (request.method === "GET" && wsMatch) {
        response = await this.handleUpgrade(request, url, wsMatch[1]);
      } else if (request.method === "POST" && endMatch) {
        response = await this.handleEnd(request, url, endMatch[1]);
      } else if ((request.method === "POST" || request.method === "PUT") && sfuMatch) {
        response = await this.handleSfu(request, url, sfuMatch[2]);
      } else {
        response = errorResponse(404, "not_found");
      }
    } catch (e) {
      console.error("call room request failed", { route: url.pathname, error: (e as Error)?.name });
      response = errorResponse(503, "storage_error", true);
    }
    if (response.status !== 101) response.headers.set("x-relay-instance", this.instance);
    return response;
  }

  // ---- HTTP handlers -------------------------------------------------------------------------

  private async handleCreate(request: Request): Promise<Response> {
    const start = { ...this.rows };
    const body = await request.arrayBuffer();
    if (body.byteLength > this.limits.maxCallRequestBytes) return this.withMetrics(errorResponse(413, "request_too_large"), start);
    const value = parseJson(body);
    if (!isRecord(value) || value.v !== 1 || typeof value.room !== "string" || !CALL_ROOM_ID.test(value.room) || typeof value.secretHash !== "string" || !SHA256_HEX.test(value.secretHash)) {
      return this.withMetrics(errorResponse(400, "bad_request"), start);
    }
    const url = new URL(request.url);
    const principal = await this.authorize(request, body, url);
    if (principal instanceof Response) return this.withMetrics(principal, start);
    if (!this.recordAuth(principal.auth)) return this.withMetrics(errorResponse(401, "auth_replayed"), start);
    const existing = this.q<{ secret_hash: string }>("SELECT secret_hash FROM room WHERE id = ?", value.room)[0];
    if (existing) {
      // Constant-time comparison: the secret hash is attacker-supplied and must not leak a prefix.
      return this.withMetrics(constantTimeEqualHex(existing.secret_hash, value.secretHash) ? json({ v: 1, created: true }) : errorResponse(409, "room_exists"), start);
    }
    const now = nowMs();
    if (!await this.mailbox().callCreateAllowed(principal.signer, principal.device)) {
      return this.withMetrics(errorResponse(429, "rate_limited", true), start);
    }
    this.q(
      "INSERT INTO room (id, secret_hash, creator, created_at, last_activity) VALUES (?, ?, ?, ?, ?)",
      value.room, value.secretHash, principal.identity, now, now,
    );
    // B6: an abandoned call start must not hold a DO row forever. The alarm wipes a room never joined
    // within the documented window; handleExistence also honours the same expiry.
    void this.ctx.storage.setAlarm(now + this.limits.callUnjoinedTtlMs).catch(() => undefined);
    this.alarmScheduled = true;
    return this.withMetrics(json({ v: 1, created: true }), start);
  }

  private async handleExistence(request: Request, url: URL, room: string): Promise<Response> {
    const start = { ...this.rows };
    for (const key of url.searchParams.keys()) if (key !== "device") return this.withMetrics(errorResponse(400, "bad_request"), start);
    const principal = await this.authorize(request, null, url);
    if (principal instanceof Response) return this.withMetrics(principal, start);
    if (!this.recordAuth(principal.auth)) return this.withMetrics(errorResponse(401, "auth_replayed"), start);
    const row = this.q<{ created_at: number }>("SELECT created_at FROM room WHERE id = ?", room)[0];
    if (!row) return this.withMetrics(errorResponse(404, "not_found"), start);
    const n = this.q<{ n: number }>("SELECT COUNT(*) AS n FROM participants WHERE left_at = 0")[0].n;
    if (n === 0 && nowMs() - row.created_at > this.limits.callUnjoinedTtlMs) {
      // B6: an unjoined room past its TTL is gone; do not keep returning a stale row.
      await this.teardown("unjoined_expired");
      return this.withMetrics(errorResponse(404, "not_found"), start);
    }
    return this.withMetrics(json({ v: 1, room, participants: n, createdAt: row.created_at }), start);
  }

  private async handleUpgrade(request: Request, url: URL, room: string): Promise<Response> {
    const start = { ...this.rows };
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return this.withMetrics(errorResponse(426, "upgrade_required"), start);
    for (const key of url.searchParams.keys()) {
      if (key !== "device" && key !== "secret" && key !== "pid") return this.withMetrics(errorResponse(400, "bad_request"), start);
    }
    const secret = url.searchParams.get("secret");
    if (!secret || !SHA256_HEX.test(secret)) return this.withMetrics(errorResponse(400, "bad_request"), start);
    const rawPid = url.searchParams.get("pid") ?? undefined;
    if (rawPid !== undefined && !CALL_PID.test(rawPid)) return this.withMetrics(errorResponse(400, "bad_request"), start);

    // The pid reconnect path still verifies NIP-98 and the row's signer/identity/device. B2: membership
    // and revocation are re-checked through Mailbox on every rejoin too, so a device revoked seconds
    // before the drop cannot rejoin with its old pid.
    const auth = await verifyRequestAuth(request, null, nowS(), null);
    if (!auth.ok) return this.withMetrics(errorResponse(auth.status, auth.code), start);
    const device = deviceParam(url);
    if (device === null) return this.withMetrics(errorResponse(400, "bad_request"), start);
    const now = nowMs();
    let principal: Principal | null = null;
    if (rawPid) {
      const row = this.q<ParticipantRow>("SELECT * FROM participants WHERE pid = ?", rawPid)[0];
      if (row && row.signer === auth.pubkey && row.device === device &&
        (row.left_at === 0 || now - row.last_seen <= this.limits.callReconnectGraceMs)) {
        const who = await this.mailbox().callAuth(auth.pubkey, device);
        if (!who.ok) return this.withMetrics(errorResponse(who.code === "auth_invalid" ? 401 : 403, who.code), start);
        if (who.revoked) return this.withMetrics(errorResponse(401, "auth_revoked"), start);
        if (!who.member) return this.withMetrics(errorResponse(403, "not_allowed"), start);
        principal = { auth, identity: row.pubkey, device: row.device, signer: auth.pubkey };
      }
    }
    if (!principal) {
      const who = await this.mailbox().callAuth(auth.pubkey, device);
      if (!who.ok) return this.withMetrics(errorResponse(who.code === "auth_invalid" ? 401 : 403, who.code), start);
      if (who.revoked) return this.withMetrics(errorResponse(401, "auth_revoked"), start);
      if (!who.member) return this.withMetrics(errorResponse(403, "not_allowed"), start);
      principal = { auth, identity: who.identity, device: who.device, signer: auth.pubkey };
    }
    const secretHash = await sha256Hex(secret);
    if (!this.recordAuth(principal.auth)) return this.withMetrics(errorResponse(401, "auth_replayed"), start);
    if (!this.joinAllowed(room, now)) return this.withMetrics(errorResponse(429, "rate_limited", true), start);

    const roomRow = this.q<{ secret_hash: string; creator: string; created_at: number }>("SELECT secret_hash, creator, created_at FROM room WHERE id = ?", room)[0];
    if (!roomRow) return this.withMetrics(errorResponse(404, "not_found"), start);
    if (now - roomRow.created_at > this.limits.callUnjoinedTtlMs &&
      this.q<{ n: number }>("SELECT COUNT(*) AS n FROM participants WHERE left_at = 0")[0].n === 0) {
      await this.teardown("unjoined_expired");
      return this.withMetrics(errorResponse(404, "not_found"), start);
    }
    if (!constantTimeEqualHex(secretHash, roomRow.secret_hash)) return this.withMetrics(errorResponse(403, "forbidden"), start);

    let participant: ParticipantRow | undefined;
    if (rawPid) {
      const candidate = this.q<ParticipantRow>("SELECT * FROM participants WHERE pid = ?", rawPid)[0];
      if (candidate && candidate.pubkey === principal.identity && candidate.device === principal.device &&
        (candidate.left_at === 0 || now - candidate.last_seen <= this.limits.callReconnectGraceMs)) {
        participant = candidate;
      }
    }
    if (!participant) {
      participant = this.q<ParticipantRow>("SELECT * FROM participants WHERE pubkey = ? AND device = ? AND left_at = 0", principal.identity, principal.device)[0];
    }
    if (!participant) {
      const n = this.q<{ n: number }>("SELECT COUNT(*) AS n FROM participants WHERE left_at = 0 OR last_seen >= ?", now - this.limits.callReconnectGraceMs)[0].n;
      if (n >= this.limits.maxCallParticipants) return this.withMetrics(errorResponse(409, "room_full"), start);
    }
    const pid = participant?.pid ?? newPid();
    if (participant) {
      this.q("UPDATE participants SET signer = ?, last_seen = ?, left_at = 0 WHERE pid = ?", principal.signer, now, pid);
    } else {
      this.q(
        "INSERT INTO participants (pid, pubkey, device, signer, joined_at, last_seen, left_at) VALUES (?, ?, ?, ?, ?, ?, 0)",
        pid, principal.identity, principal.device, principal.signer, now, now,
      );
    }
    const { 0: client, 1: server } = new WebSocketPair();
    this.ctx.acceptWebSocket(server, [pid, principal.identity]);
    // B1: accept the new socket before closing the old one. The old socket's close event must see the
    // new live socket and skip removeParticipant; otherwise it would wipe the surviving participant row.
    for (const ws of this.ctx.getWebSockets(pid)) {
      if (ws !== server) safeClose(ws, 4009, "replaced");
    }
    const session: CallSession = { v: 1, pid, pubkey: principal.identity, device: principal.device, signer: principal.signer, joinedAt: participant?.joined_at ?? now, conn: crypto.randomUUID(), checkedAt: now };
    server.serializeAttachment(session);
    const roster = this.q<{ pid: string; pubkey: string; device: string; session_id: string | null; track: string | null; muted: number }>(
      "SELECT pid, pubkey, device, session_id, track, muted FROM participants WHERE left_at = 0 AND pid <> ? ORDER BY joined_at", pid,
    ).map((r) => ({ pid: r.pid, pubkey: r.pubkey, device: r.device, sessionId: r.session_id, track: r.track, muted: r.muted === 1 }));
    safeSend(server, { type: "welcome", v: 1, you: pid, participants: roster });
    this.broadcast({ type: "joined", pid, pubkey: principal.identity, device: principal.device, sessionId: participant?.session_id ?? null, track: null, muted: false }, pid);
    await this.ctx.storage.setAlarm(now + this.limits.callHeartbeatRecheckMs);
    this.alarmScheduled = true;
    return this.withMetrics(new Response(null, { status: 101, webSocket: client }), start);
  }

  private async handleEnd(request: Request, url: URL, room: string): Promise<Response> {
    const start = { ...this.rows };
    const principal = await this.authorize(request, null, url);
    if (principal instanceof Response) return this.withMetrics(principal, start);
    if (!this.recordAuth(principal.auth)) return this.withMetrics(errorResponse(401, "auth_replayed"), start);
    const row = this.q<{ creator: string }>("SELECT creator FROM room WHERE id = ?", room)[0];
    if (!row) return this.withMetrics(errorResponse(404, "not_found"), start);
    if (row.creator !== principal.identity) return this.withMetrics(errorResponse(403, "forbidden"), start);
    this.broadcast({ type: "end", by: principal.identity });
    await this.teardown("ended");
    return this.withMetrics(json({ v: 1, ended: true }), start);
  }

  private async handleSfu(request: Request, url: URL, op: string): Promise<Response> {
    const start = { ...this.rows };
    const body = await request.arrayBuffer();
    if (body.byteLength > this.limits.maxSfuRequestBytes) return this.withMetrics(errorResponse(413, "request_too_large"), start);
    const value = parseJson(body);
    if (!isRecord(value) || value.v !== 1) return this.withMetrics(errorResponse(400, "bad_request"), start);

    const principal = await this.authorize(request, body, url);
    if (principal instanceof Response) return this.withMetrics(principal, start);
    if (!this.recordAuth(principal.auth)) return this.withMetrics(errorResponse(401, "auth_replayed"), start);
    const now = nowMs();
    if (!this.sfuAllowed(principal.identity, now)) return this.withMetrics(errorResponse(429, "rate_limited", true), start);

    const participant = this.q<ParticipantRow>("SELECT * FROM participants WHERE pubkey = ? AND device = ? AND left_at = 0", principal.identity, principal.device)[0];
    if (!participant) return this.withMetrics(errorResponse(403, "forbidden"), start);
    // The provider surface is available only while the participant has an open room socket.
    if (this.ctx.getWebSockets(participant.pid).length === 0) return this.withMetrics(errorResponse(403, "forbidden"), start);

    const config = this.sfuConfig();
    if (!config) return this.withMetrics(errorResponse(503, "not_configured"), start);
    const options = this.sfuOptions();
    const respond = (response: Response) => this.withMetrics(response, start);

    return this.serialize(`p:${participant.pid}`, async () => {
    // Refresh under the participant lock: concurrent publishes/rotations must not use a stale mid.
    const latest = this.q<ParticipantRow>("SELECT * FROM participants WHERE pid = ?", participant.pid)[0];
    if (!latest || latest.left_at !== 0) return respond(errorResponse(403, "forbidden"));
    Object.assign(participant, latest);
    if (this.q<{ n: number }>("SELECT COUNT(*) AS n FROM pending_track_close WHERE pid = ?", participant.pid)[0].n > 0 && op === "tracks-new") return respond(errorResponse(502, "upstream_failed", true));
    switch (op) {
      case "sessions-new": {
        const offer = value.offer;
        if (offer !== undefined && (typeof offer !== "string" || offer.length > this.limits.maxCallSdpBytes)) return respond(errorResponse(400, "bad_request"));
        if (participant.mid && participant.session_id) {
          this.q("INSERT OR IGNORE INTO pending_track_close (session_id, mid, pid) VALUES (?, ?, ?)", participant.session_id, participant.mid, participant.pid);
          await this.ctx.storage.setAlarm(nowMs() + this.limits.callHeartbeatRecheckMs);
          await this.closeTrack(participant.session_id, participant.mid, participant.pid);
          if (this.q<{ n: number }>("SELECT COUNT(*) AS n FROM pending_track_close WHERE pid = ?", participant.pid)[0].n) return respond(errorResponse(502, "upstream_failed", true));
        }
        if (this.q<{ n: number }>("SELECT COUNT(*) AS n FROM pending_track_close WHERE pid = ?", participant.pid)[0].n) return respond(errorResponse(502, "upstream_failed", true));
        const providerBody: Record<string, unknown> = offer ? { sessionDescription: { type: "offer", sdp: offer } } : {};
        const outcome = await proxySfu(config, this.sfuFetch, "sessions-new", null, providerBody, options);
        if (!outcome.ok) return respond(errorResponse(502, "upstream_failed", true));
        const sessionId = outcome.data.sessionId;
        if (typeof sessionId !== "string" || !SFU_SESSION_ID.test(sessionId)) return respond(errorResponse(502, "upstream_failed", true));
        this.q(
          "UPDATE participants SET prev_session_id = session_id, prev_session_at = ?, session_id = ?, mid = NULL, track = NULL, last_seen = ? WHERE pid = ?",
          now, sessionId, now, participant.pid,
        );
        return respond(json({
          v: 1,
          sessionId,
          sessionDescription: outcome.data.sessionDescription ?? null,
          requiresImmediateRenegotiation: outcome.data.requiresImmediateRenegotiation === true,
        }));
      }
      case "tracks-new": {
        const target = typeof value.sessionId === "string" ? value.sessionId : participant.session_id;
        if (target === null || (target !== participant.session_id && target !== participant.prev_session_id)) return respond(errorResponse(403, "forbidden"));
        if (target === participant.prev_session_id && now - participant.prev_session_at > this.limits.callReconnectGraceMs) return respond(errorResponse(403, "forbidden"));
        const tracks = value.tracks;
        if (!Array.isArray(tracks) || tracks.length === 0 || tracks.length > this.limits.maxCallTracksPerRequest) return respond(errorResponse(400, "bad_request"));

        let local = 0;
        const normalized: Array<Record<string, unknown>> = [];
        for (const item of tracks) {
          if (!isRecord(item)) return respond(errorResponse(400, "bad_request"));
          if (item.location === "local") {
            // B5: a second local mic would overwrite the stored mid and orphan the first track.
            if (target !== participant.session_id) return respond(errorResponse(403, "forbidden"));
            if (participant.mid !== null) return respond(errorResponse(409, "track_exists"));
            local++;
            if (local > 1 || typeof item.mid !== "string" || !SFU_TRACK_ID.test(item.mid) || item.trackName !== "mic") return respond(errorResponse(400, "bad_request"));
            normalized.push({ location: "local", mid: item.mid, trackName: "mic" });
          } else if (item.location === "remote") {
            if (typeof item.sessionId !== "string" || !SFU_SESSION_ID.test(item.sessionId) || item.trackName !== "mic") return respond(errorResponse(400, "bad_request"));
            const source = this.q<{ pid: string }>(
              "SELECT pid FROM participants WHERE (session_id = ? OR (prev_session_id = ? AND prev_session_at >= ?)) AND left_at = 0 AND pid <> ?",
              item.sessionId, item.sessionId, now - this.limits.callReconnectGraceMs, participant.pid,
            )[0];
            if (!source) return respond(errorResponse(403, "forbidden"));
            normalized.push({ location: "remote", sessionId: item.sessionId, trackName: "mic" });
          } else {
            return respond(errorResponse(400, "bad_request"));
          }
        }

        const providerBody: Record<string, unknown> = { tracks: normalized };
        if (value.sessionDescription !== undefined) {
          if (!isRecord(value.sessionDescription) || value.sessionDescription.type !== "offer" ||
            typeof value.sessionDescription.sdp !== "string" || value.sessionDescription.sdp.length > this.limits.maxCallSdpBytes) {
            return respond(errorResponse(400, "bad_request"));
          }
          providerBody.sessionDescription = { type: "offer", sdp: value.sessionDescription.sdp };
        }
        const outcome = await proxySfu(config, this.sfuFetch, "tracks-new", target, providerBody, options);
        if (!outcome.ok) return respond(errorResponse(502, "upstream_failed", true));
        const returned = Array.isArray(outcome.data.tracks) ? outcome.data.tracks : [];
        if (returned.some((t) => isRecord(t) && (t.errorCode || t.errorDescription))) return respond(errorResponse(502, "upstream_failed", true));
        if (local === 1) {
          const published = returned.find((t) => isRecord(t) && typeof t.mid === "string") as Record<string, unknown> | undefined;
          const mid = typeof published?.mid === "string" ? published.mid : null;
          if (!mid || !SFU_TRACK_ID.test(mid)) {
            // The requested local MID is the only handle we have; quarantine it until provider close succeeds.
            const requestedMid = normalized.find((t) => t.location === "local")?.mid as string;
            this.q("INSERT OR IGNORE INTO pending_track_close (session_id, mid, pid) VALUES (?, ?, ?)", target, requestedMid, participant.pid);
            await this.ctx.storage.setAlarm(nowMs() + this.limits.callHeartbeatRecheckMs);
            this.alarmScheduled = true;
            return respond(errorResponse(502, "upstream_failed", true));
          }
          this.q("UPDATE participants SET track = 'mic', mid = ?, last_seen = ? WHERE pid = ?", mid, now, participant.pid);
          this.broadcast({ type: "joined", pid: participant.pid, pubkey: participant.pubkey, device: participant.device, sessionId: target, track: "mic", muted: participant.muted === 1 }, participant.pid);
        }
        const tracksOut = returned
          .slice(0, this.limits.maxCallTracksPerRequest)
          .map((t) => (isRecord(t) ? { mid: t.mid ?? null, trackName: t.trackName ?? null } : null))
          .filter((t) => t !== null);
        return respond(json({
          v: 1,
          tracks: tracksOut,
          sessionDescription: outcome.data.sessionDescription ?? null,
          requiresImmediateRenegotiation: outcome.data.requiresImmediateRenegotiation === true,
        }));
      }
      case "renegotiate": {
        const answer = value.answer;
        if (typeof answer !== "string" || answer.length > this.limits.maxCallSdpBytes) return respond(errorResponse(400, "bad_request"));
        const target = typeof value.sessionId === "string" ? value.sessionId : participant.session_id;
        if (target === null || (target !== participant.session_id && target !== participant.prev_session_id)) return respond(errorResponse(403, "forbidden"));
        if (target === participant.prev_session_id && now - participant.prev_session_at > this.limits.callReconnectGraceMs) return respond(errorResponse(403, "forbidden"));
        const outcome = await this.serialize(`s:${target}`, () => proxySfu(config, this.sfuFetch, "renegotiate", target, { sessionDescription: { type: "answer", sdp: answer } }, options));
        if (!outcome.ok) return respond(errorResponse(502, "upstream_failed", true));
        return respond(json({ v: 1, sessionDescription: outcome.data.sessionDescription ?? null }));
      }
      case "tracks-close": {
        const mids = value.mids;
        if (!Array.isArray(mids) || mids.length < 1 || mids.length > this.limits.maxCallTracksPerRequest || !mids.every((m) => typeof m === "string" && SFU_TRACK_ID.test(m))) {
          return respond(errorResponse(400, "bad_request"));
        }
        const target = typeof value.sessionId === "string" ? value.sessionId : participant.session_id;
        if (target === null || (target !== participant.session_id && target !== participant.prev_session_id)) return respond(errorResponse(403, "forbidden"));
        if (target === participant.prev_session_id && now - participant.prev_session_at > this.limits.callReconnectGraceMs) return respond(errorResponse(403, "forbidden"));
        const outcome = await this.serialize(`s:${target}`, () => proxySfu(config, this.sfuFetch, "tracks-close", target, { tracks: mids.map((mid) => ({ mid })), force: true }, options));
        if (!outcome.ok) return respond(errorResponse(502, "upstream_failed", true));
        this.q("UPDATE participants SET track = NULL, mid = NULL, last_seen = ? WHERE pid = ?", now, participant.pid);
        const returned = Array.isArray(outcome.data.tracks) ? outcome.data.tracks : [];
        return respond(json({ v: 1, tracks: returned.map((t) => (isRecord(t) ? { mid: t.mid ?? null } : null)).filter((t) => t !== null) }));
      }
      default:
        return respond(errorResponse(404, "not_found"));
    }
    });
  }

  // ---- WebSocket frame handling ---------------------------------------------------------------

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const session = ws.deserializeAttachment() as CallSession | null;
    if (!session) {
      safeClose(ws, 4003, "no_session");
      return;
    }
    let frame: unknown;
    try {
      frame = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw));
    } catch {
      safeSend(ws, { type: "error", code: "bad_request" });
      return;
    }
    if (!isRecord(frame) || typeof frame.type !== "string") {
      safeSend(ws, { type: "error", code: "bad_request" });
      return;
    }
    if (frame.type === "heartbeat") {
      // B3: a revoked device must not stay alive by only heartbeating. Recheck at most once per throttle
      // window; a failed check closes the socket 4009 and removes the participant from the roster.
      const now = nowMs();
      if (now - session.checkedAt >= this.limits.callHeartbeatRecheckMs) {
        const principal = await this.recheck(session);
        if (principal instanceof Response) {
          // B3: close 4009 before the removal can tear the room down and close every socket with 1000.
          safeClose(ws, 4009, "auth_revoked");
          await this.removeParticipant(session.pid, true);
          return;
        }
        session.checkedAt = now;
      }
      ws.serializeAttachment(session);
      return;
    }

    // G1: a revoked device must not be able to act through a socket opened before the revocation.
    const principal = await this.recheck(session);
    if (principal instanceof Response) {
      safeClose(ws, 4009, "auth_revoked");
      await this.removeParticipant(session.pid, true);
      return;
    }

    switch (frame.type) {
      case "state": {
        if (typeof frame.muted !== "boolean" || (frame.deafened !== undefined && typeof frame.deafened !== "boolean")) {
          safeSend(ws, { type: "error", code: "bad_request" });
          return;
        }
        const muted = frame.muted ? 1 : 0;
        const deafened = frame.deafened === true ? 1 : 0;
        this.q("UPDATE participants SET muted = ?, deafened = ?, last_seen = ? WHERE pid = ?", muted, deafened, nowMs(), session.pid);
        this.broadcast({ type: "state", pid: session.pid, muted: muted === 1, deafened: deafened === 1 }, session.pid);
        return;
      }
      case "leave": {
        safeClose(ws, 1000, "left");
        await this.removeParticipant(session.pid, true);
        return;
      }
      case "end": {
        const room = this.q<{ creator: string }>("SELECT creator FROM room LIMIT 1")[0];
        if (!room || room.creator !== principal.identity) {
          safeSend(ws, { type: "error", code: "forbidden" });
          return;
        }
        this.broadcast({ type: "end", by: principal.identity });
        await this.teardown("ended");
        return;
      }
      default:
        safeSend(ws, { type: "error", code: "bad_request" });
    }
  }

  async webSocketClose(ws: WebSocket, _code: number, _reason: string): Promise<void> {
    if (this.tearingDown) return;
    const session = ws.deserializeAttachment() as CallSession | null;
    if (!session) return;
    // B1: only remove when no other live socket remains for this pid. Exclude the socket being closed:
    // a duplicate join's old socket must not mark the surviving socket's row left or schedule teardown.
    const others = this.ctx.getWebSockets(session.pid).filter((s) => s !== ws).length;
    if (others === 0) {
      await this.removeParticipant(session.pid, false);
    }
  }

  async webSocketError(ws: WebSocket, _error: unknown): Promise<void> {
    if (this.tearingDown) return;
    const session = ws.deserializeAttachment() as CallSession | null;
    if (!session) return;
    const others = this.ctx.getWebSockets(session.pid).filter((s) => s !== ws).length;
    if (others === 0) {
      await this.removeParticipant(session.pid, false);
    }
  }

  async alarm(): Promise<void> {
    this.alarmScheduled = false;
    if (this.tearingDown) return;
    // Hibernated sockets receive no frames. Probe once per live socket per interval, not per frame.
    for (const ws of this.ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as CallSession | null;
      if (!session) continue;
      const principal = await this.recheck(session).catch(() => errorResponse(503, "storage_error", true));
      if (principal instanceof Response) {
        safeClose(ws, 4009, "auth_revoked");
        await this.removeParticipant(session.pid, true);
      } else {
        session.checkedAt = nowMs();
        ws.serializeAttachment(session);
      }
    }
    if (this.tearingDown) return;
    const pending = this.q<{ session_id: string; mid: string; pid: string }>("SELECT * FROM pending_track_close LIMIT 17");
    for (const item of pending) await this.closeTrack(item.session_id, item.mid, item.pid);
    const active = this.q<{ n: number }>("SELECT COUNT(*) AS n FROM participants WHERE left_at = 0")[0].n;
    if (active > 0) {
      await this.ctx.storage.setAlarm(nowMs() + this.limits.callHeartbeatRecheckMs);
      this.alarmScheduled = true;
      return;
    }
    if (this.q<{ n: number }>("SELECT COUNT(*) AS n FROM pending_track_close")[0].n > 0) {
      await this.ctx.storage.setAlarm(nowMs() + this.limits.callHeartbeatRecheckMs);
      this.alarmScheduled = true;
      return;
    }
    const grace = this.q<{ last_seen: number }>("SELECT last_seen FROM participants ORDER BY last_seen DESC LIMIT 1")[0];
    if (grace && nowMs() - grace.last_seen < this.limits.callReconnectGraceMs) {
      await this.ctx.storage.setAlarm(grace.last_seen + this.limits.callReconnectGraceMs);
      this.alarmScheduled = true;
      return;
    }
    await this.teardown("expired");
  }

  // ---- Authorization and limits -----------------------------------------------------------------

  private async authorize(request: Request, body: ArrayBuffer | null, url: URL): Promise<Principal | Response> {
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const device = deviceParam(url);
    if (device === null) return errorResponse(400, "bad_request");
    const who = await this.mailbox().callAuth(auth.pubkey, device);
    if (!who.ok) return errorResponse(who.code === "auth_invalid" ? 401 : 403, who.code);
    if (who.revoked) return errorResponse(401, "auth_revoked");
    if (!who.member) return errorResponse(403, "not_allowed");
    return { auth, identity: who.identity, device: who.device, signer: auth.pubkey };
  }

  private async recheck(session: CallSession): Promise<Principal | Response> {
    const who = await this.mailbox().callAuth(session.signer, session.device);
    if (!who.ok) return errorResponse(401, "auth_invalid");
    if (who.revoked) return errorResponse(401, "auth_revoked");
    if (!who.member) return errorResponse(403, "not_allowed");
    if (who.identity !== session.pubkey) return errorResponse(401, "auth_invalid");
    return { auth: { ok: true, pubkey: session.signer, eventId: "", createdAt: 0 }, identity: who.identity, device: who.device, signer: session.signer };
  }

  private mailbox(): { callAuth(signer: string, device: string): Promise<CallAuthOutcome>; callCreateAllowed(signer: string, device: string): Promise<boolean> } {
    return this.env.MAILBOX.get(this.env.MAILBOX.idFromName("pilot")) as unknown as {
      callAuth(signer: string, device: string): Promise<CallAuthOutcome>;
      callCreateAllowed(signer: string, device: string): Promise<boolean>;
    };
  }

  private joinAllowed(room: string, now: number): boolean {
    return this.allow(this.joinOps, room, now, this.limits.callJoinsPerMinute);
  }

  private sfuAllowed(identity: string, now: number): boolean {
    return this.allow(this.sfuOps, identity, now, this.limits.callSfuPerMinute);
  }

  private allow(map: Map<string, number[]>, key: string, now: number, limit: number): boolean {
    const recent = (map.get(key) ?? []).filter((t) => t >= now - 60_000);
    if (recent.length >= limit) {
      map.set(key, recent);
      return false;
    }
    recent.push(now);
    map.set(key, recent);
    return true;
  }

  private serialize<T>(key: string, work: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(key) ?? Promise.resolve();
    const next = previous.then(work, work);
    this.tails.set(key, next.then(() => undefined, () => undefined));
    // The map holds one promise per provider session id and would otherwise grow for a long call.
    if (this.tails.size > MAX_TAILS) {
      const oldest = this.tails.keys().next().value;
      if (oldest !== undefined) this.tails.delete(oldest);
    }
    return next;
  }

  private sfuOptions(): SfuOptions {
    return { timeoutMs: this.limits.sfuTimeoutMs, maxResponseBytes: this.limits.maxSfuResponseBytes };
  }

  // ---- Lifecycle -------------------------------------------------------------------------------

  private async removeParticipant(pid: string, immediate: boolean): Promise<void> {
    if (this.tearingDown) return;
    const row = this.q<ParticipantRow>("SELECT * FROM participants WHERE pid = ?", pid)[0];
    if (!row || row.left_at !== 0) return;

    const now = nowMs();
    this.q("UPDATE participants SET left_at = ?, last_seen = ? WHERE pid = ?", now, now, pid);
    this.broadcast({ type: "left", pid, pubkey: row.pubkey, device: row.device }, pid);
    // Persist the cleanup intent before any provider await. Failed closes block republish and survive eviction.
    if (row.mid && row.session_id) {
      this.q("INSERT OR IGNORE INTO pending_track_close (session_id, mid, pid) VALUES (?, ?, ?)", row.session_id, row.mid, pid);
      await this.ctx.storage.setAlarm(now + this.limits.callHeartbeatRecheckMs);
      this.alarmScheduled = true;
      await this.closeTrack(row.session_id, row.mid, pid);
    }
    const active = this.q<{ n: number }>("SELECT COUNT(*) AS n FROM participants WHERE left_at = 0")[0].n;
    if (active > 0) return;
    if (immediate || this.limits.callReconnectGraceMs <= 0) {
      await this.teardown("empty");
    } else {
      // Do not block the close handshake on the alarm write; the alarm only needs to exist before the
      // object can hibernate, and setAlarm is durable once it resolves.
      void this.ctx.storage.setAlarm(now + this.limits.callReconnectGraceMs).catch(() => undefined);
      this.alarmScheduled = true;
    }
  }

  private async closeTrack(sessionId: string, mid: string, pid: string): Promise<void> {
    const config = this.sfuConfig();
    if (!config) return;
    const options: SfuOptions = { timeoutMs: PROVIDER_CLEANUP_MS, maxResponseBytes: this.limits.maxSfuResponseBytes };
    try {
      const outcome = await Promise.race([
        this.serialize(`c:${pid}`, () => proxySfu(config, this.sfuFetch, "tracks-close", sessionId, { tracks: [{ mid }], force: true }, options)),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), PROVIDER_CLEANUP_BUDGET_MS)),
      ]);
      if (!outcome?.ok) { this.providerCleanupFailures++; return; }
      this.q("DELETE FROM pending_track_close WHERE session_id = ? AND mid = ?", sessionId, mid);
      this.q("UPDATE participants SET mid = NULL, track = NULL WHERE pid = ? AND session_id = ? AND mid = ?", pid, sessionId, mid);
    } catch { this.providerCleanupFailures++; }
  }

  private cancelAlarm(): void {
    if (!this.alarmScheduled) return;
    this.alarmScheduled = false;
    void this.ctx.storage.deleteAlarm().catch(() => undefined);
  }

  /**
   * Closes provider tracks best-effort within a 3 s budget, then wipes every row. The empty tables are
   * recreated so a later create can reuse the object; a dead room has no room or participant rows.
   */
  private async teardown(reason: string): Promise<void> {
    if (this.tearingDown) return;
    if (this.q<{ n: number }>("SELECT COUNT(*) AS n FROM pending_track_close")[0].n > 0) {
      for (const ws of this.ctx.getWebSockets()) safeClose(ws, 1000, reason);
      this.q("DELETE FROM room");
      await this.ctx.storage.setAlarm(nowMs() + this.limits.callHeartbeatRecheckMs);
      this.alarmScheduled = true;
      return;
    }
    this.tearingDown = true;
    try {
      const participants = this.q<ParticipantRow>("SELECT pid, session_id, mid FROM participants WHERE session_id IS NOT NULL");
      const config = this.sfuConfig();
      if (config && participants.length > 0) {
        const options: SfuOptions = {
          timeoutMs: Math.min(this.limits.sfuTimeoutMs, 2000),
          maxResponseBytes: this.limits.maxSfuResponseBytes,
        };
        const cleanup = Promise.allSettled(participants.map((p) =>
          p.session_id && p.mid
            ? proxySfu(config, this.sfuFetch, "tracks-close", p.session_id, { tracks: [{ mid: p.mid }], force: true }, options)
            : Promise.resolve(null),
        ));
        await Promise.race([cleanup, new Promise<void>((resolve) => setTimeout(resolve, 3000))]);
      }
      for (const ws of this.ctx.getWebSockets()) safeClose(ws, 1000, reason);
      await this.ctx.storage.deleteAlarm().catch(() => undefined);
      await this.ctx.storage.deleteAll();
      this.migrate();
    } finally {
      this.tearingDown = false;
    }
  }

  private broadcast(frame: unknown, exceptPid?: string): void {
    const data = JSON.stringify(frame);
    for (const ws of this.ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as CallSession | null;
      if (!session || session.pid === exceptPid) continue;
      try {
        ws.send(data);
      } catch {
        // Closing; the peer recovers from the next welcome roster.
      }
    }
  }

  // ---- Storage ---------------------------------------------------------------------------------

  private q<T extends Record<string, SqlStorageValue>>(query: string, ...params: SqlStorageValue[]): T[] {
    const cursor = this.sql.exec<T>(query, ...params);
    const result = cursor.toArray();
    this.rows.read += cursor.rowsRead;
    this.rows.written += cursor.rowsWritten;
    return result;
  }

  private recordAuth(auth: AuthOk): boolean {
    // Replay rows are needed only inside the 60 s NIP-98 window. Pruning keeps a long call's auth table
    // bounded instead of growing it once per request until teardown.
    this.q("DELETE FROM auth_seen WHERE expires_at < ?", nowS());
    const expiresAt = (auth.createdAt + AUTH_WINDOW_S + 1) * 1000;
    return this.q("INSERT INTO auth_seen (id, expires_at) VALUES (?, ?) ON CONFLICT(id) DO NOTHING RETURNING id", auth.eventId, expiresAt).length === 1;
  }

  private withMetrics(response: Response, start: { read: number; written: number }): Response {
    if (this.exposeMetrics) {
      response.headers.set("x-relay-sql-rows", `read=${this.rows.read - start.read};written=${this.rows.written - start.written}`);
    }
    return response;
  }

  private migrate(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS pending_track_close (session_id TEXT NOT NULL, mid TEXT NOT NULL, pid TEXT NOT NULL, PRIMARY KEY (session_id, mid)) WITHOUT ROWID");
    this.sql.exec("CREATE TABLE IF NOT EXISTS room (id TEXT PRIMARY KEY, secret_hash TEXT NOT NULL, creator TEXT NOT NULL, created_at INTEGER NOT NULL, last_activity INTEGER NOT NULL) WITHOUT ROWID");
    this.sql.exec("CREATE TABLE IF NOT EXISTS participants (pid TEXT PRIMARY KEY, pubkey TEXT NOT NULL, device TEXT NOT NULL, signer TEXT NOT NULL, session_id TEXT, prev_session_id TEXT, prev_session_at INTEGER NOT NULL DEFAULT 0, mid TEXT, track TEXT, muted INTEGER NOT NULL DEFAULT 0, deafened INTEGER NOT NULL DEFAULT 0, joined_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, left_at INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID");
    this.sql.exec("CREATE INDEX IF NOT EXISTS participants_identity ON participants (pubkey, device)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS auth_seen (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)");
    this.sql.exec("CREATE INDEX IF NOT EXISTS auth_seen_expiry ON auth_seen (expires_at)");
  }
}
