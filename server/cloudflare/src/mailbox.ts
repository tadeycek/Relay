import { DurableObject } from "cloudflare:workers";
import { AUTH_WINDOW_S, verifyRequestAuth, type AuthOutcome } from "./auth";
import {
  DELIVERY_ID,
  PROTOCOL_VERSION,
  PUBKEY,
  allowlistFrom,
  errorResponse,
  json,
  limitsFrom,
  sha256Hex,
  utf8,
  type Delivery,
  type DeliveryResult,
  type ErrorCode,
  type InboxMessage,
  type Limits,
} from "./protocol";

/**
 * Rows written are the scarcest Workers Free allowance (100,000/day), and every index entry a
 * statement touches counts. v2 cut the per-delivery and per-receipt write cost; see migrate().
 */
const SCHEMA_VERSION = 4;
/** WebSocket frames stay well under the platform's per-message limit even when HTTP pages are larger. */
const MAX_SOCKET_PAGE_BYTES = 512 * 1024;
const MAX_ACK_BODY_BYTES = 4096;
/** Cleanup scans a small table; once a minute is plenty and keeps burst traffic from paying for it each time. */
const PRUNE_INTERVAL_MS = 60_000;

/** Who a socket belongs to. Lives in the socket attachment, so it survives hibernation; nothing is kept in memory. */
interface Session {
  v: 1;
  pubkey: string;
  connectedAt: number;
  expiresAt: number;
  /** Live-signal rate window: start of the current minute and how many were relayed in it. */
  signalWindow?: number;
  signalCount?: number;
}

type AuthOk = Extract<AuthOutcome, { ok: true }>;
type SizedDelivery = Delivery & { size: number };

const nowS = () => Math.floor(Date.now() / 1000);
const isSeq = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;

function safeSend(ws: WebSocket, frame: unknown): void {
  try {
    ws.send(JSON.stringify(frame));
  } catch {
    // Already closing; the client recovers by catching up after reconnecting.
  }
}

function safeClose(ws: WebSocket, code: number, reason: string): void {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}

/**
 * One SQLite-backed Durable Object holding every pilot user's private mailbox. Ciphertext is opaque:
 * the envelope is the app's NIP-59 gift wrap, which only the recipient can open. The server knows
 * the authenticated sender, the recipient, sizes and timing (accepted for the pilot, 2026-10-18).
 *
 * All SQL for one request runs in a single synchronous section after the request's awaits, so
 * requests never interleave inside it. Platform output gates hold the response until those writes
 * are durable, so "stored" means stored.
 */
