import { DurableObject } from "cloudflare:workers";
import { AUTH_WINDOW_S, verifyRequestAuth, type AuthOutcome, type Membership } from "./auth";
import { PushSender, serviceAccountFrom } from "./push";
import {
  DELIVERY_ID,
  DEVICE_SEEN_REFRESH_MS,
  LINKED_DEVICE_ID,
  MAX_DEVICE_LABEL_CHARS,
  PRIMARY_DEVICE,
  PROTOCOL_VERSION,
  PUBKEY,
  allowlistFrom,
  deviceParam,
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
const SCHEMA_VERSION = 7;
/** At most one wake-up push per recipient this often; several messages in a row need only one. */
const WAKE_INTERVAL_MS = 20_000;
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
  /** A linked device's id. Absent for the primary and for every legacy client, so their attachment is unchanged. */
  device?: string;
  /** Live-signal rate window: start of the current minute and how many were relayed in it. */
  signalWindow?: number;
  signalCount?: number;
}

/** One row of `devices` (WP04). `gap_through` > 0 means a dormant return missed deletions up to that seq. */
interface DeviceRow extends Record<string, SqlStorageValue> {
  pubkey: string;
  device: string;
  label: string;
  acked: number;
  last_seen: number;
  added_at: number;
  gap_through: number;
  gap_shown: number;
}

/** Outcome of identifying the calling device: legacy identities cost no extra row reads. */
type DeviceAccess = { ok: true; linked: false } | { ok: true; linked: true; row: DeviceRow } | { ok: false; response: Response };

type AuthOk = Extract<AuthOutcome, { ok: true }>;
type SizedDelivery = Delivery & { size: number };

const nowS = () => Math.floor(Date.now() / 1000);
const deviceTag = (pubkey: string, device: string) => `d:${pubkey}:${device}`;
const isSeq = (v: unknown): v is number => Number.isSafeInteger(v) && (v as number) >= 0;