export class Mailbox extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  private readonly limits: Limits;
  private readonly allowed: Set<string>;
  private readonly exposeMetrics: boolean;
  /** New on every construction (first start, eviction, hibernation wake). Lets tests see a real restart. */
  private readonly instance = crypto.randomUUID();
  private readonly rows = { read: 0, written: 0 };
  /** In memory on purpose: after a restart the first write simply prunes again. */
  private lastPruneAt = 0;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    const vars = env as unknown as Record<string, unknown>;
    this.limits = limitsFrom(vars);
    this.allowed = allowlistFrom(vars.ALLOWED_PUBKEYS);
    this.exposeMetrics = vars.EXPOSE_METRICS === "1";
    this.migrate();
    // Keepalives are answered by the runtime without waking a hibernated object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    let response: Response;
    try {
      switch (`${request.method} ${url.pathname}`) {
        case "POST /v1/send": response = await this.handleSend(request); break;
        case "GET /v1/inbox": response = await this.handleInbox(request, url); break;
        case "POST /v1/ack": response = await this.handleAck(request); break;
        case "GET /v1/ws": response = await this.handleUpgrade(request); break;
        default: response = errorResponse(404, "not_found");
      }
    } catch (e) {
      console.error("mailbox request failed", { route: url.pathname, error: (e as Error)?.name });
      response = errorResponse(503, "storage_error", true);
    }
    if (response.status !== 101) response.headers.set("x-relay-instance", this.instance);
    return response;
  }

  // ---- HTTP handlers -------------------------------------------------------------------------

  private async handleSend(request: Request): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > this.limits.maxRequestBytes) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), this.allowed);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const parsed = parseSend(body, this.limits);
    if ("code" in parsed) return errorResponse(parsed.status, parsed.code);
    const hashes = await Promise.all(parsed.map((d) => sha256Hex(d.envelope)));

    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      const results = parsed.map((d, i) => this.store(auth.pubkey, d, hashes[i], now));
      this.prune(now);
      results.forEach((result, i) => {
        if (result.status === "stored") {
          const d = parsed[i];
          this.push(d.to, { seq: result.seq, from: auth.pubkey, id: d.id, envelope: d.envelope, storedAt: now });
        }
      });
      return json({ v: PROTOCOL_VERSION, results });
    });
  }

  private async handleInbox(request: Request, url: URL): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), this.allowed);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    // The mailbox is always the caller's own: there is deliberately no way to name another recipient.
    const query = parseInboxQuery(url, this.limits);
    if (!query) return errorResponse(400, "bad_request");

    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      return json({ v: PROTOCOL_VERSION, ...this.page(auth.pubkey, query.after, query.limit, this.limits.maxPageBytes) });
    });
  }

  private async handleAck(request: Request): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), this.allowed);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const through = parseAck(body);
    if (through === null) return errorResponse(400, "bad_request");

    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const acked = this.ack(auth.pubkey, through);
      this.prune(Date.now());
      return json({ v: PROTOCOL_VERSION, acked, through });
    });
  }

  private async handleUpgrade(request: Request): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return errorResponse(426, "upgrade_required");
    const auth = await verifyRequestAuth(request, null, nowS(), this.allowed);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const recorded = this.metered(() => (this.recordAuth(auth) ? json(null) : errorResponse(401, "auth_replayed")));
    if (!recorded.ok) return recorded;

    const now = Date.now();
    const open = this.ctx.getWebSockets(auth.pubkey)
      .map((ws) => ({ ws, connectedAt: (ws.deserializeAttachment() as Session | null)?.connectedAt ?? 0 }))
      .sort((a, b) => a.connectedAt - b.connectedAt);
    while (open.length >= this.limits.maxSocketsPerIdentity) safeClose(open.shift()!.ws, 4008, "replaced");

    const { 0: client, 1: server } = new WebSocketPair();
    // The pubkey tag is how pushes find a recipient's sockets, including after hibernation.
    this.ctx.acceptWebSocket(server, [auth.pubkey]);
    const session: Session = { v: 1, pubkey: auth.pubkey, connectedAt: now, expiresAt: now + this.limits.sessionTtlMs };
    server.serializeAttachment(session);
    safeSend(server, { type: "ready", v: PROTOCOL_VERSION, instance: this.instance, expiresAt: session.expiresAt });
    return new Response(null, { status: 101, webSocket: client });
  }

  // ---- WebSocket (hibernation API) -----------------------------------------------------------

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const session = this.session(ws);
    if (!session) return;
    let frame: Record<string, unknown> | null = null;
    try {
      const parsed = typeof raw === "string" ? JSON.parse(raw) : null;
      if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) frame = parsed;
    } catch {
      // Answered as bad_request below.
    }
    const ref = typeof frame?.ref === "string" && frame.ref.length <= 64 ? frame.ref : undefined;
    try {
      if (frame?.type === "sync" && isSeq(frame.after) && (frame.limit === undefined || isLimit(frame.limit))) {
        const limit = Math.min((frame.limit as number | undefined) ?? this.limits.maxPageMessages, this.limits.maxPageMessages);
        const bytes = Math.min(this.limits.maxPageBytes, MAX_SOCKET_PAGE_BYTES);
        safeSend(ws, { type: "batch", ref, ...this.page(session.pubkey, frame.after, limit, bytes) });
      } else if (frame?.type === "ack" && isSeq(frame.through)) {
        const acked = this.ack(session.pubkey, frame.through);
        this.prune(Date.now());
        safeSend(ws, { type: "acked", ref, acked, through: frame.through });
      } else if (frame?.type === "signal" && typeof frame.to === "string" && typeof frame.envelope === "string") {
        this.relaySignal(ws, session, frame.to, frame.envelope, ref);
      } else {
        safeSend(ws, { type: "error", ref, error: { code: "bad_request", retryable: false } });
      }
    } catch (e) {
      console.error("socket request failed", { error: (e as Error)?.name });
      safeSend(ws, { type: "error", ref, error: { code: "storage_error", retryable: true } });
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    safeClose(ws, code === 1005 ? 1000 : code, reason);
  }

  async webSocketError(): Promise<void> {
    // The runtime closes the socket; the client reconnects and catches up.
  }

  /** The socket's identity, or null after closing it when the session expired or was revoked. */
  private session(ws: WebSocket): Session | null {
    const s = ws.deserializeAttachment() as Session | null;
    if (!s || s.v !== 1 || !PUBKEY.test(s.pubkey)) {
      safeClose(ws, 4001, "auth_invalid");
      return null;
    }
    if (Date.now() >= s.expiresAt) {
      safeClose(ws, 4001, "auth_expired");
      return null;
    }
    if (!this.allowed.has(s.pubkey)) {
      safeClose(ws, 4003, "not_allowed");
      return null;
    }
    return s;
  }

  /**
   * Typing and presence: passed from socket to socket and never stored, so they cost no rows. A
   * recipient who isn't connected simply misses it, which is what best-effort signals want.
   */
  private relaySignal(ws: WebSocket, session: Session, to: string, envelope: string, ref: string | undefined): void {
    const refuse = (code: ErrorCode) => safeSend(ws, { type: "error", ref, error: { code, retryable: false } });
    if (!PUBKEY.test(to) || !this.allowed.has(to)) return refuse("unknown_recipient");
    if (envelope.length === 0 || utf8.encode(envelope).byteLength > this.limits.maxSignalBytes) return refuse("envelope_too_large");
    const minute = Math.floor(Date.now() / 60_000);
    const count = session.signalWindow === minute ? (session.signalCount ?? 0) + 1 : 1;
    ws.serializeAttachment({ ...session, signalWindow: minute, signalCount: count });
    if (count > this.limits.signalsPerMinute) return refuse("rate_limited");
    for (const target of this.ctx.getWebSockets(to)) {
      if (this.session(target)) safeSend(target, { type: "signal", from: session.pubkey, envelope });
    }
  }

  private push(recipient: string, message: InboxMessage): void {
    for (const ws of this.ctx.getWebSockets(recipient)) {
      if (this.session(ws)) safeSend(ws, { type: "message", ...message });
    }
  }

  // ---- Storage -------------------------------------------------------------------------------

  private store(sender: string, d: SizedDelivery, hash: string, now: number): DeliveryResult {
    const reject = (code: ErrorCode, retryable: boolean): DeliveryResult =>
      ({ to: d.to, id: d.id, status: "rejected", error: { code, retryable } });
    if (!this.allowed.has(d.to)) return reject("unknown_recipient", false);
    if (d.size > this.limits.maxEnvelopeBytes) return reject("envelope_too_large", false);
    try {
      return this.ctx.storage.transactionSync((): DeliveryResult => {
        const prior = this.q<{ seq: number; envelope_hash: string }>(
          "SELECT seq, envelope_hash FROM deliveries WHERE sender = ? AND recipient = ? AND client_id = ?",
          sender, d.to, d.id,
        )[0];
        // Idempotent retry while the message waits: same id and bytes return the original result, new
        // bytes under that id are refused. After the receipt the row is gone and a retry is stored anew;
        // the app's own payload-id check keeps it from showing twice.
        if (prior) return prior.envelope_hash === hash ? { to: d.to, id: d.id, status: "duplicate", seq: prior.seq } : reject("id_conflict", false);
        // Caps reject new work and never evict what was already accepted.
        if (this.pendingBytes(d.to) + d.size > this.limits.maxPendingBytesPerRecipient) return reject("recipient_full", true);
        if (this.pendingBytesTotal() + d.size > this.limits.maxPendingBytesTotal) return reject("mailbox_full", true);
        const seq = this.q<{ seq: number }>(
          "INSERT INTO deliveries (recipient, sender, client_id, envelope_hash, envelope, size, stored_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING seq",
          d.to, sender, d.id, hash, d.envelope, d.size, now,
        )[0].seq;
        this.addPendingBytes(d.to, d.size);
        return { to: d.to, id: d.id, status: "stored", seq };
      });
    } catch (e) {
      // transactionSync rolled back every write above.
      console.error("store failed", { error: (e as Error)?.name });
      return reject("storage_error", true);
    }
  }

  private page(recipient: string, after: number, limit: number, maxBytes: number) {
    // Only waiting messages have rows, so the scan never passes over acknowledged ones.
    const rows = this.q<{ seq: number; sender: string; client_id: string; envelope: string; stored_at: number; size: number }>(
      "SELECT seq, sender, client_id, envelope, stored_at, size FROM deliveries WHERE recipient = ? AND seq > ? ORDER BY seq LIMIT ?",
      recipient, after, limit + 1,
    );
    const messages: InboxMessage[] = [];
    let more = rows.length > limit;
    let bytes = 0;
    for (const row of rows.slice(0, limit)) {
      // Always return at least one message so a single large envelope can't stall the cursor.
      if (messages.length > 0 && bytes + row.size > maxBytes) {
        more = true;
        break;
      }
      bytes += row.size;
      messages.push({ seq: row.seq, from: row.sender, id: row.client_id, envelope: row.envelope, storedAt: row.stored_at });
    }
    return { messages, more, cursor: messages.at(-1)?.seq ?? after };
  }

  /**
   * Cumulative receipt: the recipient has durably stored everything up to [through], so those
   * messages are deleted. Nothing about them stays on the server (decided 2026-10-18).
   */
  private ack(recipient: string, through: number): number {
    return this.ctx.storage.transactionSync(() => {
      const freed = this.q<{ size: number }>("DELETE FROM deliveries WHERE recipient = ? AND seq <= ? RETURNING size", recipient, through);
      const bytes = freed.reduce((sum, row) => sum + row.size, 0);
      if (bytes > 0) this.q("UPDATE pending_bytes SET bytes = bytes - ? WHERE scope = ?", bytes, recipient);
      return freed.length;
    });
  }

  /** False if this auth event was already used. Kept until it could no longer pass the freshness check. */
  private recordAuth(auth: AuthOk): boolean {
    const expiresAt = (auth.createdAt + AUTH_WINDOW_S + 1) * 1000;
    return this.q("INSERT INTO auth_seen (id, expires_at) VALUES (?, ?) ON CONFLICT(id) DO NOTHING RETURNING id", auth.eventId, expiresAt).length === 1;
  }

  /** Bounded cleanup on write paths, at most once a minute, so there is no alarm to schedule or bill. */
  private prune(now: number): void {
    if (now - this.lastPruneAt < PRUNE_INTERVAL_MS) return;
    this.lastPruneAt = now;
    this.q("DELETE FROM auth_seen WHERE id IN (SELECT id FROM auth_seen WHERE expires_at < ? LIMIT 200)", now);
    // Messages nobody collected within the lifetime (e.g. for a phone that was wiped) would be
    // refused by the app as too old anyway; dropping them keeps the per-recipient caps honest.
    this.ctx.storage.transactionSync(() => {
      const stale = this.q<{ recipient: string; size: number }>(
        "DELETE FROM deliveries WHERE seq IN (SELECT seq FROM deliveries WHERE stored_at < ? LIMIT 200) RETURNING recipient, size",
        now - this.limits.pendingLifetimeMs,
      );
      const freed = new Map<string, number>();
      for (const row of stale) freed.set(row.recipient, (freed.get(row.recipient) ?? 0) + row.size);
      for (const [recipient, bytes] of freed) this.q("UPDATE pending_bytes SET bytes = bytes - ? WHERE scope = ?", bytes, recipient);
    });
  }

  private pendingBytes(recipient: string): number {
    return this.q<{ bytes: number }>("SELECT bytes FROM pending_bytes WHERE scope = ?", recipient)[0]?.bytes ?? 0;
  }

  /** Summed from the per-recipient rows (one per pilot user) instead of a total row that costs a write. */
  private pendingBytesTotal(): number {
    return this.q<{ bytes: number }>("SELECT COALESCE(SUM(bytes), 0) AS bytes FROM pending_bytes")[0].bytes;
  }

  private addPendingBytes(recipient: string, delta: number): void {
    this.q("INSERT INTO pending_bytes (scope, bytes) VALUES (?, ?) ON CONFLICT(scope) DO UPDATE SET bytes = bytes + excluded.bytes", recipient, delta);
  }

  /** Every query goes through here so per-request row counts (what Cloudflare bills) can be reported. */
  private q<T extends Record<string, SqlStorageValue>>(query: string, ...params: SqlStorageValue[]): T[] {
    const cursor = this.sql.exec<T>(query, ...params);
    const result = cursor.toArray();
    this.rows.read += cursor.rowsRead;
    this.rows.written += cursor.rowsWritten;
    return result;
  }

  /** Runs one synchronous storage section; storage failures become a retryable 503, never an empty success. */
  private metered(work: () => Response): Response {
    const start = { ...this.rows };
    let response: Response;
    try {
      response = work();
    } catch (e) {
      console.error("storage section failed", { error: (e as Error)?.name });
      response = errorResponse(503, "storage_error", true);
    }
    if (this.exposeMetrics) {
      response.headers.set("x-relay-sql-rows", `read=${this.rows.read - start.read};written=${this.rows.written - start.written}`);
    }
    return response;
  }

  private migrate(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    const current = Number(this.q<{ value: string }>("SELECT value FROM meta WHERE key = 'schema'")[0]?.value ?? 0);
    const steps: Array<() => void> = [() => this.schemaV1(), () => this.schemaV2(), () => this.schemaV3(), () => this.schemaV4()];
    for (let version = current + 1; version <= SCHEMA_VERSION; version++) {
      this.ctx.storage.transactionSync(() => {
        steps[version - 1]();
        this.sql.exec("INSERT INTO meta (key, value) VALUES ('schema', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", String(version));
      });
    }
  }

  /** The original layout; schemaV2 reshapes it. Kept so existing objects migrate step by step. */
  private schemaV1(): void {
    // envelope is NULL once the recipient acknowledged it; the row then only serves sender dedup.
    this.sql.exec(`CREATE TABLE IF NOT EXISTS deliveries (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      recipient TEXT NOT NULL,
      sender TEXT NOT NULL,
      client_id TEXT NOT NULL,
      envelope_hash TEXT NOT NULL,
      envelope TEXT,
      size INTEGER NOT NULL,
      stored_at INTEGER NOT NULL,
      delivered_at INTEGER,
      UNIQUE (sender, recipient, client_id)
    )`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS deliveries_pending ON deliveries (recipient, seq) WHERE envelope IS NOT NULL");
    this.sql.exec("CREATE INDEX IF NOT EXISTS deliveries_delivered ON deliveries (delivered_at) WHERE delivered_at IS NOT NULL");
    this.sql.exec("CREATE TABLE IF NOT EXISTS pending_bytes (scope TEXT PRIMARY KEY, bytes INTEGER NOT NULL)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS auth_seen (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL)");
    this.sql.exec("CREATE INDEX IF NOT EXISTS auth_seen_expiry ON auth_seen (expires_at)");
  }

  /**
   * Fewer rows written per message:
   *  - a plain (recipient, seq) index replaces the pending-only one, so a receipt no longer rewrites it;
   *  - auth_seen and pending_bytes become WITHOUT ROWID, one b-tree each instead of a table plus key index;
   *  - the "*" total row goes; the total is summed from the per-recipient rows.
   */
  private schemaV2(): void {
    this.sql.exec("CREATE INDEX IF NOT EXISTS deliveries_recipient ON deliveries (recipient, seq)");
    this.sql.exec("DROP INDEX IF EXISTS deliveries_pending");
    this.sql.exec("CREATE TABLE auth_seen_v2 (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL) WITHOUT ROWID");
    this.sql.exec("INSERT INTO auth_seen_v2 (id, expires_at) SELECT id, expires_at FROM auth_seen");
    this.sql.exec("DROP TABLE auth_seen");
    this.sql.exec("ALTER TABLE auth_seen_v2 RENAME TO auth_seen");
    this.sql.exec("CREATE TABLE pending_bytes_v2 (scope TEXT PRIMARY KEY, bytes INTEGER NOT NULL) WITHOUT ROWID");
    this.sql.exec("INSERT INTO pending_bytes_v2 (scope, bytes) SELECT scope, bytes FROM pending_bytes WHERE scope <> '*'");
    this.sql.exec("DROP TABLE pending_bytes");
    this.sql.exec("ALTER TABLE pending_bytes_v2 RENAME TO pending_bytes");
  }

  /**
   * pending_bytes becomes the per-recipient state row: it also records how far the recipient has
   * acknowledged, updated in the same row write a receipt already makes. Receipts are cumulative,
   * so the newest acknowledged seq still on record is a safe starting point for existing data.
   */
  private schemaV3(): void {
    this.sql.exec("ALTER TABLE pending_bytes ADD COLUMN acked_through INTEGER NOT NULL DEFAULT 0");
    this.sql.exec(`UPDATE pending_bytes SET acked_through = COALESCE(
      (SELECT MAX(seq) FROM deliveries WHERE deliveries.recipient = pending_bytes.scope AND envelope IS NULL), 0)`);
  }

  /**
   * Delete on receipt (decided 2026-10-18): acknowledged messages leave no row at all, so the
   * retained dedup records, their index and the receipt high-water mark go. AUTOINCREMENT keeps seq
   * growing even when every row is deleted, so client cursors stay valid.
   */
  private schemaV4(): void {
    this.sql.exec("DELETE FROM deliveries WHERE envelope IS NULL");
    this.sql.exec("DROP INDEX IF EXISTS deliveries_delivered");
    this.sql.exec("ALTER TABLE deliveries DROP COLUMN delivered_at");
    this.sql.exec("ALTER TABLE pending_bytes DROP COLUMN acked_through");
  }
}