function safeSend(ws: WebSocket, frame: unknown): boolean {
  try {
    ws.send(JSON.stringify(frame));
    return true;
  } catch {
    // Already closing; the client recovers by catching up after reconnecting.
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
  /** Founders, set at deploy time (ALLOWED_PUBKEYS). Everyone else joins by QR pairing ([members]). */
  private readonly allowed: Set<string>;
  /** People admitted through pairing; a few dozen at most, so kept in memory and reloaded on start. */
  private readonly members = new Set<string>();
  private readonly membership: Membership = { has: (pubkey: string) => this.isMember(pubkey) };
  private readonly exposeMetrics: boolean;
  /** Null when no Firebase service account is configured: pushes are off. */
  private readonly push: PushSender | null;
  /** Replaceable in tests, so no request ever leaves the test runtime. */
  private pushFetch: (request: Request) => Promise<Response> = (request) => fetch(request);
  /** New on every construction (first start, eviction, hibernation wake). Lets tests see a real restart. */
  private readonly instance = crypto.randomUUID();
  private readonly rows = { read: 0, written: 0 };
  /** In memory on purpose: after a restart the first write simply prunes again. */
  private lastPruneAt = 0;
  /**
   * Identities that have a `devices` table row (WP04), loaded on start like [members]. Everyone else is a
   * legacy single-device identity and takes exactly the code path that existed before per-device cursors,
   * with no extra reads or writes.
   */
  private readonly linked = new Set<string>();

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    const vars = env as unknown as Record<string, unknown>;
    this.limits = limitsFrom(vars);
    this.allowed = allowlistFrom(vars.ALLOWED_PUBKEYS);
    this.exposeMetrics = vars.EXPOSE_METRICS === "1";
    const account = serviceAccountFrom(vars.FCM_SERVICE_ACCOUNT);
    this.push = account ? new PushSender(account, (request) => this.pushFetch(request)) : null;
    this.migrate();
    for (const row of this.q<{ pubkey: string }>("SELECT pubkey FROM members")) this.members.add(row.pubkey);
    for (const row of this.q<{ pubkey: string }>("SELECT DISTINCT pubkey FROM devices")) this.linked.add(row.pubkey);
    // Keepalives are answered by the runtime without waking a hibernated object.
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    let response: Response;
    try {
      if (url.pathname === "/v1/devices" || url.pathname.startsWith("/v1/devices/")) {
        response = await this.handleDevices(request, url);
        if (response.status !== 101) response.headers.set("x-relay-instance", this.instance);
        return response;
      }
      switch (`${request.method} ${url.pathname}`) {
        case "POST /v1/send": response = await this.handleSend(request); break;
        case "GET /v1/inbox": response = await this.handleInbox(request, url); break;
        case "POST /v1/ack": response = await this.handleAck(request, url); break;
        case "GET /v1/ws": response = await this.handleUpgrade(request, url); break;
        case "POST /v1/invite": response = await this.handleInvite(request); break;
        case "POST /v1/pair": response = await this.handlePair(request); break;
        case "POST /v1/push-token": response = await this.handlePushToken(request); break;
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
    const auth = await verifyRequestAuth(request, body, nowS(), this.membership);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const parsed = parseSend(body, this.limits);
    if ("code" in parsed) return errorResponse(parsed.status, parsed.code);
    const hashes = await Promise.all(parsed.map((d) => sha256Hex(d.envelope)));

    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      const results = parsed.map((d, i) => this.store(auth.pubkey, d, hashes[i], now));
      this.prune(now);
      const woken = new Set<string>();
      results.forEach((result, i) => {
        if (result.status === "stored") {
          const d = parsed[i];
          if (!this.pushLive(d.to, { seq: result.seq, from: auth.pubkey, id: d.id, envelope: d.envelope, storedAt: now }) && !woken.has(d.to)) {
            woken.add(d.to);
            this.ctx.waitUntil(this.wake(d.to, now));
          }
        }
      });
      return json({ v: PROTOCOL_VERSION, results });
    });
  }

  private async handleInbox(request: Request, url: URL): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), this.membership);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    // The mailbox is always the caller's own: there is deliberately no way to name another recipient.
    const query = parseInboxQuery(url, this.limits);
    if (!query) return errorResponse(400, "bad_request");

    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const access = this.enter(auth.pubkey, query.device, Date.now());
      if (!access.ok) return access.response;
      const page = this.page(auth.pubkey, query.after, query.limit, this.limits.maxPageBytes);
      return json({ v: PROTOCOL_VERSION, ...page, ...this.gapFields(auth.pubkey, access) });
    });
  }

  private async handleAck(request: Request, url: URL): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), this.membership);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const device = deviceParam(url);
    const through = parseAck(body);
    if (through === null || device === null) return errorResponse(400, "bad_request");

    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      const access = this.enter(auth.pubkey, device, now);
      if (!access.ok) return access.response;
      const acked = access.linked ? this.ackDevice(access.row, through, now) : this.ack(auth.pubkey, through);
      this.prune(now);
      return json({ v: PROTOCOL_VERSION, acked, through, ...this.ackGap(access) });
    });
  }

  private async handleUpgrade(request: Request, url: URL): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return errorResponse(426, "upgrade_required");
    const device = deviceParam(url);
    if (device === null) return errorResponse(400, "bad_request");
    const auth = await verifyRequestAuth(request, null, nowS(), this.membership);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const now = Date.now();
    let access: DeviceAccess = { ok: true, linked: false };
    const recorded = this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      access = this.enter(auth.pubkey, device, now);
      return access.ok ? json(null) : access.response;
    });
    if (!recorded.ok) return recorded;

    const open = this.ctx.getWebSockets(auth.pubkey)
      .map((ws) => ({ ws, connectedAt: (ws.deserializeAttachment() as Session | null)?.connectedAt ?? 0 }))
      .sort((a, b) => a.connectedAt - b.connectedAt);
    while (open.length >= this.limits.maxSocketsPerIdentity) safeClose(open.shift()!.ws, 4008, "replaced");

    const { 0: client, 1: server } = new WebSocketPair();
    // The pubkey tag is how pushes find a recipient's sockets, including after hibernation.
    // A linked device's sockets also carry a device tag, so removing the device can find and close them.
    this.ctx.acceptWebSocket(server, device === PRIMARY_DEVICE ? [auth.pubkey] : [auth.pubkey, deviceTag(auth.pubkey, device)]);
    const session: Session = { v: 1, pubkey: auth.pubkey, connectedAt: now, expiresAt: now + this.limits.sessionTtlMs };
    if (device !== PRIMARY_DEVICE) session.device = device;
    server.serializeAttachment(session);
    safeSend(server, { type: "ready", v: PROTOCOL_VERSION, instance: this.instance, expiresAt: session.expiresAt, ...this.gapFields(auth.pubkey, access) });
    return new Response(null, { status: 101, webSocket: client });
  }

  // ---- Devices: per-device cursors (WP04) ------------------------------------------------------
  //
  // One queue per identity. Each linked device has its own acknowledged cursor, and a message is deleted
  // once every ACTIVE device has acked it (the minimum of the active cursors). A device silent for longer
  // than `dormantAfterMs` stops holding the queue. THE `device` PARAMETER IS NOT AUTHENTICATION: every
  // device holding the Nostr secret can sign any value, so none of this is a security boundary (G1).

  /**
   * Identifies the calling device and applies dormancy, in this order: (1) look the device up, (2) decide
   * whether it was dormant using the stored last_seen, (3) only then refresh last_seen. If a dormant device
   * returns after others deleted past its cursor, a gap watermark is persisted before anything else can ack.
   * Legacy identities (no devices rows) return immediately without touching storage.
   */
  private enter(pubkey: string, device: string, now: number): DeviceAccess {
    if (!this.linked.has(pubkey)) {
      return device === PRIMARY_DEVICE ? { ok: true, linked: false } : { ok: false, response: errorResponse(403, "unknown_device") };
    }
    return this.ctx.storage.transactionSync((): DeviceAccess => {
      const row = this.q<DeviceRow>("SELECT * FROM devices WHERE pubkey = ? AND device = ?", pubkey, device)[0];
      if (!row) return { ok: false, response: errorResponse(403, "unknown_device") };
      const dormant = device !== PRIMARY_DEVICE && now - row.last_seen > this.limits.dormantAfterMs;
      if (dormant) {
        // Raise (never lower) the cursor to the deletion floor, so the min() below can't be dragged under it.
        const floor = this.q<{ floor: number }>("SELECT floor FROM cursor_state WHERE pubkey = ?", pubkey)[0]?.floor ?? 0;
        if (row.acked < floor) {
          if (floor > row.gap_through) row.gap_shown = 0;
          row.gap_through = Math.max(row.gap_through, floor);
          row.acked = floor;
        }
        row.last_seen = now;
        this.q("UPDATE devices SET acked = ?, last_seen = ?, gap_through = ?, gap_shown = ? WHERE pubkey = ? AND device = ?",
          row.acked, row.last_seen, row.gap_through, row.gap_shown, pubkey, device);
      } else if (now - row.last_seen >= DEVICE_SEEN_REFRESH_MS) {
        row.last_seen = now;
        this.q("UPDATE devices SET last_seen = ? WHERE pubkey = ? AND device = ?", now, pubkey, device);
      }
      return { ok: true, linked: true, row };
    });
  }

  /** `gap`/`oldest` for ready, inbox and batch frames. Marks the gap as shown, which lets a later ack clear it. */
  private gapFields(pubkey: string, access: DeviceAccess): { gap?: true; oldest?: number } {
    if (!access.ok || !access.linked || access.row.gap_through <= 0) return {};
    if (access.row.gap_shown === 0) {
      access.row.gap_shown = 1;
      this.q("UPDATE devices SET gap_shown = 1 WHERE pubkey = ? AND device = ?", pubkey, access.row.device);
    }
    const oldest = this.q<{ seq: number | null }>("SELECT MIN(seq) AS seq FROM deliveries WHERE recipient = ?", pubkey)[0]?.seq;
    return oldest === null || oldest === undefined ? { gap: true } : { gap: true, oldest };
  }

  private ackGap(access: DeviceAccess): { gap?: true } {
    return access.ok && access.linked && access.row.gap_through > 0 ? { gap: true } : {};
  }

  /** Highest seq ever assigned by the mailbox. AUTOINCREMENT keeps it even when every row is deleted. */
  private seqHead(): number {
    return this.q<{ seq: number }>("SELECT seq FROM sqlite_sequence WHERE name = 'deliveries'")[0]?.seq ?? 0;
  }

  /**
   * Device receipt. The cursor only moves forward and never past the mailbox head (a receipt naming a seq
   * beyond anything stored must not pre-acknowledge future messages). A gap is cleared only by an ack that
   * comes after the gap was shown to the device. Mutates [row] so callers see the new state.
   */
  private ackDevice(row: DeviceRow, through: number, now: number): number {
    return this.ctx.storage.transactionSync(() => {
      const head = this.seqHead();
      const acked = Math.max(row.acked, Math.min(through, head));
      let gapThrough = row.gap_through;
      let gapShown = row.gap_shown;
      if (gapThrough > 0 && gapShown === 1 && acked >= gapThrough) {
        gapThrough = 0;
        gapShown = 0;
      }
      if (acked !== row.acked || gapThrough !== row.gap_through || gapShown !== row.gap_shown) {
        this.q("UPDATE devices SET acked = ?, gap_through = ?, gap_shown = ? WHERE pubkey = ? AND device = ?",
          acked, gapThrough, gapShown, row.pubkey, row.device);
      }
      row.acked = acked;
      row.gap_through = gapThrough;
      row.gap_shown = gapShown;
      return this.deleteBelowMin(row.pubkey, now, head);
    });
  }

  /**
   * Deletes everything every active device has acked. The new floor (and the head it was computed against)
   * is persisted BEFORE the rows go, so a dormant device returning later can tell what it missed. The floor
   * only ever rises.
   */
  private deleteBelowMin(pubkey: string, now: number, head: number): number {
    const min = this.q<{ m: number | null }>(
      "SELECT MIN(acked) AS m FROM devices WHERE pubkey = ? AND (device = 'primary' OR last_seen >= ?)",
      pubkey, now - this.limits.dormantAfterMs,
    )[0]?.m;
    if (min === null || min === undefined) return 0;
    this.q(
      "INSERT INTO cursor_state (pubkey, floor, high) VALUES (?, ?, ?) ON CONFLICT(pubkey) DO UPDATE SET floor = excluded.floor, high = MAX(high, excluded.high) WHERE excluded.floor > floor",
      pubkey, min, head,
    );
    return this.ack(pubkey, min);
  }

  /** A socket whose device was removed: tell it, then close with 4009. */
  private revoked(ws: WebSocket, ref: string | undefined): void {
    safeSend(ws, { type: "error", ref, error: { code: "unknown_device", retryable: false } });
    safeClose(ws, 4009, "unlinked");
  }

  /** `POST/GET /v1/devices`, `DELETE /v1/devices/<id>`: primary only. */
  private async handleDevices(request: Request, url: URL): Promise<Response> {
    const target = url.pathname === "/v1/devices" ? null : url.pathname.slice("/v1/devices/".length);
    const allowed = target === null
      ? request.method === "POST" || request.method === "GET"
      : request.method === "DELETE";
    if (!allowed) return errorResponse(405, "method_not_allowed");
    const device = deviceParam(url);
    if (device === null) return errorResponse(400, "bad_request");
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), this.membership);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    let registration: { device: string; label: string } | null = null;
    if (request.method === "POST") {
      const value = parseJson(body);
      const id = value?.device;
      const label = value?.label ?? "";
      if (value?.v !== PROTOCOL_VERSION || typeof id !== "string" || !LINKED_DEVICE_ID.test(id) ||
          typeof label !== "string" || [...label].length > MAX_DEVICE_LABEL_CHARS) {
        return errorResponse(400, "bad_request");
      }
      registration = { device: id, label };
    }
    if (target !== null && target !== PRIMARY_DEVICE && !LINKED_DEVICE_ID.test(target)) return errorResponse(400, "bad_request");

    let unlinked = null as string | null;
    const response = this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      // The caller must be the primary. A known non-primary device is told so; a stranger is "unknown".
      if (device !== PRIMARY_DEVICE) {
        const known = this.linked.has(auth.pubkey) &&
          this.q("SELECT 1 FROM devices WHERE pubkey = ? AND device = ?", auth.pubkey, device).length > 0;
        return errorResponse(403, known ? "not_primary" : "unknown_device");
      }
      if (registration) return this.registerDevice(auth.pubkey, registration.device, registration.label, now);
      if (target === null) return this.listDevices(auth.pubkey, now);
      if (target === PRIMARY_DEVICE) return errorResponse(400, "bad_request");
      const removed = this.removeDevice(auth.pubkey, target, now);
      if (removed) unlinked = target;
      return removed ? json({ v: PROTOCOL_VERSION, removed: true }) : errorResponse(404, "unknown_device");
    });
    if (unlinked !== null) {
      for (const ws of this.ctx.getWebSockets(deviceTag(auth.pubkey, unlinked))) safeClose(ws, 4009, "unlinked");
    }
    return response;
  }

  private registerDevice(pubkey: string, device: string, label: string, now: number): Response {
    const result = this.ctx.storage.transactionSync((): Response | { head: number; existing: boolean } => {
      const head = this.seqHead();
      if (!this.linked.has(pubkey)) {
        // The implicit primary becomes an explicit row at the position it has acknowledged so far: everything
        // before the oldest waiting message is acked (or expired), and with nothing waiting that is the head.
        const oldest = this.q<{ seq: number | null }>("SELECT MIN(seq) AS seq FROM deliveries WHERE recipient = ?", pubkey)[0]?.seq;
        const position = oldest === null || oldest === undefined ? head : oldest - 1;
        this.q("INSERT INTO devices (pubkey, device, label, acked, last_seen, added_at) VALUES (?, 'primary', '', ?, ?, ?)", pubkey, position, now, now);
        this.q("INSERT INTO cursor_state (pubkey, floor, high) VALUES (?, ?, ?) ON CONFLICT(pubkey) DO NOTHING", pubkey, position, head);
      }
      const existing = this.q<DeviceRow>("SELECT * FROM devices WHERE pubkey = ? AND device = ?", pubkey, device)[0];
      if (existing) {
        // Idempotent: a retry or rename never moves the cursor (that would silently skip messages).
        if (existing.label !== label) this.q("UPDATE devices SET label = ? WHERE pubkey = ? AND device = ?", label, pubkey, device);
        return { head: existing.acked, existing: true };
      }
      const count = this.q<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE pubkey = ? AND device <> 'primary'", pubkey)[0].n;
      if (count >= this.limits.maxLinkedDevices) return errorResponse(409, "device_limit");
      // The new device starts at the head, in the same synchronous section as every store: no message can
      // slip between the snapshot and the row. It never receives history.
      this.q("INSERT INTO devices (pubkey, device, label, acked, last_seen, added_at) VALUES (?, ?, ?, ?, ?, ?)", pubkey, device, label, head, now, now);
      return { head, existing: false };
    });
    this.linked.add(pubkey);
    if (result instanceof Response) return result;
    return json({ v: PROTOCOL_VERSION, head: result.head, ...(result.existing ? { existing: true } : {}) });
  }

  private listDevices(pubkey: string, now: number): Response {
    const rows = this.q<DeviceRow>("SELECT * FROM devices WHERE pubkey = ? ORDER BY added_at, device", pubkey);
    const devices = rows.map((r) => ({
      device: r.device,
      label: r.label,
      added_at: r.added_at,
      last_seen: r.last_seen,
      active: r.device === PRIMARY_DEVICE || now - r.last_seen <= this.limits.dormantAfterMs,
    }));
    return json({ v: PROTOCOL_VERSION, devices, head: this.seqHead() });
  }

  /** Drops the cursor and frees whatever only that device was holding. False if there is no such device. */
  private removeDevice(pubkey: string, device: string, now: number): boolean {
    return this.ctx.storage.transactionSync(() => {
      if (!this.linked.has(pubkey)) return false;
      const gone = this.q("DELETE FROM devices WHERE pubkey = ? AND device = ? RETURNING device", pubkey, device);
      if (gone.length === 0) return false;
      this.deleteBelowMin(pubkey, now, this.seqHead());
      return true;
    });
  }

  // ---- Media support (RPC from MediaBlob) ----------------------------------------------------

  /**
   * One read for a media upload: membership of [pubkeys], and for each the ids of its devices that are active
   * now (always including `primary`). An identity without devices gets an empty list, which the media store
   * treats as the single implicit `primary`.
   */
  async mediaRecipients(pubkeys: string[]): Promise<{ members: boolean[]; devices: string[][] }> {
    const now = Date.now();
    const devices = pubkeys.map((pubkey) => {
      if (!this.linked.has(pubkey)) return [];
      return this.q<{ device: string }>(
        "SELECT device FROM devices WHERE pubkey = ? AND (device = 'primary' OR last_seen >= ?)",
        pubkey, now - this.limits.dormantAfterMs,
      ).map((r) => r.device);
    });
    return { members: pubkeys.map((p) => this.isMember(p)), devices };
  }

  /**
   * State of (pubkey, device) pairs for the media store: whether the device still exists (not unlinked),
   * whether it is active, and when it would turn dormant (0 = never, e.g. the primary).
   */
  async deviceStates(pairs: Array<[string, string]>): Promise<Array<{ exists: boolean; active: boolean; dormantAt: number }>> {
    const now = Date.now();
    return pairs.map(([pubkey, device]) => {
      if (device === PRIMARY_DEVICE) return { exists: true, active: true, dormantAt: 0 };
      if (!this.linked.has(pubkey)) return { exists: false, active: false, dormantAt: 0 };
      const row = this.q<DeviceRow>("SELECT * FROM devices WHERE pubkey = ? AND device = ?", pubkey, device)[0];
      if (!row) return { exists: false, active: false, dormantAt: 0 };
      const dormantAt = row.last_seen + this.limits.dormantAfterMs;
      return { exists: true, active: now <= dormantAt, dormantAt };
    });
  }

  // ---- Membership (joining by QR pairing) ----------------------------------------------------

  isMember(pubkey: string): boolean {
    return this.allowed.has(pubkey) || this.members.has(pubkey);
  }

  /** For the media store, which keeps no member list of its own. */
  async areMembers(pubkeys: string[]): Promise<boolean[]> {
    return pubkeys.map((p) => this.isMember(p));
  }

  /**
   * A member showing their QR registers its one-time code (only its SHA-256), so whoever scans it in
   * person can join. Codes expire with the invitation; each member keeps only their newest few.
   */
  private async handleInvite(request: Request): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), this.membership);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const value = parseJson(body);
    const code = value?.code;
    const expiresAt = value?.expiresAt;
    const now = Date.now();
    if (value?.v !== PROTOCOL_VERSION || typeof code !== "string" || !PUBKEY.test(code) || !Number.isSafeInteger(expiresAt)) {
      return errorResponse(400, "bad_request");
    }
    const until = Math.min(expiresAt as number, now + this.limits.inviteMaxTtlMs);
    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      this.q("DELETE FROM invites WHERE expires_at < ?", now);
      this.q("INSERT INTO invites (code_hash, inviter, expires_at) VALUES (?, ?, ?) ON CONFLICT(code_hash) DO NOTHING", code, auth.pubkey, until);
      this.q(
        "DELETE FROM invites WHERE inviter = ? AND code_hash NOT IN (SELECT code_hash FROM invites WHERE inviter = ? ORDER BY expires_at DESC LIMIT ?)",
        auth.pubkey, auth.pubkey, this.limits.maxInvitesPerMember,
      );
      return json({ v: PROTOCOL_VERSION, expiresAt: until });
    });
  }

  /**
   * Called by the phone that scanned [peer]'s QR, just before it sends the pairing request. A member
   * vouches for the peer they are pairing with in person; a newcomer joins with the code from a
   * member's QR (once, before it expires). Says whether both can now use the mailbox.
   */
  private async handlePair(request: Request): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    // Anyone may ask: a newcomer isn't a member yet. What they get depends on what they can prove.
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const value = parseJson(body);
    const peer = value?.peer;
    const code = value?.code;
    if (value?.v !== PROTOCOL_VERSION || typeof peer !== "string" || !PUBKEY.test(peer) || peer === auth.pubkey ||
        typeof code !== "string" || code.length === 0 || code.length > 128) {
      return errorResponse(400, "bad_request");
    }
    const codeHash = await sha256Hex(code);
    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      if (this.isMember(auth.pubkey)) {
        if (!this.isMember(peer)) this.admit(peer, auth.pubkey, now);
      } else {
        const invite = this.q<{ inviter: string }>(
          "DELETE FROM invites WHERE code_hash = ? AND inviter = ? AND expires_at >= ? RETURNING inviter",
          codeHash, peer, now,
        )[0];
        if (invite && this.isMember(invite.inviter)) this.admit(auth.pubkey, invite.inviter, now);
      }
      return json({ v: PROTOCOL_VERSION, you: this.isMember(auth.pubkey), peer: this.isMember(peer) });
    });
  }

  private admit(pubkey: string, by: string, now: number): void {
    if (this.allowed.size + this.members.size >= this.limits.maxMembers) return;
    this.q("INSERT INTO members (pubkey, added_by, added_at) VALUES (?, ?, ?) ON CONFLICT(pubkey) DO NOTHING", pubkey, by, now);
    this.members.add(pubkey);
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
        const access = this.enter(session.pubkey, session.device ?? PRIMARY_DEVICE, Date.now());
        if (!access.ok) return this.revoked(ws, ref);
        safeSend(ws, { type: "batch", ref, ...this.page(session.pubkey, frame.after, limit, bytes), ...this.gapFields(session.pubkey, access) });
      } else if (frame?.type === "ack" && isSeq(frame.through)) {
        const now = Date.now();
        const access = this.enter(session.pubkey, session.device ?? PRIMARY_DEVICE, now);
        if (!access.ok) return this.revoked(ws, ref);
        const acked = access.linked ? this.ackDevice(access.row, frame.through, now) : this.ack(session.pubkey, frame.through);
        this.prune(now);
        safeSend(ws, { type: "acked", ref, acked, through: frame.through, ...this.ackGap(access) });
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
    if (!this.isMember(s.pubkey)) {
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
    const refuse = (code: ErrorCode): void => {
      safeSend(ws, { type: "error", ref, error: { code, retryable: false } });
    };
    if (!PUBKEY.test(to) || !this.isMember(to)) return refuse("unknown_recipient");
    if (envelope.length === 0 || utf8.encode(envelope).byteLength > this.limits.maxSignalBytes) return refuse("envelope_too_large");
    const minute = Math.floor(Date.now() / 60_000);
    const count = session.signalWindow === minute ? (session.signalCount ?? 0) + 1 : 1;
    ws.serializeAttachment({ ...session, signalWindow: minute, signalCount: count });
    if (count > this.limits.signalsPerMinute) return refuse("rate_limited");
    for (const target of this.ctx.getWebSockets(to)) {
      if (this.session(target)) safeSend(target, { type: "signal", from: session.pubkey, envelope });
    }
  }

  /** Sends [message] to the recipient's open sockets; false if there were none to send to. */
  private pushLive(recipient: string, message: InboxMessage): boolean {
    let delivered = false;
    for (const ws of this.ctx.getWebSockets(recipient)) {
      if (this.session(ws)) {
        const sent = safeSend(ws, { type: "message", ...message });
        delivered = sent || delivered;
      }
    }
    return delivered;
  }

  // ---- Push (Firebase wake-ups) ---------------------------------------------------------------

  /** The phone's Firebase token, so a closed app can be woken; an empty token turns pushes off. */
  private async handlePushToken(request: Request): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), this.membership);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const parsed = parseJson(body);
    const token = parsed?.token;
    if (parsed?.v !== PROTOCOL_VERSION || typeof token !== "string" || token.length > 4096 || /\s/.test(token)) return errorResponse(400, "bad_request");
    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      if (token === "") this.q("DELETE FROM push_tokens WHERE pubkey = ?", auth.pubkey);
      else this.q(
        "INSERT INTO push_tokens (pubkey, token, updated_at) VALUES (?, ?, ?) ON CONFLICT(pubkey) DO UPDATE SET last_wake_at = CASE WHEN push_tokens.token = excluded.token THEN push_tokens.last_wake_at ELSE 0 END, token = excluded.token, updated_at = excluded.updated_at",
        auth.pubkey, token, Date.now(),
      );
      return json({ v: PROTOCOL_VERSION, push: token !== "" && this.push !== null });
    });
  }

  /** Wakes a recipient who has no open socket, at most once per [WAKE_INTERVAL_MS]. */
  private async wake(recipient: string, now: number): Promise<void> {
    if (!this.push) return;
    const registration = this.q<{ token: string; last_wake_at: number }>("SELECT token, last_wake_at FROM push_tokens WHERE pubkey = ?", recipient)[0];
    if (!registration || now - registration.last_wake_at < WAKE_INTERVAL_MS) return;
    this.q("UPDATE push_tokens SET last_wake_at = ? WHERE pubkey = ?", now, recipient);
    const result = await this.push.wake(registration.token);
    if (result === "unregistered") this.q("DELETE FROM push_tokens WHERE pubkey = ? AND token = ?", recipient, registration.token);
    else if (result === "failed") console.error("wake-up push failed");
  }

  // ---- Storage -------------------------------------------------------------------------------

  private store(sender: string, d: SizedDelivery, hash: string, now: number): DeliveryResult {
    const reject = (code: ErrorCode, retryable: boolean): DeliveryResult =>
      ({ to: d.to, id: d.id, status: "rejected", error: { code, retryable } });
    if (!this.isMember(d.to)) return reject("unknown_recipient", false);
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
      const stale = this.q<{ recipient: string; size: number; seq: number }>(
        "DELETE FROM deliveries WHERE seq IN (SELECT seq FROM deliveries WHERE stored_at < ? LIMIT 200) RETURNING recipient, size, seq",
        now - this.limits.pendingLifetimeMs,
      );
      const freed = new Map<string, number>();
      const expiredThrough = new Map<string, number>();
      for (const row of stale) {
        freed.set(row.recipient, (freed.get(row.recipient) ?? 0) + row.size);
        if (this.linked.has(row.recipient)) expiredThrough.set(row.recipient, Math.max(expiredThrough.get(row.recipient) ?? 0, row.seq));
      }
      // Expiry deletes without every device's receipt: raise the floor so a returning device sees a gap.
      for (const [recipient, seq] of expiredThrough) {
        // Unlike dormancy, expiry can happen while a device is active. Persist its gap now;
        // enter() only detects a missing floor when the caller has gone dormant.
        this.q("UPDATE devices SET gap_through = ?, gap_shown = 0 WHERE pubkey = ? AND acked < ? AND gap_through < ?",
          seq, recipient, seq, seq);
        this.q("UPDATE cursor_state SET floor = ?, high = MAX(high, ?) WHERE pubkey = ? AND floor < ?", seq, seq, recipient, seq);
      }
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
    const steps: Array<() => void> = [
      () => this.schemaV1(), () => this.schemaV2(), () => this.schemaV3(), () => this.schemaV4(), () => this.schemaV5(),
      () => this.schemaV6(), () => this.schemaV7(),
    ];
    this.migrateDevices();
    for (let version = current + 1; version <= SCHEMA_VERSION; version++) {
      this.ctx.storage.transactionSync(() => {
        steps[version - 1]();
        this.sql.exec("INSERT INTO meta (key, value) VALUES ('schema', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", String(version));
      });
    }
  }

  /**
   * WP04 tables. They follow the same additive, idempotent pattern as the numbered steps but are tracked by
   * their own meta key, so `schema` keeps its value of 7 and the existing migration test stays unchanged
   * (WP04-AC1). Legacy identities never write to these tables.
   */
  private migrateDevices(): void {
    const done = this.q<{ value: string }>("SELECT value FROM meta WHERE key = 'devices_schema'")[0]?.value === "1";
    if (done) return;
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (
        pubkey TEXT NOT NULL,
        device TEXT NOT NULL,
        label TEXT NOT NULL DEFAULT '',
        acked INTEGER NOT NULL,
        last_seen INTEGER NOT NULL,
        added_at INTEGER NOT NULL,
        gap_through INTEGER NOT NULL DEFAULT 0,
        gap_shown INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY (pubkey, device)
      ) WITHOUT ROWID`);
      // Per identity: the highest deletion floor ever reached and the seq head it was computed against.
      this.sql.exec("CREATE TABLE IF NOT EXISTS cursor_state (pubkey TEXT PRIMARY KEY, floor INTEGER NOT NULL, high INTEGER NOT NULL) WITHOUT ROWID");
      this.sql.exec("INSERT INTO meta (key, value) VALUES ('devices_schema', '1') ON CONFLICT(key) DO UPDATE SET value = excluded.value");
    });
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

  /** Joining by QR pairing: admitted members, and one-time invites (code hashes) from members' QR codes. */
  private schemaV5(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS members (pubkey TEXT PRIMARY KEY, added_by TEXT NOT NULL, added_at INTEGER NOT NULL) WITHOUT ROWID");
    this.sql.exec("CREATE TABLE IF NOT EXISTS invites (code_hash TEXT PRIMARY KEY, inviter TEXT NOT NULL, expires_at INTEGER NOT NULL) WITHOUT ROWID");
  }

  /** Each member's Firebase token (one device per identity), for content-free wake-up pushes. */
  private schemaV6(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS push_tokens (pubkey TEXT PRIMARY KEY, token TEXT NOT NULL, updated_at INTEGER NOT NULL) WITHOUT ROWID");
  }

  private schemaV7(): void {
    const columns = this.sql.exec<{ name: string }>("PRAGMA table_info(push_tokens)").toArray();
    if (!columns.some((column) => column.name === "last_wake_at")) {
      this.sql.exec("ALTER TABLE push_tokens ADD COLUMN last_wake_at INTEGER NOT NULL DEFAULT 0");
    }
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

function parseInboxQuery(url: URL, limits: Limits): { after: number; limit: number; device: string } | null {
  for (const key of url.searchParams.keys()) if (key !== "after" && key !== "limit" && key !== "device") return null;
  const device = deviceParam(url);
  if (device === null) return null;
  const num = (name: string, fallback: number) => {
    const raw = url.searchParams.get(name);
    return raw === null ? fallback : /^\d{1,15}$/.test(raw) ? Number(raw) : NaN;
  };
  const after = num("after", 0);
  const limit = num("limit", limits.maxPageMessages);
  if (!isSeq(after) || !isLimit(limit)) return null;
  return { after, limit: Math.min(limit, limits.maxPageMessages), device };
}

function parseAck(body: ArrayBuffer): number | null {
  const value = parseJson(body);
  return value?.v === PROTOCOL_VERSION && isSeq(value.through) ? value.through : null;
}