// ---- Request parsing -----------------------------------------------------------------------

const isLimit = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 1;

function parseJson(body: ArrayBuffer): Record<string, unknown> | null {
  try {
    const value = JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(body));
    return value && typeof value === "object" && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

function parseSend(body: ArrayBuffer, limits: Limits): SizedDelivery[] | { status: number; code: ErrorCode } {
  const bad = { status: 400, code: "bad_request" as const };
  const value = parseJson(body);
  if (value?.v !== PROTOCOL_VERSION || !Array.isArray(value.deliveries) || value.deliveries.length === 0) return bad;
  if (value.deliveries.length > limits.maxDeliveriesPerRequest) return { status: 400, code: "too_many_deliveries" };
  const out: SizedDelivery[] = [];
  const keys = new Set<string>();
  for (const d of value.deliveries as unknown[]) {
    if (!d || typeof d !== "object") return bad;
    const { to, id, envelope } = d as Record<string, unknown>;
    if (typeof to !== "string" || !PUBKEY.test(to) || typeof id !== "string" || !DELIVERY_ID.test(id)) return bad;
    if (typeof envelope !== "string" || envelope.length === 0) return bad;
    const key = `${to}/${id}`;
    if (keys.has(key)) return bad;
    keys.add(key);
    out.push({ to, id, envelope, size: utf8.encode(envelope).byteLength });
  }
  return out;
}

function parseInboxQuery(url: URL, limits: Limits): { after: number; limit: number } | null {
  for (const key of url.searchParams.keys()) if (key !== "after" && key !== "limit") return null;
  const num = (name: string, fallback: number) => {
    const raw = url.searchParams.get(name);
    return raw === null ? fallback : /^\d{1,15}$/.test(raw) ? Number(raw) : NaN;
  };
  const after = num("after", 0);
  const limit = num("limit", limits.maxPageMessages);
  if (!isSeq(after) || !isLimit(limit)) return null;
  return { after, limit: Math.min(limit, limits.maxPageMessages) };
}

function parseAck(body: ArrayBuffer): number | null {
  const value = parseJson(body);
  return value?.v === PROTOCOL_VERSION && isSeq(value.through) ? value.through : null;
}
