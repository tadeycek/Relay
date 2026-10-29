import { DurableObject } from "cloudflare:workers";
import { AUTH_WINDOW_S, verifyRequestAuth, type AuthOutcome, type Membership } from "./auth";
import { mintIceServers, turnConfigFrom, type IceServer, type TurnConfig } from "./call";
import { PushSender, serviceAccountFrom } from "./push";
import {
  DELIVERY_ID,
  DEVICE_SEEN_REFRESH_MS,
  LINK_PROOF_HEADER,
  LINK_SLOT,
  LINKED_DEVICE_ID,
  MEDIA_DIGEST,
  MAX_ADMIN_OPS_PER_MINUTE,
  MAX_DEVICE_LABEL_CHARS,
  MAX_SYNC_REQUEST_BYTES,
  PRIMARY_DEVICE,
  PROTOCOL_VERSION,
  PUBKEY,
  SYNC_ID,
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
  CALL_SLOT_TAKEOVER_GRACE_MS,
} from "./protocol";

/**
 * Rows written are the scarcest Workers Free allowance (100,000/day), and every index entry a
 * statement touches counts. v2 cut the per-delivery and per-receipt write cost; see migrate().
 */
const SCHEMA_VERSION = 7;
/** A grant that could not reach its blob is retried this often (only while one is outstanding). */
const MEDIA_GRANT_RETRY_MS = 60 * 1000;
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
  /**
   * The key that signed this upgrade. Authorization is re-checked against live state on every frame and
   * push: a shared identity key stops working the moment the identity migrates, and a device key stops
   * working when its device is revoked or rotated, even if a close sweep never reached this socket.
   * Absent only on attachments written before this field existed (treated as the identity key).
   */
  signer?: string;
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
  /** Empty for WP04 rows that predate G1; the device key's public key otherwise. */
  auth_pubkey: string;
  protocol_version: number;
  /** 0 while active; ms timestamp once revoked. Revoked rows keep the cursor for audit and are ignored by the queue. */
  revoked_at: number;
}

/** The sealed bundle is opaque bytes; only its size and lifetime are interpreted. */
interface LinkSlotRow extends Record<string, SqlStorageValue> {
  slot: string;
  pubkey: string;
  bundle: ArrayBuffer;
  size: number;
  created_at: number;
  expires_at: number;
  lease_until: number;
}

/** One uploaded part of a versioned sync envelope (WP05/G3). */
interface SyncPartRow extends Record<string, SqlStorageValue> {
  pubkey: string;
  id: string;
  rev: number;
  part: number;
  parts: number;
  total: number;
  hash: string;
  expires_at: number;
  data: ArrayBuffer;
}

interface SyncEventRow extends Record<string, SqlStorageValue> {
  seq: number;
  pubkey: string;
  id: string;
  rev: number;
  total: number;
  hash: string;
  body: ArrayBuffer;
  created_at: number;
  expires_at: number;
}

/** A parsed, validated sync part. Never trusted beyond these bounds. */
interface SyncPart {
  id: string;
  rev: number;
  part: number;
  parts: number;
  total: number;
  hash: string;
  expiresAt: number;
  data: Uint8Array;
}

/** A request that passed device authorization: the shared identity, the device acting, and whether it is the admin key. */
interface Authorized {
  auth: AuthOk;
  identity: string;
  device: string;
  admin: boolean;
  legacy: boolean;
  /** The public key that signed the request. Used to re-check the binding inside write transactions. */
  signer: string;
}

/** Outcome of identifying the calling device: legacy identities cost no extra row reads. */
type DeviceAccess = { ok: true; linked: false } | { ok: true; linked: true; row: DeviceRow } | { ok: false; response: Response };

type AuthOk = Extract<AuthOutcome, { ok: true }>;
/** WP15: the outcome of resolving a call-room caller. */
export type CallAuthOutcome =
  | { ok: true; identity: string; device: string; member: boolean; revoked: boolean }
  | { ok: false; code: "auth_invalid" | "not_primary" | "unknown_device" };
/** G5 signed roster: `{ok:false}` means this group is pinned to a different owner key. */
export type GroupOwnerOutcome = { ok: true; pinned: boolean } | { ok: false };
/** G5: the outcome of claiming the one live room slot of a group revision. */
export type CallSlotOutcome =
  | { ok: true; superseded: string | null }
  | { ok: false; code: "conflict"; room: string }
  | { ok: false; code: "stale_revision" }
  | { ok: false; code: "newer"; room: string }
  | { ok: false; code: "creator_full"; rooms: string[] }
  | { ok: false; code: "full" };
/** [urgent] is the WP11 call-invite flag: true only when the client sent `"urgent": true`. */
type SizedDelivery = Delivery & { size: number; urgent: boolean };
/** A send whose messages were queued together with their media grants (WP04-AC4). */
type Queued = { results: DeliveryResult[]; grantIds: Map<string, number> };

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
  /** `media_grants` is created on the first send that attaches media, so legacy storage is untouched. */
  private grantTableReady = false;
  /** Admin key -> shared identity. Only the phone holds the admin key (G1). */
  private readonly adminByKey = new Map<string, string>();
  /** Identities that have migrated to per-device keys. Their shared identity key is disabled for device operations. */
  private readonly migrated = new Set<string>();
  /** Best-effort admin-operation rate limiter: identity -> timestamps within the last minute. */
  private readonly adminOps = new Map<string, number[]>();
  /**
   * Best-effort link-proof limiter, keyed `s:<slot>` and `i:<client>`. In memory: a restart forgets it,
   * and the cost of a wrong guess to the server is one indexed read. Exposed for tests.
   */
  private readonly linkAttempts = new Map<string, number[]>();
  /** Replaceable in tests, so no request ever leaves the test runtime. WP11: the TURN provider call. */
  private turnFetch: (request: Request) => Promise<Response> = (request) => fetch(request);
  /** The TURN secrets from the Worker env, read per request so a rotated secret takes effect without a restart. */
  private turnConfig: () => TurnConfig | null = () => turnConfigFrom(this.env as unknown as Record<string, unknown>);
  /** Best-effort ICE limiter: identity -> timestamps within the last minute. In memory, bounded by the member count. */
  private readonly iceOps = new Map<string, number[]>();
  /** One minted answer (or one in flight) per identity, reused for [Limits.callIceCacheMs]. Never holds a failure. */
  private readonly iceCache = new Map<string, { mintedAt: number; result: Promise<{ iceServers: IceServer[]; expiresAt: number } | null> }>();
  /** Urgent-wake budgets (WP11/G4), in memory: a restart forgets them, and a restart needs an idle object. */
  private readonly urgentRecipientAt = new Map<string, number>();
  private readonly urgentSenderOps = new Map<string, number[]>();
  private urgentGlobalOps: number[] = [];

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
    for (const row of this.q<{ pubkey: string; admin_pubkey: string }>("SELECT pubkey, admin_pubkey FROM identity_admin")) {
      this.migrated.add(row.pubkey);
      this.adminByKey.set(row.admin_pubkey, row.pubkey);
    }
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
      if (url.pathname.startsWith("/v1/link/") || url.pathname === "/v1/link") {
        response = await this.handleLink(request, url);
        if (response.status !== 101) response.headers.set("x-relay-instance", this.instance);
        return response;
      }
      if (url.pathname === "/v1/sync" || url.pathname.startsWith("/v1/sync/")) {
        response = await this.handleSync(request, url);
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
        case "GET /v1/call/ice": response = await this.handleCallIce(request, url); break;
        default:
          if (url.pathname === "/v1/call/ice") {
            response = errorResponse(405, "method_not_allowed");
            break;
          }
          response = errorResponse(404, "not_found");
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
    const authorized = await this.authorize(request, body, new URL(request.url));
    if (authorized instanceof Response) return authorized;
    const parsed = parseSend(body, this.limits, authorized.identity);
    if ("code" in parsed) return errorResponse(parsed.status, parsed.code);
    const hashes = await Promise.all(parsed.deliveries.map((d) => sha256Hex(d.envelope)));

    // WP11: an urgent (call-invite) delivery spends a separate, tighter budget. This early check only
    // saves work; the authoritative check-and-spend happens in the synchronous stored section, because a
    // media send awaits an RPC in between and concurrent requests would otherwise all pass this gate.
    const urgentDelivery = parsed.deliveries.find((d) => d.urgent);
    if (urgentDelivery && !this.urgentAllowed(authorized.identity, urgentDelivery.to, Date.now())) {
      return errorResponse(429, "rate_limited", true);
    }

    // No `media` field (every client today): the original path, with no extra read, write or RPC.
    if (parsed.media.length === 0) return this.metered(() => this.sendStored(authorized, parsed.deliveries, hashes, Date.now(), [] as []));
    return this.measured(() => this.sendWithMedia(authorized, parsed.deliveries, hashes, parsed.media));
  }

  /**
   * `POST /v1/send` with an authenticated `media` list (WP04-AC4). In order:
   *  1. every digest must be a live blob uploaded by this sender (RPC, no mailbox storage touched); otherwise
   *     409 media_unavailable and NOTHING is queued;
   *  2. one synchronous transaction queues the messages and writes one outbox row per digest holding the
   *     (recipient, device) pairs active at that instant, for the recipients whose copy was newly stored;
   *  3. the outbox is drained: each blob adds those pairs (idempotent). A failure leaves the row for a retry
   *     (next media send, or the alarm); the message is already queued and the reply says `pending`.
   */
  private async sendWithMedia(authorized: Authorized, parsed: SizedDelivery[], hashes: string[], media: string[]): Promise<Response> {
    const uploader = authorized.identity;
    const usable = await Promise.all(media.map((h) => this.blob(h).attachCheck(uploader)));
    if (!usable.every(Boolean)) return errorResponse(409, "media_unavailable");

    const queued = this.sendStored(authorized, parsed, hashes, Date.now(), media);
    if (queued instanceof Response) return queued;
    const { results, grantIds } = queued;
    const outcome = await this.drainGrants();
    const entries = media.map((hash) => {
      const id = grantIds.get(hash);
      return { hash, status: id === undefined ? "unchanged" : (outcome.statuses.get(id) ?? "pending") };
    });
    const response = json({ v: PROTOCOL_VERSION, results, media: entries });
    if (this.exposeMetrics) response.headers.set("x-relay-media-rows", `read=${outcome.blobRows.read};written=${outcome.blobRows.written}`);
    return response;
  }

  private blob(hash: string) {
    return this.env.MEDIA.get(this.env.MEDIA.idFromName(hash), { locationHint: "weur" });
  }

  private ensureGrantTable(): void {
    if (this.grantTableReady) return;
    this.sql.exec("CREATE TABLE IF NOT EXISTS media_grants (id INTEGER PRIMARY KEY AUTOINCREMENT, digest TEXT NOT NULL, uploader TEXT NOT NULL, pairs TEXT NOT NULL, created_at INTEGER NOT NULL)");
    this.grantTableReady = true;
  }

  /** Applies queued grants to their blobs. Every blob call is idempotent, so concurrent drains are harmless. */
  private async drainGrants(): Promise<{ statuses: Map<number, "attached" | "gone" | "pending">; blobRows: { read: number; written: number } }> {
    this.ensureGrantTable();
    const statuses = new Map<number, "attached" | "gone" | "pending">();
    const blobRows = { read: 0, written: 0 };
    const now = Date.now();
    const rows = this.q<{ id: number; digest: string; uploader: string; pairs: string; created_at: number }>(
      "SELECT id, digest, uploader, pairs, created_at FROM media_grants ORDER BY id LIMIT 64");
    await Promise.all(rows.map(async (row) => {
      if (now - row.created_at > this.limits.mediaLifetimeMs) {
        this.q("DELETE FROM media_grants WHERE id = ?", row.id);
        statuses.set(row.id, "gone");
        return;
      }
      try {
        const r = await this.blob(row.digest).grant(row.uploader, JSON.parse(row.pairs) as Array<[string, string]>);
        blobRows.read += r.rows.read;
        blobRows.written += r.rows.written;
        this.q("DELETE FROM media_grants WHERE id = ?", row.id);
        statuses.set(row.id, r.status);
      } catch (e) {
        console.error("media grant failed", { error: (e as Error)?.name });
        statuses.set(row.id, "pending");
      }
    }));
    if (this.q("SELECT 1 FROM media_grants LIMIT 1").length > 0) await this.ctx.storage.setAlarm(Date.now() + MEDIA_GRANT_RETRY_MS);
    return { statuses, blobRows };
  }

  /** Retries grants whose blob could not be reached when the message was queued. */
  async alarm(): Promise<void> {
    await this.drainGrants();
  }

  /**
   * The write section of `POST /v1/send`. Authorization was checked before the (awaited) body hashing;
   * a revocation or a migration can land in between, so the binding is re-read here, in the same
   * synchronous section as the inserts. Without this a send authorized a moment before a DELETE would
   * still be stored.
   *
   * With [media] the messages and the grant outbox rows are one transaction (no await inside), so either both
   * exist or neither does. Without it the section is exactly the original one and returns a plain Response.
   */
  private sendStored(authorized: Authorized, parsed: SizedDelivery[], hashes: string[], now: number, media?: []): Response;
  private sendStored(authorized: Authorized, parsed: SizedDelivery[], hashes: string[], now: number, media: string[]): Response | Queued;
  private sendStored(authorized: Authorized, parsed: SizedDelivery[], hashes: string[], now: number, media: string[] = []): Response | Queued {
    const section = (): Response | Queued => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      if (!this.sendBindingActive(authorized)) return errorResponse(401, "auth_revoked");
      // WP11: the urgent budget is checked AND spent inside this synchronous section. Checking it earlier
      // (before the media attachCheck RPC) let concurrent requests all pass the check before any of them
      // recorded a spend, which is a push-wake flood. noteUrgent below runs after the inserts, still with
      // no await in between, so the next request cannot enter the same window.
      const urgent = parsed.find((d) => d.urgent);
      if (urgent && !this.urgentAllowed(authorized.identity, urgent.to, now)) {
        return errorResponse(429, "rate_limited", true);
      }
      const results = parsed.map((d, i) => this.store(authorized.identity, d, hashes[i], now));
      this.prune(now);
      return { results, grantIds: media.length > 0 ? this.enqueueGrants(authorized.identity, parsed, results, media, now) : new Map() };
    };
    const done = media.length > 0 ? this.ctx.storage.transactionSync(section) : section();
    if (done instanceof Response) return done;
    const { results } = done;
    // Recipients with no open socket, in first-seen order; true when one of their stored messages is urgent.
    const offline = new Map<string, boolean>();
    results.forEach((result, i) => {
      if (result.status === "stored") {
        const d = parsed[i];
        if (d.urgent) this.noteUrgent(authorized.identity, d.to, now);
        if (!this.pushLive(d.to, { seq: result.seq, from: authorized.identity, id: d.id, envelope: d.envelope, storedAt: now })) {
          offline.set(d.to, (offline.get(d.to) ?? false) || d.urgent);
        }
      }
    });
    for (const [recipient, urgent] of offline) this.ctx.waitUntil(this.wake(recipient, now, urgent));
    return media.length > 0 ? done : json({ v: PROTOCOL_VERSION, results });
  }

  /**
   * Outbox rows for a send that attached blobs: for every digest, the devices of each recipient whose copy was
   * newly STORED (duplicates and rejections get nothing) that are active right now, i.e. exactly the devices
   * that will see this message. A device enrolled later, a revoked or a dormant device is not in the set.
   */
  private enqueueGrants(sender: string, parsed: SizedDelivery[], results: DeliveryResult[], media: string[], now: number): Map<string, number> {
    const ids = new Map<string, number>();
    const recipients = [...new Set(results.flatMap((r, i) => (r.status === "stored" ? [parsed[i].to] : [])))];
    if (recipients.length === 0) return ids;
    this.ensureGrantTable();
    const pairs = recipients.flatMap((pubkey) => this.activeDevices(pubkey, now).map((device): [string, string] => [pubkey, device]));
    const text = JSON.stringify(pairs);
    for (const hash of media) {
      ids.set(hash, this.q<{ id: number }>("INSERT INTO media_grants (digest, uploader, pairs, created_at) VALUES (?, ?, ?, ?) RETURNING id", hash, sender, text, now)[0].id);
    }
    return ids;
  }

  /** Device ids that receive messages now: not revoked, and the primary or seen within the dormancy window. Identities without devices: primary. */
  private activeDevices(pubkey: string, now: number): string[] {
    if (!this.linked.has(pubkey)) return [PRIMARY_DEVICE];
    return this.q<{ device: string }>(
      "SELECT device FROM devices WHERE pubkey = ? AND revoked_at = 0 AND (device = 'primary' OR last_seen >= ?)",
      pubkey, now - this.limits.dormantAfterMs,
    ).map((r) => r.device);
  }

  /** Whether the caller's key may still act at this instant: not migrated away, not revoked. */
  private sendBindingActive(authorized: Authorized): boolean {
    if (authorized.legacy && this.migrated.has(authorized.identity)) return false;
    if (!this.linked.has(authorized.identity)) return true;
    const row = this.q<DeviceRow>("SELECT revoked_at FROM devices WHERE pubkey = ? AND device = ?", authorized.identity, authorized.device)[0];
    return !!row && row.revoked_at === 0;
  }

  private async handleInbox(request: Request, url: URL): Promise<Response> {
    const authorized = await this.authorize(request, null, url);
    if (authorized instanceof Response) return authorized;
    // The mailbox is always the caller's own: there is deliberately no way to name another recipient.
    const query = parseInboxQuery(url, this.limits);
    if (!query) return errorResponse(400, "bad_request");

    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      const access = this.enter(authorized.identity, authorized.device, Date.now());
      if (!access.ok) return access.response;
      const page = this.page(authorized.identity, query.after, query.limit, this.limits.maxPageBytes);
      return json({ v: PROTOCOL_VERSION, ...page, ...this.gapFields(authorized.identity, access) });
    });
  }

  private async handleAck(request: Request, url: URL): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const authorized = await this.authorize(request, body, url);
    if (authorized instanceof Response) return authorized;
    const through = parseAck(body);
    if (through === null) return errorResponse(400, "bad_request");

    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      const access = this.enter(authorized.identity, authorized.device, now);
      if (!access.ok) return access.response;
      const acked = access.linked ? this.ackDevice(access.row, through, now) : this.ack(authorized.identity, through);
      this.prune(now);
      return json({ v: PROTOCOL_VERSION, acked, through, ...this.ackGap(access) });
    });
  }

  private async handleUpgrade(request: Request, url: URL): Promise<Response> {
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") return errorResponse(426, "upgrade_required");
    const authorized = await this.authorize(request, null, url);
    if (authorized instanceof Response) return authorized;
    const now = Date.now();
    let access: DeviceAccess = { ok: true, linked: false };
    const recorded = this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      access = this.enter(authorized.identity, authorized.device, now);
      return access.ok ? json(null) : access.response;
    });
    if (!recorded.ok) return recorded;

    const open = this.ctx.getWebSockets(authorized.identity)
      .map((ws) => ({ ws, connectedAt: (ws.deserializeAttachment() as Session | null)?.connectedAt ?? 0 }))
      .sort((a, b) => a.connectedAt - b.connectedAt);
    while (open.length >= this.limits.maxSocketsPerIdentity) safeClose(open.shift()!.ws, 4008, "replaced");

    const { 0: client, 1: server } = new WebSocketPair();
    // The pubkey tag is how pushes find a recipient's sockets, including after hibernation.
    // A linked device's sockets also carry a device tag, so removing the device can find and close them.
    this.ctx.acceptWebSocket(server, authorized.device === PRIMARY_DEVICE ? [authorized.identity] : [authorized.identity, deviceTag(authorized.identity, authorized.device)]);
    const session: Session = { v: 1, pubkey: authorized.identity, signer: authorized.signer, connectedAt: now, expiresAt: now + this.limits.sessionTtlMs };
    if (authorized.device !== PRIMARY_DEVICE) session.device = authorized.device;
    server.serializeAttachment(session);
    safeSend(server, { type: "ready", v: PROTOCOL_VERSION, instance: this.instance, expiresAt: session.expiresAt, ...this.gapFields(authorized.identity, access) });
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
      if (!row || row.revoked_at > 0) return { ok: false, response: errorResponse(403, "unknown_device") };
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

  /**
   * Resolves a NIP-98 signer to the shared identity and the acting device without enforcing membership.
   * This is the single place the G1 binding is interpreted; `authorize` adds membership and endpoint rules.
   * Returns null for a signer that must not act at all (the shared key of a migrated identity).
   */
  resolveSigner(signer: string, device: string): { identity: string; device: string; admin: boolean; legacy: boolean; revoked: boolean } | null {
    if (this.migrated.has(signer)) return null;
    const adminIdentity = this.adminByKey.get(signer);
    if (adminIdentity !== undefined) {
      // The phone-only admin key acts as the primary device and never as a linked device.
      return { identity: adminIdentity, device: PRIMARY_DEVICE, admin: true, legacy: false, revoked: false };
    }
    // Deterministic even before the UNIQUE index backfill finishes: a key resolves to one row.
    const row = this.q<DeviceRow>("SELECT * FROM devices WHERE auth_pubkey = ? AND auth_pubkey <> '' ORDER BY pubkey, device", signer)[0];
    if (row) {
      return { identity: row.pubkey, device: row.device, admin: false, legacy: false, revoked: row.revoked_at > 0 };
    }
    // An unmigrated identity: the WP04 behaviour. Membership is checked by the caller.
    return { identity: signer, device, admin: false, legacy: true, revoked: false };
  }

  /**
   * WP15: authorization probe for the CallRoom Durable Object. Resolves the NIP-98 signer to the shared
   * identity and acting device, and reports current membership and revocation. It does not record the auth
   * event: each CallRoom keeps its own per-room replay table, so a dead room costs the mailbox nothing.
   */
  async callAuth(signer: string, device: string): Promise<CallAuthOutcome> {
    const who = this.resolveSigner(signer, device);
    if (!who) return { ok: false, code: "auth_invalid" };
    if (who.device !== device) return { ok: false, code: device === PRIMARY_DEVICE ? "not_primary" : "unknown_device" };
    return { ok: true, identity: who.identity, device: who.device, member: this.isMember(who.identity), revoked: who.revoked };
  }

  /**
   * G5: atomic create-if-absent of the single live room of a group. One row per group (keyed by the hash
   * of its id): the revision and room id that currently own the slot. The whole decision is one synchronous
   * SQL sequence in this single object, so concurrent creators cannot both win. A newer revision replaces
   * the row and names the superseded room so the caller can end it; an older one is refused.
   */
  async callSlotClaim(groupKey: string, revision: number, room: string, creator: string): Promise<CallSlotOutcome> {
    const now = Date.now();
    this.sql.exec("DELETE FROM call_slots WHERE claimed_at < ?", now - this.limits.callSlotTtlMs);
    const row = this.sql.exec<{ revision: number; room: string }>("SELECT revision, room FROM call_slots WHERE group_key = ?", groupKey).toArray()[0];
    if (row) {
      if (revision < row.revision) return { ok: false, code: "stale_revision" };
      if (revision === row.revision) return row.room === room ? { ok: true, superseded: null } : { ok: false, code: "conflict", room: row.room };
      // B1: a newer revision never replaces the slot here; the new room first proves its creator is in the OLD
      // room's frozen roster and then calls callSlotSupersede (compare-and-swap on the old room id).
      return { ok: false, code: "newer", room: row.room };
    }
    const mine = this.sql.exec<{ room: string }>("SELECT room FROM call_slots WHERE creator = ? ORDER BY claimed_at ASC LIMIT ?", creator, this.limits.maxCallSlotsPerIdentity + 1).toArray();
    if (mine.length >= this.limits.maxCallSlotsPerIdentity) return { ok: false, code: "creator_full", rooms: mine.map((r) => r.room) };
    const n = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM call_slots").one().n;
    if (n >= this.limits.maxCallSlots) return { ok: false, code: "full" };
    this.sql.exec("INSERT INTO call_slots (group_key, revision, room, creator, claimed_at) VALUES (?, ?, ?, ?, ?)", groupKey, revision, room, creator, now);
    return { ok: true, superseded: null };
  }

  /**
   * G5 signed roster: pins the owner key of a group the first time a verified attestation names it. A later
   * signed revision for the same group must use the same key, so an enrolled member cannot mint their own "owner"
   * attestation and supersede the real group's call. First use is trusted: the server has no group directory, so
   * this is trust-on-first-use within this deployment, not proof against a first squatter.
   */
  async callGroupOwner(groupKey: string, owner: string): Promise<GroupOwnerOutcome> {
    const row = this.sql.exec<{ owner: string }>("SELECT owner FROM call_group_owners WHERE group_key = ?", groupKey).toArray()[0];
    if (row) return row.owner === owner ? { ok: true, pinned: false } : { ok: false };
    const n = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM call_group_owners").one().n;
    if (n >= this.limits.maxCallSlots) {
      // Keep the table bounded: the oldest pin is the least likely to be needed again. A re-pin after eviction is
      // still safe for the current room; it only loses the ability to reject an older squatter's key.
      this.sql.exec("DELETE FROM call_group_owners WHERE group_key IN (SELECT group_key FROM call_group_owners ORDER BY pinned_at ASC LIMIT 1)");
    }
    this.sql.exec("INSERT INTO call_group_owners (group_key, owner, pinned_at) VALUES (?, ?, ?)", groupKey, owner, Date.now());
    return { ok: true, pinned: true };
  }

  /** G5/B1: replaces the slot of [oldRoom] with a higher revision. Compare-and-swap on the old room and a lower revision. */
  async callSlotSupersede(groupKey: string, revision: number, oldRoom: string, room: string, creator: string): Promise<boolean> {
    const cursor = this.sql.exec("UPDATE call_slots SET revision = ?, room = ?, creator = ?, claimed_at = ? WHERE group_key = ? AND room = ? AND revision < ?", revision, room, creator, Date.now(), groupKey, oldRoom, revision);
    cursor.toArray();
    return cursor.rowsWritten > 0;
  }

  /** G5: frees the slot, but only for the room that owns it, so a stale release cannot evict a newer room. */
  async callSlotRelease(groupKey: string, room: string): Promise<void> {
    this.sql.exec("DELETE FROM call_slots WHERE group_key = ? AND room = ?", groupKey, room);
  }

  /** G5/B3: a live room keeps its slot past the TTL; only the owning room refreshes it. */
  async callSlotTouch(groupKey: string, room: string): Promise<void> {
    this.sql.exec("UPDATE call_slots SET claimed_at = ? WHERE group_key = ? AND room = ?", Date.now(), groupKey, room);
  }

  /**
   * G5/B3: replaces a slot whose room the caller proved dead. Compare-and-swap on the dead room id, and only
   * once the slot is older than the grace: a winner that has claimed but not yet inserted its room row looks
   * dead to a concurrent loser for a moment.
   */
  async callSlotTakeover(groupKey: string, revision: number, deadRoom: string, room: string, creator: string): Promise<boolean> {
    const now = Date.now();
    const cursor = this.sql.exec("UPDATE call_slots SET room = ?, creator = ?, claimed_at = ? WHERE group_key = ? AND room = ? AND revision = ? AND claimed_at <= ?", room, creator, now, groupKey, deadRoom, revision, now - CALL_SLOT_TAKEOVER_GRACE_MS);
    cursor.toArray();
    return cursor.rowsWritten > 0;
  }

  /** Shared, eviction-safe budget; resolve the signer rather than trusting a room-supplied identity. */
  async callCreateAllowed(signer: string, device: string): Promise<boolean> {
    const who = await this.callAuth(signer, device);
    if (!who.ok || who.revoked || !who.member) return false;
    const now = Date.now();
    this.sql.exec("DELETE FROM call_create_budget WHERE at_ms < ?", now - 60_000);
    const n = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM call_create_budget WHERE identity = ?", who.identity).one().n;
    if (n >= this.limits.callCreatesPerMinute) return false;
    this.sql.exec("INSERT INTO call_create_budget (identity, at_ms) VALUES (?, ?)", who.identity, now);
    return true;
  }

  /**
   * Authorizes one HTTP request against the G1 contract. The NIP-98 event signature is verified first by the
   * existing path; this method then resolves the signer to an identity/device and enforces the binding:
   *  - before migration, the shared identity key works exactly as in WP04;
   *  - after migration, the shared identity key is refused, the admin key may act only as primary, and each
   *    device key may act only as the device it was enrolled for;
   *  - revoked devices are refused with 401 auth_revoked on every surface.
   * The caller still records the event id for replay protection.
   */
  private async authorize(
    request: Request,
    body: ArrayBuffer | null,
    url: URL,
    opts: { allowIdentityMigration?: boolean } = {},
  ): Promise<Authorized | Response> {
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const device = deviceParam(url);
    if (device === null) return errorResponse(400, "bad_request");
    const signer = auth.pubkey;

    if (opts.allowIdentityMigration) {
      if (this.migrated.has(signer)) return errorResponse(401, "auth_invalid");
      if (this.adminByKey.has(signer)) return errorResponse(401, "auth_invalid");
      if (this.q("SELECT 1 FROM devices WHERE auth_pubkey = ? AND auth_pubkey <> ''", signer).length > 0) return errorResponse(401, "auth_invalid");
      if (!this.isMember(signer)) return errorResponse(403, "not_allowed");
      return { auth, identity: signer, device: PRIMARY_DEVICE, admin: false, legacy: true, signer };
    }

    const who = this.resolveSigner(signer, device);
    if (!who) return errorResponse(401, "auth_invalid");
    if (who.revoked) return errorResponse(401, "auth_revoked");
    if (who.device !== device) {
      return errorResponse(403, device === PRIMARY_DEVICE ? "not_primary" : "unknown_device");
    }
    if (!this.isMember(who.identity)) return errorResponse(403, "not_allowed");
    return { auth, identity: who.identity, device: who.device, admin: who.admin, legacy: who.legacy, signer };
  }

  /** Best-effort per-identity admin rate limit. In memory, so a restart resets it; replay is separately persisted. */
  private checkAdminRate(identity: string): boolean {
    const now = Date.now();
    const windowStart = now - 60_000;
    const recent = (this.adminOps.get(identity) ?? []).filter((t) => t >= windowStart);
    if (recent.length >= MAX_ADMIN_OPS_PER_MINUTE) {
      this.adminOps.set(identity, recent);
      return false;
    }
    recent.push(now);
    this.adminOps.set(identity, recent);
    return true;
  }

  /** `POST /v1/devices/admin`: the shared identity key installs the phone-only admin key and migrates. */
  private async handleAdminMigration(request: Request, url: URL): Promise<Response> {
    if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const device = deviceParam(url);
    if (device === null) return errorResponse(400, "bad_request");
    if (device !== PRIMARY_DEVICE) return errorResponse(403, "not_primary");
    const signer = auth.pubkey;
    if (this.adminByKey.has(signer) || this.q("SELECT 1 FROM devices WHERE auth_pubkey = ? AND auth_pubkey <> ''", signer).length > 0) {
      return errorResponse(401, "auth_invalid");
    }
    if (!this.isMember(signer)) return errorResponse(403, "not_allowed");
    const value = parseJson(body);
    const adminPubkey = value?.admin_pubkey;
    if (value?.v !== PROTOCOL_VERSION || typeof adminPubkey !== "string" || !PUBKEY.test(adminPubkey)) return errorResponse(400, "bad_request");
    const identity = signer;
    if (adminPubkey === identity || this.isMember(adminPubkey)) return errorResponse(400, "bad_request");
    if (!this.checkAdminRate(identity)) return errorResponse(429, "rate_limited");

    let head = 0;
    const response = this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      // Keys are one-purpose: an admin key must not already be a device key (any identity) or another
      // identity's admin key, or attribution would depend on which table is consulted first.
      if (this.adminByKey.has(adminPubkey) || this.q("SELECT 1 FROM devices WHERE auth_pubkey = ? AND auth_pubkey <> ''", adminPubkey).length > 0) {
        return errorResponse(409, "admin_key_in_use");
      }
      if (this.migrated.has(identity)) return errorResponse(401, "auth_invalid");
      const now = Date.now();
      const outcome = this.ctx.storage.transactionSync((): Response | number => {
        if (this.q("SELECT 1 FROM identity_admin WHERE pubkey = ?", identity).length > 0) return errorResponse(409, "already_migrated");
        // Re-checked in the same synchronous section as the writes. Claiming the admin key first turns a
        // UNIQUE violation into a clean 409 instead of a 503, and happens before any cursor is moved.
        if (this.q("SELECT 1 FROM devices WHERE auth_pubkey = ? AND auth_pubkey <> ''", adminPubkey).length > 0) {
          return errorResponse(409, "admin_key_in_use");
        }
        const claimed = this.q<{ pubkey: string }>(
          "INSERT INTO identity_admin (pubkey, admin_pubkey, migrated_at) VALUES (?, ?, ?) ON CONFLICT(admin_pubkey) DO NOTHING RETURNING pubkey",
          identity, adminPubkey, now,
        );
        if (claimed.length === 0) return errorResponse(409, "admin_key_in_use");
        const nextHead = this.seqHead();
        const primary = this.q<DeviceRow>("SELECT * FROM devices WHERE pubkey = ? AND device = 'primary'", identity)[0];
        if (primary) {
          this.q("UPDATE devices SET auth_pubkey = ?, protocol_version = ? WHERE pubkey = ? AND device = 'primary'", adminPubkey, PROTOCOL_VERSION, identity);
        } else {
          const oldest = this.q<{ seq: number | null }>("SELECT MIN(seq) AS seq FROM deliveries WHERE recipient = ?", identity)[0]?.seq;
          const position = oldest === null || oldest === undefined ? nextHead : oldest - 1;
          this.q("INSERT INTO devices (pubkey, device, label, acked, last_seen, added_at, auth_pubkey, protocol_version, revoked_at) VALUES (?, 'primary', '', ?, ?, ?, ?, ?, 0)", identity, position, now, now, adminPubkey, PROTOCOL_VERSION);
          this.q("INSERT INTO cursor_state (pubkey, floor, high) VALUES (?, ?, ?) ON CONFLICT(pubkey) DO NOTHING", identity, position, nextHead);
        }
        return nextHead;
      });
      if (outcome instanceof Response) return outcome;
      head = outcome;
      return json({ v: PROTOCOL_VERSION, migrated: true, head });
    });
    if (response.status === 200) {
      this.migrated.add(identity);
      this.adminByKey.set(adminPubkey, identity);
      // Every socket for this identity was authenticated by the shared identity key (device keys can
      // only exist after migration). Close them all: the phone reconnects with the admin key. The frame
      // gate re-checks the signer too, so a socket this sweep misses is still refused.
      for (const ws of this.ctx.getWebSockets(identity)) safeClose(ws, 4001, "auth_invalid");
    }
    return response;
  }

  /** `POST /v1/devices` after migration: the admin key enrolls a linked device's own authentication key. */
  private enrollDevice(pubkey: string, device: string, label: string, authPubkey: string, now: number): Response {
    // A key is bound to exactly one (identity, device) pair, and admin keys and device keys are disjoint,
    // so resolving a key never has to choose between two meanings.
    if (this.adminByKey.has(authPubkey)) return errorResponse(409, "admin_key_in_use");
    const result = this.ctx.storage.transactionSync((): Response | { head: number; existing: boolean; rotated: boolean } => {
      const head = this.seqHead();
      const owner = this.q<DeviceRow>("SELECT * FROM devices WHERE auth_pubkey = ? AND auth_pubkey <> ''", authPubkey)[0];
      if (owner && !(owner.pubkey === pubkey && owner.device === device)) return errorResponse(409, "auth_key_in_use");
      const existing = this.q<DeviceRow>("SELECT * FROM devices WHERE pubkey = ? AND device = ?", pubkey, device)[0];
      if (existing && existing.revoked_at === 0) {
        // Idempotent: a retry or rename never moves the cursor. Rotating the key is allowed, but the old
        // socket is closed below: the key it authenticated with is gone. Rotation is not revocation;
        // DELETE is.
        const rotated = existing.auth_pubkey !== "" && existing.auth_pubkey !== authPubkey;
        if (existing.label !== label || existing.auth_pubkey !== authPubkey) {
          this.q("UPDATE devices SET label = ?, auth_pubkey = ?, protocol_version = ? WHERE pubkey = ? AND device = ?", label, authPubkey, PROTOCOL_VERSION, pubkey, device);
        }
        return { head: existing.acked, existing: true, rotated };
      }
      const count = this.q<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE pubkey = ? AND device <> 'primary' AND revoked_at = 0", pubkey)[0].n;
      if (count >= this.limits.maxLinkedDevices) return errorResponse(409, "device_limit");
      if (existing) {
        // Re-enrolling a revoked id revives it at the head: it missed everything while revoked.
        this.q("UPDATE devices SET label = ?, auth_pubkey = ?, protocol_version = ?, acked = ?, last_seen = ?, added_at = ?, revoked_at = 0, gap_through = 0, gap_shown = 0 WHERE pubkey = ? AND device = ?", label, authPubkey, PROTOCOL_VERSION, head, now, now, pubkey, device);
        return { head, existing: false, rotated: false };
      }
      this.q("INSERT INTO devices (pubkey, device, label, acked, last_seen, added_at, auth_pubkey, protocol_version, revoked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)", pubkey, device, label, head, now, now, authPubkey, PROTOCOL_VERSION);
      return { head, existing: false, rotated: false };
    });
    if (result instanceof Response) return result;
    this.linked.add(pubkey);
    if (result.rotated) {
      // The old key stops resolving immediately. Closing the socket makes the change visible to a client
      // that would otherwise keep its authenticated session until the TTL.
      for (const ws of this.ctx.getWebSockets(deviceTag(pubkey, device))) safeClose(ws, 4001, "auth_invalid");
    }
    return json({ v: PROTOCOL_VERSION, head: result.head, ...(result.existing ? { existing: true } : {}) });
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
      "SELECT MIN(acked) AS m FROM devices WHERE pubkey = ? AND revoked_at = 0 AND (device = 'primary' OR last_seen >= ?)",
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

  /** `POST/GET /v1/devices`, `DELETE /v1/devices/<id>`: primary or admin key only. */
  private async handleDevices(request: Request, url: URL): Promise<Response> {
    if (url.pathname === "/v1/devices/admin") return this.handleAdminMigration(request, url);
    const target = url.pathname === "/v1/devices" ? null : url.pathname.slice("/v1/devices/".length);
    const allowed = target === null
      ? request.method === "POST" || request.method === "GET"
      : request.method === "DELETE";
    if (!allowed) return errorResponse(405, "method_not_allowed");
    const device = deviceParam(url);
    if (device === null) return errorResponse(400, "bad_request");
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const authorized = await this.authorize(request, body, url);
    if (authorized instanceof Response) return authorized;
    if (authorized.device !== PRIMARY_DEVICE) {
      const known = this.linked.has(authorized.identity) &&
        this.q("SELECT 1 FROM devices WHERE pubkey = ? AND device = ?", authorized.identity, authorized.device).length > 0;
      return errorResponse(403, known ? "not_primary" : "unknown_device");
    }
    if (authorized.admin && !this.checkAdminRate(authorized.identity)) return errorResponse(429, "rate_limited");
    if (!authorized.admin && !authorized.legacy) return errorResponse(403, "not_primary");

    let registration: { device: string; label: string; auth_pubkey: string } | null = null;
    if (request.method === "POST") {
      const value = parseJson(body);
      const id = value?.device;
      const label = value?.label ?? "";
      const authPubkey = typeof value?.auth_pubkey === "string" ? value.auth_pubkey : "";
      const needsKey = this.migrated.has(authorized.identity);
      if (value?.v !== PROTOCOL_VERSION || typeof id !== "string" || !LINKED_DEVICE_ID.test(id) ||
          typeof label !== "string" || [...label].length > MAX_DEVICE_LABEL_CHARS ||
          (needsKey && !PUBKEY.test(authPubkey))) {
        return errorResponse(400, "bad_request");
      }
      if (needsKey && (authPubkey === authorized.identity || this.isMember(authPubkey))) return errorResponse(400, "bad_request");
      registration = { device: id, label, auth_pubkey: needsKey ? authPubkey : "" };
    }
    if (target !== null && target !== PRIMARY_DEVICE && !LINKED_DEVICE_ID.test(target)) return errorResponse(400, "bad_request");

    let unlinked = null as string | null;
    const response = this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      if (registration) {
        if (authorized.admin) return this.enrollDevice(authorized.identity, registration.device, registration.label, registration.auth_pubkey, now);
        return this.registerDevice(authorized.identity, registration.device, registration.label, now);
      }
      if (target === null) return this.listDevices(authorized.identity, now);
      if (target === PRIMARY_DEVICE) return errorResponse(400, "bad_request");
      const removed = this.removeDevice(authorized.identity, target, now);
      if (removed) unlinked = target;
      return removed ? json({ v: PROTOCOL_VERSION, removed: true }) : errorResponse(404, "unknown_device");
    });
    if (unlinked !== null) {
      for (const ws of this.ctx.getWebSockets(deviceTag(authorized.identity, unlinked))) safeClose(ws, 4009, "unlinked");
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
      if (existing && existing.revoked_at === 0) {
        // Idempotent: a retry or rename never moves the cursor (that would silently skip messages).
        if (existing.label !== label) this.q("UPDATE devices SET label = ? WHERE pubkey = ? AND device = ?", label, pubkey, device);
        return { head: existing.acked, existing: true };
      }
      const count = this.q<{ n: number }>("SELECT COUNT(*) AS n FROM devices WHERE pubkey = ? AND device <> 'primary' AND revoked_at = 0", pubkey)[0].n;
      if (count >= this.limits.maxLinkedDevices) return errorResponse(409, "device_limit");
      if (existing) {
        // Re-registering a revoked id revives it at the head: it missed everything while revoked.
        this.q("UPDATE devices SET label = ?, acked = ?, last_seen = ?, added_at = ?, revoked_at = 0, gap_through = 0, gap_shown = 0 WHERE pubkey = ? AND device = ?", label, head, now, now, pubkey, device);
        return { head, existing: false };
      }
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
    const rows = this.q<DeviceRow>("SELECT * FROM devices WHERE pubkey = ? AND revoked_at = 0 ORDER BY added_at, device", pubkey);
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
      const gone = this.q("UPDATE devices SET revoked_at = ?, last_seen = ? WHERE pubkey = ? AND device = ? AND revoked_at = 0 RETURNING device", now, now, pubkey, device);
      if (gone.length === 0) return false;
      this.deleteBelowMin(pubkey, now, this.seqHead());
      return true;
    });
  }

  // ---- Link slot (WP05 part B) ----------------------------------------------------------------
  //
  // The phone seals identity keys, contacts, groups and settings (no history, LP-D06) and parks the
  // bytes under sha256(one-time code). The server stores only that hash, so it never learns the code.
  // The desktop proves the code with a header, GETs the bundle and then explicitly completes the read.
  // A GET does not consume: a lost response is retried, and only DELETE removes the bundle.

  private async handleLink(request: Request, url: URL): Promise<Response> {
    const slot = url.pathname.slice("/v1/link/".length);
    if (!LINK_SLOT.test(slot)) return errorResponse(400, "bad_request");
    // The one-time code must never travel in the URL; a query string here is a client bug, not a variant.
    if (url.search !== "") return errorResponse(400, "bad_request");
    if (request.method === "PUT") return this.putLink(request, url, slot);
    if (request.method !== "GET" && request.method !== "DELETE") return errorResponse(405, "method_not_allowed");
    return this.readLink(request, slot, request.method === "DELETE");
  }

  /** `PUT /v1/link/<slot>`: one live slot per identity, 10-minute TTL, refused while one is live. */
  private async putLink(request: Request, url: URL, slot: string): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength === 0) return errorResponse(400, "bad_request");
    if (body.byteLength > this.limits.maxLinkBytes) return errorResponse(413, "envelope_too_large");
    const authorized = await this.authorize(request, body, url);
    if (authorized instanceof Response) return authorized;
    // Primary only: the admin key (post-migration) or the shared identity key (before migration).
    // A linked device's own key cannot publish a bundle for the identity.
    if (authorized.device !== PRIMARY_DEVICE || (!authorized.admin && !authorized.legacy)) {
      return errorResponse(403, "not_primary");
    }
    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      return this.ctx.storage.transactionSync((): Response => {
        // Expired slots are dropped before the one-live-slot check, so an old link never blocks a new one.
        this.q("DELETE FROM link_slots WHERE pubkey = ? AND expires_at <= ?", authorized.identity, now);
        if (this.q("SELECT 1 FROM link_slots WHERE pubkey = ?", authorized.identity).length > 0 ||
            this.q("SELECT 1 FROM link_slots WHERE slot = ?", slot).length > 0) {
          return errorResponse(409, "link_busy");
        }
        const expiresAt = now + this.limits.linkTtlMs;
        this.q(
          "INSERT INTO link_slots (slot, pubkey, bundle, size, created_at, expires_at, lease_until) VALUES (?, ?, ?, ?, ?, ?, 0)",
          slot, authorized.identity, body, body.byteLength, now, expiresAt,
        );
        return json({ v: PROTOCOL_VERSION, slot, size: body.byteLength, expiresAt });
      });
    });
  }

  /**
   * `GET`/`DELETE /v1/link/<slot>` with `x-relay-link-proof: <code hex>`. A wrong code and an absent
   * slot both answer 404, so a guess cannot tell whether a slot exists. GET refreshes a 60 s lease and
   * keeps the bundle; DELETE is the explicit completion and frees it.
   */
  private async readLink(request: Request, slot: string, consume: boolean): Promise<Response> {
    const proof = request.headers.get(LINK_PROOF_HEADER);
    if (proof === null) return errorResponse(401, "auth_missing");
    if (!LINK_SLOT.test(proof)) return errorResponse(401, "auth_invalid");
    if (!this.linkAttemptAllowed(slot, request)) return errorResponse(429, "rate_limited");
    const digest = await sha256Hex(proof);
    return this.metered(() => {
      const now = Date.now();
      const row = this.q<LinkSlotRow>("SELECT * FROM link_slots WHERE slot = ?", slot)[0];
      if (!row) return errorResponse(404, "not_found");
      if (row.expires_at <= now) {
        this.q("DELETE FROM link_slots WHERE slot = ? AND pubkey = ?", slot, row.pubkey);
        return errorResponse(404, "not_found");
      }
      // The slot IS the hash of the code, so a matching digest proves the code without storing it.
      if (!sameHex(digest, row.slot)) return errorResponse(404, "not_found");
      if (consume) {
        this.q("DELETE FROM link_slots WHERE slot = ? AND pubkey = ?", slot, row.pubkey);
        return json({ v: PROTOCOL_VERSION, consumed: true });
      }
      const leaseUntil = now + this.limits.linkLeaseMs;
      this.q("UPDATE link_slots SET lease_until = ? WHERE slot = ? AND pubkey = ?", leaseUntil, slot, row.pubkey);
      return new Response(row.bundle, {
        status: 200,
        headers: {
          "content-type": "application/octet-stream",
          "cache-control": "no-store",
          "content-length": String(row.size),
          "x-relay-link-expires": String(row.expires_at),
          "x-relay-link-lease": String(leaseUntil),
        },
      });
    });
  }

  /** True while this slot and this client bucket may still guess. In-memory, best effort (G1). */
  private linkAttemptAllowed(slot: string, request: Request): boolean {
    const now = Date.now();
    const bucket = request.headers.get("CF-Connecting-IP") ?? "unknown";
    const windowStart = now - 60_000;
    for (const key of [`s:${slot}`, `i:${bucket}`]) {
      const recent = (this.linkAttempts.get(key) ?? []).filter((t) => t >= windowStart);
      if (recent.length >= this.limits.linkAttemptsPerMinute) {
        this.linkAttempts.set(key, recent);
        return false;
      }
      recent.push(now);
      this.linkAttempts.set(key, recent);
    }
    if (this.linkAttempts.size > 1024) {
      for (const [key, times] of this.linkAttempts) {
        if (times.every((t) => t < windowStart)) this.linkAttempts.delete(key);
      }
    }
    return true;
  }

  // ---- Sync channel (WP05 part B, G3) ---------------------------------------------------------
  //
  // A bounded, versioned envelope for SENT copies and KEYS rotations: unique id, revision, part
  // index/count, total decoded bytes, SHA-256 of the assembled body and an expiry. Parts assemble
  // only when every index is present and the hash matches; nothing partial is ever served. The
  // decoded body is capped at 12 KiB so its base64 form fits the 16 KiB envelope bound.

  private async handleSync(request: Request, url: URL): Promise<Response> {
    if (url.pathname === "/v1/sync/ack") {
      if (request.method !== "POST") return errorResponse(405, "method_not_allowed");
      return this.ackSync(request, url);
    }
    if (url.pathname === "/v1/sync") {
      if (request.method === "POST") return this.uploadSync(request, url);
      if (request.method === "GET") return this.listSync(request, url);
      return errorResponse(405, "method_not_allowed");
    }
    if (request.method !== "GET") return errorResponse(405, "method_not_allowed");
    const id = url.pathname.slice("/v1/sync/".length);
    if (!SYNC_ID.test(id)) return errorResponse(400, "bad_request");
    return this.readSync(request, url, id);
  }

  /** `POST /v1/sync`: one part. Duplicate parts are idempotent; a different revision or bytes conflict. */
  private async uploadSync(request: Request, url: URL): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_SYNC_REQUEST_BYTES) return errorResponse(413, "request_too_large");
    const authorized = await this.authorize(request, body, url);
    if (authorized instanceof Response) return authorized;
    const parsed = parseSyncPart(body, this.limits);
    if ("code" in parsed) return errorResponse(parsed.status, parsed.code);
    const media = parseMediaRefs(parseJson(body)?.media, this.limits);
    if (media === null) return errorResponse(400, "bad_request");
    if (media === "too_many") return errorResponse(400, "too_many_media");
    return this.measured(() => this.storeSyncPart(authorized, parsed, Date.now(), media));
  }

  private async storeSyncPart(authorized: Authorized, parsed: SyncPart, now: number, media: string[] = []): Promise<Response> {
    if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
    const identity = authorized.identity;
    const done = (row: SyncEventRow) => json({
      v: PROTOCOL_VERSION, id: row.id, rev: row.rev, complete: true, duplicate: true, cursor: row.seq,
    });

    const grantIds = new Map<string, number>();
    const finish = async (response: Response): Promise<Response> => {
      if (media.length === 0 || response.status !== 200) return response;
      const outcome = await this.drainGrants();
      const payload = await response.json() as Record<string, unknown>;
      payload.media = media.map((hash) => ({ hash, status: grantIds.has(hash)
        ? (outcome.statuses.get(grantIds.get(hash)!) ?? "pending") : "unchanged" }));
      const result = json(payload);
      if (this.exposeMetrics) result.headers.set("x-relay-media-rows", `read=${outcome.blobRows.read};written=${outcome.blobRows.written}`);
      return result;
    };
    const existing = this.q<SyncEventRow>("SELECT * FROM sync_events WHERE pubkey = ? AND id = ?", identity, parsed.id)[0];
    if (existing) return existing.rev === parsed.rev ? finish(done(existing)) : errorResponse(409, "sync_conflict");

    const outcome = this.ctx.storage.transactionSync((): Response | { complete: false } | { complete: true; bytes: Uint8Array } => {
      this.q("DELETE FROM sync_parts WHERE pubkey = ? AND expires_at <= ?", identity, now);
      const owner = this.q<SyncPartRow>("SELECT * FROM sync_parts WHERE pubkey = ? AND id = ? LIMIT 1", identity, parsed.id)[0];
      if (owner && (owner.rev !== parsed.rev || owner.parts !== parsed.parts || owner.total !== parsed.total || owner.hash !== parsed.hash)) {
        return errorResponse(409, "sync_conflict");
      }
      if (!owner) {
        const open = this.q<{ n: number }>("SELECT COUNT(DISTINCT id) AS n FROM sync_parts WHERE pubkey = ?", identity)[0].n;
        if (open >= this.limits.maxSyncAssemblies) return errorResponse(409, "sync_limit", true);
      }
      const prior = this.q<{ data: ArrayBuffer }>(
        "SELECT data FROM sync_parts WHERE pubkey = ? AND id = ? AND part = ?", identity, parsed.id, parsed.part,
      )[0];
      if (prior && !sameBytes(new Uint8Array(prior.data), parsed.data)) {
        return errorResponse(409, "sync_conflict");
      }
      if (!prior) this.q(
        "INSERT INTO sync_parts (pubkey, id, rev, part, parts, total, hash, expires_at, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        identity, parsed.id, parsed.rev, parsed.part, parsed.parts, parsed.total, parsed.hash, parsed.expiresAt,
        parsed.data.slice().buffer,
      );
      const held = this.q<{ part: number; data: ArrayBuffer }>(
        "SELECT part, data FROM sync_parts WHERE pubkey = ? AND id = ? ORDER BY part", identity, parsed.id,
      );
      if (held.length < parsed.parts) return { complete: false };
      let length = 0;
      for (const row of held) length += row.data.byteLength;
      // A declared total that the parts cannot fill is malformed: drop the assembly, do not keep it.
      if (length !== parsed.total) {
        this.q("DELETE FROM sync_parts WHERE pubkey = ? AND id = ?", identity, parsed.id);
        return errorResponse(400, "bad_request") as unknown as { complete: false };
      }
      const bytes = new Uint8Array(length);
      let offset = 0;
      for (const row of held) {
        bytes.set(new Uint8Array(row.data), offset);
        offset += row.data.byteLength;
      }
      return { complete: true, bytes };
    });
    if (outcome instanceof Response) return outcome;
    if (!outcome.complete) {
      const prior = this.q<{ data: ArrayBuffer }>(
        "SELECT data FROM sync_parts WHERE pubkey = ? AND id = ? AND part = ?", identity, parsed.id, parsed.part,
      )[0];
      const duplicate = !!prior && sameBytes(new Uint8Array(prior.data), parsed.data);
      return json({
        v: PROTOCOL_VERSION, id: parsed.id, rev: parsed.rev, part: parsed.part, parts: parsed.parts,
        complete: false, ...(duplicate ? { duplicate: true } : {}),
      });
    }

    // Hash and size are checked outside the insert transaction: the only await is the digest itself.
    const digest = await sha256Hex(outcome.bytes);
    if (digest !== parsed.hash) {
      this.ctx.storage.transactionSync(() => this.q("DELETE FROM sync_parts WHERE pubkey = ? AND id = ?", identity, parsed.id));
      return errorResponse(400, "bad_request");
    }
    if (media.length > 0) {
      // Only a complete, verified body can request grants. No RPC/await in the publication transaction.
      const usable = await Promise.all(media.map((h) => this.blob(h).attachCheck(identity, true)));
      if (!usable.every(Boolean)) return errorResponse(409, "media_unavailable");
      this.ensureGrantTable();
      // Arm BEFORE durable publication: a crash after committing must still retry the outbox.
      await this.ctx.storage.setAlarm(Date.now() + MEDIA_GRANT_RETRY_MS);
    }
    const response = this.ctx.storage.transactionSync((): Response => {
      if (media.length > 0) {
        if (parsed.expiresAt <= Date.now()) return errorResponse(400, "bad_request");
        const who = this.resolveSigner(authorized.auth.pubkey, authorized.device);
        if (!who || who.revoked || who.identity !== identity || who.device !== authorized.device ||
            !this.sendBindingActive(authorized)) return errorResponse(401, "auth_revoked");
      }
      const raced = this.q<SyncEventRow>("SELECT * FROM sync_events WHERE pubkey = ? AND id = ?", identity, parsed.id)[0];
      if (raced) return raced.rev === parsed.rev ? done(raced) : errorResponse(409, "sync_conflict");
      const usage = this.q<{ n: number; bytes: number }>(
        "SELECT COUNT(*) AS n, COALESCE(SUM(total), 0) AS bytes FROM sync_events WHERE pubkey = ?", identity,
      )[0];
      if (usage.n >= this.limits.maxSyncEvents || usage.bytes + parsed.total > this.limits.maxSyncEventBytes) {
        return errorResponse(409, "sync_limit", true);
      }
      const seq = this.q<{ seq: number }>(
        "INSERT INTO sync_events (pubkey, id, rev, total, hash, body, created_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING seq",
        identity, parsed.id, parsed.rev, parsed.total, parsed.hash, outcome.bytes.slice().buffer, now, parsed.expiresAt,
      )[0].seq;
      if (media.length > 0) {
        const pairs = JSON.stringify(this.activeDevices(identity, Date.now()).map((d) => [identity, d]));
        for (const hash of media) {
          grantIds.set(hash, this.q<{ id: number }>(
            "INSERT INTO media_grants (digest, uploader, pairs, created_at) VALUES (?, ?, ?, ?) RETURNING id",
            hash, identity, pairs, Date.now())[0].id);
        }
      }
      this.q("DELETE FROM sync_parts WHERE pubkey = ? AND id = ?", identity, parsed.id);
      return json({
        v: PROTOCOL_VERSION, id: parsed.id, rev: parsed.rev, parts: parsed.parts, complete: true, cursor: seq,
      });
    });
    return finish(response);
  }

  /** `GET /v1/sync?after=&limit=`: what this device missed, in cursor order, bounded by count and bytes. */
  private async listSync(request: Request, url: URL): Promise<Response> {
    const query = parseSyncQuery(url, this.limits);
    if (!query) return errorResponse(400, "bad_request");
    const authorized = await this.authorize(request, null, url);
    if (authorized instanceof Response) return authorized;
    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      this.q("DELETE FROM sync_events WHERE pubkey = ? AND expires_at <= ?", authorized.identity, now);
      const stored = this.q<{ cursor: number }>(
        "SELECT cursor FROM sync_cursors WHERE pubkey = ? AND device = ?", authorized.identity, authorized.device,
      )[0]?.cursor ?? 0;
      const after = query.after ?? stored;
      const rows = this.q<SyncEventRow>(
        "SELECT * FROM sync_events WHERE pubkey = ? AND seq > ? ORDER BY seq LIMIT ?",
        authorized.identity, after, query.limit + 1,
      );
      const events: Array<Record<string, unknown>> = [];
      let bytes = 0;
      let more = rows.length > query.limit;
      for (const row of rows.slice(0, query.limit)) {
        if (events.length > 0 && bytes + row.total > this.limits.maxSyncPageBytes) {
          more = true;
          break;
        }
        if (bytes + row.total > this.limits.maxSyncPageBytes) break;
        bytes += row.total;
        events.push({ cursor: row.seq, id: row.id, rev: row.rev, total: row.total, hash: row.hash, data: base64(new Uint8Array(row.body)) });
      }
      return json({
        v: PROTOCOL_VERSION, events, more, cursor: events.length > 0 ? events.at(-1)!.cursor : after,
      });
    });
  }

  private async readSync(request: Request, url: URL, id: string): Promise<Response> {
    if ([...url.searchParams.keys()].some((k) => k !== "device")) return errorResponse(400, "bad_request");
    const authorized = await this.authorize(request, null, url);
    if (authorized instanceof Response) return authorized;
    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      const row = this.q<SyncEventRow>("SELECT * FROM sync_events WHERE pubkey = ? AND id = ?", authorized.identity, id)[0];
      if (!row) return errorResponse(404, "not_found");
      if (row.expires_at <= now) {
        this.q("DELETE FROM sync_events WHERE pubkey = ? AND id = ?", authorized.identity, id);
        return errorResponse(404, "not_found");
      }
      return json({
        v: PROTOCOL_VERSION, id: row.id, rev: row.rev, total: row.total, hash: row.hash,
        data: base64(new Uint8Array(row.body)), cursor: row.seq, expiresAt: row.expires_at,
      });
    });
  }

  /** `POST /v1/sync/ack`: advances this device's cursor. It never moves back and never past the head. */
  private async ackSync(request: Request, url: URL): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength > MAX_ACK_BODY_BYTES) return errorResponse(413, "request_too_large");
    const authorized = await this.authorize(request, body, url);
    if (authorized instanceof Response) return authorized;
    const through = parseAck(body);
    if (through === null) return errorResponse(400, "bad_request");
    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      return this.ctx.storage.transactionSync((): Response => {
        const head = this.q<{ seq: number }>("SELECT seq FROM sqlite_sequence WHERE name = 'sync_events'")[0]?.seq ?? 0;
        const stored = this.q<{ cursor: number }>(
          "SELECT cursor FROM sync_cursors WHERE pubkey = ? AND device = ?", authorized.identity, authorized.device,
        )[0]?.cursor ?? 0;
        const cursor = Math.max(stored, Math.min(through, head));
        this.q(
          "INSERT INTO sync_cursors (pubkey, device, cursor) VALUES (?, ?, ?) ON CONFLICT(pubkey, device) DO UPDATE SET cursor = excluded.cursor WHERE excluded.cursor > sync_cursors.cursor",
          authorized.identity, authorized.device, cursor,
        );
        return json({ v: PROTOCOL_VERSION, cursor });
      });
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
        "SELECT device FROM devices WHERE pubkey = ? AND revoked_at = 0 AND (device = 'primary' OR last_seen >= ?)",
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
      if (!row || row.revoked_at > 0) return { exists: false, active: false, dormantAt: 0 };
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
    const authorized = await this.authorize(request, body, new URL(request.url));
    if (authorized instanceof Response) return authorized;
    const value = parseJson(body);
    const code = value?.code;
    const expiresAt = value?.expiresAt;
    const now = Date.now();
    if (value?.v !== PROTOCOL_VERSION || typeof code !== "string" || !PUBKEY.test(code) || !Number.isSafeInteger(expiresAt)) {
      return errorResponse(400, "bad_request");
    }
    const until = Math.min(expiresAt as number, now + this.limits.inviteMaxTtlMs);
    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      this.q("DELETE FROM invites WHERE expires_at < ?", now);
      this.q("INSERT INTO invites (code_hash, inviter, expires_at) VALUES (?, ?, ?) ON CONFLICT(code_hash) DO NOTHING", code, authorized.identity, until);
      this.q(
        "DELETE FROM invites WHERE inviter = ? AND code_hash NOT IN (SELECT code_hash FROM invites WHERE inviter = ? ORDER BY expires_at DESC LIMIT ?)",
        authorized.identity, authorized.identity, this.limits.maxInvitesPerMember,
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
    const device = deviceParam(new URL(request.url));
    if (device === null) return errorResponse(400, "bad_request");
    const who = this.resolveSigner(auth.pubkey, device);
    if (!who || who.revoked || who.device !== device) return errorResponse(401, "auth_invalid");
    const requester = who.identity;
    const value = parseJson(body);
    const peer = value?.peer;
    const code = value?.code;
    if (value?.v !== PROTOCOL_VERSION || typeof peer !== "string" || !PUBKEY.test(peer) || peer === requester ||
        typeof code !== "string" || code.length === 0 || code.length > 128) {
      return errorResponse(400, "bad_request");
    }
    const codeHash = await sha256Hex(code);
    return this.metered(() => {
      if (!this.recordAuth(auth)) return errorResponse(401, "auth_replayed");
      const now = Date.now();
      if (this.isMember(requester)) {
        if (!this.isMember(peer)) this.admit(peer, requester, now);
      } else {
        const invite = this.q<{ inviter: string }>(
          "DELETE FROM invites WHERE code_hash = ? AND inviter = ? AND expires_at >= ? RETURNING inviter",
          codeHash, peer, now,
        )[0];
        if (invite && this.isMember(invite.inviter)) this.admit(requester, invite.inviter, now);
      }
      return json({ v: PROTOCOL_VERSION, you: this.isMember(requester), peer: this.isMember(peer) });
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
    // The attachment records the key that signed the upgrade. Re-check it against live state on every
    // frame and every push, so a missed close sweep cannot leave a migrated or revoked key alive.
    const failure = this.bindingFailure(s);
    if (failure) {
      safeClose(ws, failure.code, failure.reason);
      return null;
    }
    return s;
  }

  /**
   * Why a socket's signer may no longer act, or null if it may. Sessions written before `signer`
   * existed are treated as their identity key, which is what that upgrade used.
   */
  private bindingFailure(s: Session): { code: number; reason: string } | null {
    const signer = s.signer ?? s.pubkey;
    if (signer === s.pubkey && this.migrated.has(signer)) return { code: 4001, reason: "auth_invalid" };
    const adminIdentity = this.adminByKey.get(signer);
    if (adminIdentity !== undefined) {
      return adminIdentity === s.pubkey ? null : { code: 4001, reason: "auth_invalid" };
    }
    const row = this.q<DeviceRow>(
      "SELECT * FROM devices WHERE auth_pubkey = ? AND auth_pubkey <> '' ORDER BY pubkey, device",
      signer,
    )[0];
    if (row) {
      if (row.pubkey !== s.pubkey || row.device !== (s.device ?? PRIMARY_DEVICE)) return { code: 4001, reason: "auth_invalid" };
      if (row.revoked_at > 0) return { code: 4009, reason: "unlinked" };
      return null;
    }
    // No row: only the identity's own shared key before migration may act this way. A rotated device key
    // has no row either, so it is refused here rather than resurfacing as a legacy identity.
    return signer === s.pubkey && !this.migrated.has(s.pubkey) ? null : { code: 4001, reason: "auth_invalid" };
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
    const authorized = await this.authorize(request, body, new URL(request.url));
    if (authorized instanceof Response) return authorized;
    const parsed = parseJson(body);
    const token = parsed?.token;
    if (parsed?.v !== PROTOCOL_VERSION || typeof token !== "string" || token.length > 4096 || /\s/.test(token)) return errorResponse(400, "bad_request");
    return this.metered(() => {
      if (!this.recordAuth(authorized.auth)) return errorResponse(401, "auth_replayed");
      if (token === "") this.q("DELETE FROM push_tokens WHERE pubkey = ?", authorized.identity);
      else this.q(
        "INSERT INTO push_tokens (pubkey, token, updated_at) VALUES (?, ?, ?) ON CONFLICT(pubkey) DO UPDATE SET last_wake_at = CASE WHEN push_tokens.token = excluded.token THEN push_tokens.last_wake_at ELSE 0 END, token = excluded.token, updated_at = excluded.updated_at",
        authorized.identity, token, Date.now(),
      );
      return json({ v: PROTOCOL_VERSION, push: token !== "" && this.push !== null });
    });
  }

  /**
   * Wakes a recipient who has no open socket, at most once per [WAKE_INTERVAL_MS]. An [urgent] wake (a call
   * invite, already admitted by [urgentAllowed]) skips that coalescing and is sent as its own high-priority,
   * non-collapsible, short-lived message; it still stamps `last_wake_at`, so ordinary messages right after a
   * ring are folded into it.
   */
  private async wake(recipient: string, now: number, urgent = false): Promise<void> {
    if (!this.push) return;
    const registration = this.q<{ token: string; last_wake_at: number }>("SELECT token, last_wake_at FROM push_tokens WHERE pubkey = ?", recipient)[0];
    if (!registration || (!urgent && now - registration.last_wake_at < WAKE_INTERVAL_MS)) return;
    this.q("UPDATE push_tokens SET last_wake_at = ? WHERE pubkey = ?", now, recipient);
    const result = await this.push.wake(registration.token, urgent ? { urgent: true } : {});
    if (result === "unregistered") this.q("DELETE FROM push_tokens WHERE pubkey = ? AND token = ?", recipient, registration.token);
    else if (result === "failed") console.error("wake-up push failed");
  }

  // ---- Calls (WP11) ----------------------------------------------------------------------------

  /**
   * Whether an urgent wake from [sender] to [recipient] fits the budgets: one per recipient per interval, a
   * per-sender and a server-wide count per minute. The sealed envelope is opaque, so the server cannot check
   * that it is really a call invite; these budgets are what stop a member from using the flag as a pager.
   * Pure check: [noteUrgent] records the spend once the message is stored.
   */
  private urgentAllowed(sender: string, recipient: string, now: number): boolean {
    const last = this.urgentRecipientAt.get(recipient);
    if (last !== undefined && now - last < this.limits.urgentRecipientIntervalMs) return false;
    const windowStart = now - 60_000;
    if ((this.urgentSenderOps.get(sender) ?? []).filter((t) => t >= windowStart).length >= this.limits.urgentPerSenderPerMinute) return false;
    return this.urgentGlobalOps.filter((t) => t >= windowStart).length < this.limits.urgentGlobalPerMinute;
  }

  private noteUrgent(sender: string, recipient: string, now: number): void {
    const windowStart = now - 60_000;
    this.urgentRecipientAt.set(recipient, now);
    this.urgentSenderOps.set(sender, [...(this.urgentSenderOps.get(sender) ?? []).filter((t) => t >= windowStart), now]);
    this.urgentGlobalOps = [...this.urgentGlobalOps.filter((t) => t >= windowStart), now];
    // Keys are members only (sender and stored recipient), so these maps are bounded by the member count.
  }

  /** `GET /v1/call/ice`: short-lived TURN credentials for a member or an enrolled, unrevoked device. */
  private async handleCallIce(request: Request, url: URL): Promise<Response> {
    for (const key of url.searchParams.keys()) if (key !== "device") return errorResponse(400, "bad_request");
    const authorized = await this.authorize(request, null, url);
    if (authorized instanceof Response) return authorized;
    const start = { ...this.rows };
    const done = (response: Response): Response => this.withMetrics(response, start);

    // Limit before touching storage, so a flood costs no rows. A refused request is not recorded for replay: it
    // is refused again on its own merit (rate), and the client must sign a fresh one anyway.
    if (!this.iceAllowed(authorized.identity, Date.now())) return done(errorResponse(429, "rate_limited", true));
    const gate = this.metered(() => (this.recordAuth(authorized.auth) ? json(null) : errorResponse(401, "auth_replayed")));
    if (!gate.ok) return done(gate);

    const config = this.turnConfig();
    if (!config) return done(errorResponse(503, "not_configured"));

    const now = Date.now();
    let entry = this.iceCache.get(authorized.identity);
    if (!entry || now - entry.mintedAt >= this.limits.callIceCacheMs) {
      const mint = mintIceServers(config, (r) => this.turnFetch(r), now, {
        ttlS: this.limits.callIceTtlS,
        timeoutMs: this.limits.callIceTimeoutMs,
        maxResponseBytes: this.limits.maxCallIceResponseBytes,
      }).then((outcome) => (outcome.ok ? { iceServers: outcome.iceServers, expiresAt: outcome.expiresAt } : null));
      entry = { mintedAt: now, result: mint };
      this.iceCache.set(authorized.identity, entry);
      // A failure is never cached: drop the entry as soon as it settles empty.
      const mine = entry;
      void mint.then((value) => {
        if (value === null && this.iceCache.get(authorized.identity) === mine) this.iceCache.delete(authorized.identity);
      });
    }
    const minted = await entry.result;
    if (!minted) return done(errorResponse(502, "upstream_failed", true));
    // The provider call took time: a device revoked meanwhile gets nothing.
    if (!this.sendBindingActive(authorized)) return done(errorResponse(401, "auth_revoked"));
    return done(json({ v: PROTOCOL_VERSION, iceServers: minted.iceServers, expiresAt: minted.expiresAt }));
  }

  /** Best-effort per-identity limit on credential minting. In memory; replay is persisted separately. */
  private iceAllowed(identity: string, now: number): boolean {
    const recent = (this.iceOps.get(identity) ?? []).filter((t) => t >= now - 60_000);
    if (recent.length >= this.limits.callIcePerMinute) {
      this.iceOps.set(identity, recent);
      return false;
    }
    recent.push(now);
    this.iceOps.set(identity, recent);
    return true;
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
    return this.withMetrics(response, start);
  }

  /** Like [metered], for a section with an await in the middle (the sync body digest). */
  private async measured(work: () => Promise<Response>): Promise<Response> {
    const start = { ...this.rows };
    let response: Response;
    try {
      response = await work();
    } catch (e) {
      console.error("storage section failed", { error: (e as Error)?.name });
      response = errorResponse(503, "storage_error", true);
    }
    return this.withMetrics(response, start);
  }

  private withMetrics(response: Response, start: { read: number; written: number }): Response {
    if (this.exposeMetrics) {
      response.headers.set("x-relay-sql-rows", `read=${this.rows.read - start.read};written=${this.rows.written - start.written}`);
    }
    return response;
  }

  private migrate(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS call_create_budget (id INTEGER PRIMARY KEY AUTOINCREMENT, identity TEXT NOT NULL, at_ms INTEGER NOT NULL)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS call_slots (group_key TEXT PRIMARY KEY, revision INTEGER NOT NULL, room TEXT NOT NULL, creator TEXT NOT NULL, claimed_at INTEGER NOT NULL) WITHOUT ROWID");
    // G5 signed roster: one pinned owner key per group (hash of the group id, never the id itself).
    this.sql.exec("CREATE TABLE IF NOT EXISTS call_group_owners (group_key TEXT PRIMARY KEY, owner TEXT NOT NULL, pinned_at INTEGER NOT NULL) WITHOUT ROWID");
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    const current = Number(this.q<{ value: string }>("SELECT value FROM meta WHERE key = 'schema'")[0]?.value ?? 0);
    const steps: Array<() => void> = [
      () => this.schemaV1(), () => this.schemaV2(), () => this.schemaV3(), () => this.schemaV4(), () => this.schemaV5(),
      () => this.schemaV6(), () => this.schemaV7(),
    ];
    this.migrateDevices();
    this.migrateLink();
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
    const version = Number(this.q<{ value: string }>("SELECT value FROM meta WHERE key = 'devices_schema'")[0]?.value ?? 0);
    if (version >= 3) return;
    this.ctx.storage.transactionSync(() => {
      if (version < 1) {
        this.sql.exec(`CREATE TABLE IF NOT EXISTS devices (
          pubkey TEXT NOT NULL,
          device TEXT NOT NULL,
          label TEXT NOT NULL DEFAULT '',
          acked INTEGER NOT NULL,
          last_seen INTEGER NOT NULL,
          added_at INTEGER NOT NULL,
          gap_through INTEGER NOT NULL DEFAULT 0,
          gap_shown INTEGER NOT NULL DEFAULT 0,
          auth_pubkey TEXT NOT NULL DEFAULT '',
          protocol_version INTEGER NOT NULL DEFAULT 1,
          revoked_at INTEGER NOT NULL DEFAULT 0,
          PRIMARY KEY (pubkey, device)
        ) WITHOUT ROWID`);
        // Per identity: the highest deletion floor ever reached and the seq head it was computed against.
        this.sql.exec("CREATE TABLE IF NOT EXISTS cursor_state (pubkey TEXT PRIMARY KEY, floor INTEGER NOT NULL, high INTEGER NOT NULL) WITHOUT ROWID");
      } else if (version < 2) {
        // v2 (WP05/G1): per-device authentication keys. Existing WP04 rows keep an empty key and stay legacy.
        this.sql.exec("ALTER TABLE devices ADD COLUMN auth_pubkey TEXT NOT NULL DEFAULT ''");
        this.sql.exec("ALTER TABLE devices ADD COLUMN protocol_version INTEGER NOT NULL DEFAULT 1");
        this.sql.exec("ALTER TABLE devices ADD COLUMN revoked_at INTEGER NOT NULL DEFAULT 0");
      }
      // The phone-only administration key, one per identity. Its presence means the identity has migrated.
      this.sql.exec("CREATE TABLE IF NOT EXISTS identity_admin (pubkey TEXT PRIMARY KEY, admin_pubkey TEXT NOT NULL UNIQUE, migrated_at INTEGER NOT NULL) WITHOUT ROWID");
      if (version < 3) {
        // v3 (WP05-A review): one device key may resolve to one (identity, device). Before the UNIQUE
        // index can be built, rows that already share a key are made unambiguous: the lexicographically
        // first (pubkey, device) keeps the key, every other row loses its auth_pubkey and can no longer
        // authenticate. Blanking rather than deleting keeps cursors and the audit trail.
        this.sql.exec(`UPDATE devices SET auth_pubkey = ''
          WHERE auth_pubkey <> '' AND EXISTS (
            SELECT 1 FROM devices AS keep
            WHERE keep.auth_pubkey = devices.auth_pubkey
              AND (keep.pubkey < devices.pubkey OR (keep.pubkey = devices.pubkey AND keep.device < devices.device))
          )`);
        this.sql.exec("CREATE UNIQUE INDEX IF NOT EXISTS devices_auth_pubkey ON devices (auth_pubkey) WHERE auth_pubkey <> ''");
      }
      this.sql.exec("INSERT INTO meta (key, value) VALUES ('devices_schema', '3') ON CONFLICT(key) DO UPDATE SET value = excluded.value");
    });
  }

  /**
   * WP05 part B tables, additive and tracked by their own meta key so the numbered `schema` stays 7.
   * Legacy identities never read or write them. `link_slots` holds one row per identity at most;
   * `sync_parts` only holds an assembly until it completes or expires; `sync_events` is capped per
   * identity; `sync_cursors` is one small row per device.
   */
  private migrateLink(): void {
    const version = Number(this.q<{ value: string }>("SELECT value FROM meta WHERE key = 'link_schema'")[0]?.value ?? 0);
    if (version >= 1) return;
    this.ctx.storage.transactionSync(() => {
      this.sql.exec(`CREATE TABLE IF NOT EXISTS link_slots (
        slot TEXT PRIMARY KEY,
        pubkey TEXT NOT NULL,
        bundle BLOB NOT NULL,
        size INTEGER NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        lease_until INTEGER NOT NULL DEFAULT 0
      ) WITHOUT ROWID`);
      // One live slot per identity; the unique index makes a race between two PUTs impossible.
      this.sql.exec("CREATE UNIQUE INDEX IF NOT EXISTS link_slots_pubkey ON link_slots (pubkey)");
      this.sql.exec(`CREATE TABLE IF NOT EXISTS sync_parts (
        pubkey TEXT NOT NULL,
        id TEXT NOT NULL,
        rev INTEGER NOT NULL,
        part INTEGER NOT NULL,
        parts INTEGER NOT NULL,
        total INTEGER NOT NULL,
        hash TEXT NOT NULL,
        expires_at INTEGER NOT NULL,
        data BLOB NOT NULL,
        PRIMARY KEY (pubkey, id, part)
      ) WITHOUT ROWID`);
      this.sql.exec(`CREATE TABLE IF NOT EXISTS sync_events (
        seq INTEGER PRIMARY KEY AUTOINCREMENT,
        pubkey TEXT NOT NULL,
        id TEXT NOT NULL,
        rev INTEGER NOT NULL,
        total INTEGER NOT NULL,
        hash TEXT NOT NULL,
        body BLOB NOT NULL,
        created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL,
        UNIQUE (pubkey, id)
      )`);
      this.sql.exec("CREATE INDEX IF NOT EXISTS sync_events_fetch ON sync_events (pubkey, seq)");
      this.sql.exec(`CREATE TABLE IF NOT EXISTS sync_cursors (
        pubkey TEXT NOT NULL,
        device TEXT NOT NULL,
        cursor INTEGER NOT NULL,
        PRIMARY KEY (pubkey, device)
      ) WITHOUT ROWID`);
      this.sql.exec("INSERT INTO meta (key, value) VALUES ('link_schema', '1') ON CONFLICT(key) DO UPDATE SET value = excluded.value");
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

function parseSend(body: ArrayBuffer, limits: Limits, sender: string): { deliveries: SizedDelivery[]; media: string[] } | { status: number; code: ErrorCode } {
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
    // WP11: absent or false is the legacy delivery; only a literal true is urgent, anything else is malformed.
    const urgent = (d as Record<string, unknown>).urgent;
    if (urgent !== undefined && typeof urgent !== "boolean") return bad;
    const key = `${to}/${id}`;
    if (keys.has(key)) return bad;
    keys.add(key);
    out.push({ to, id, envelope, size: utf8.encode(envelope).byteLength, urgent: urgent === true });
  }
  // One ring per request, and never to oneself: an urgent wake is for another person's phone.
  const urgentCount = out.filter((d) => d.urgent).length;
  if (urgentCount > 1) return bad;
  if (out.some((d) => d.urgent && d.to === sender)) return bad;
  const media = parseMediaRefs(value.media, limits);
  if (media === null) return bad;
  if (media === "too_many") return { status: 400, code: "too_many_media" };
  return { deliveries: out, media };
}

/**
 * The optional `media` field: absent or [] means none (legacy). Otherwise an array of at most [maxMediaRefs]
 * distinct lowercase 64-hex SHA-256 digests; anything else (wrong type, uppercase, duplicate, non-string) is bad.
 */
function parseMediaRefs(raw: unknown, limits: Limits): string[] | "too_many" | null {
  if (raw === undefined) return [];
  if (!Array.isArray(raw)) return null;
  if (raw.length > limits.maxMediaRefs) return "too_many";
  const seen = new Set<string>();
  for (const h of raw) {
    if (typeof h !== "string" || !MEDIA_DIGEST.test(h) || seen.has(h)) return null;
    seen.add(h);
  }
  return [...seen];
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

/** Compares two hex digests without an early exit on the first differing character. */
function sameHex(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function sameBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

/** Base64 for the bounded sync bodies; chunked so a spread never hits the argument limit. */
function base64(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i += 8192) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  }
  return btoa(binary);
}

/** Strict decoder: canonical alphabet and padding only, never a permissive parse of junk. */
function decodeBase64(text: string): Uint8Array | null {
  if (text.length > 16 * 1024 || text.length % 4 !== 0 || !/^[A-Za-z0-9+/]*={0,2}$/.test(text)) return null;
  try {
    const binary = atob(text);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

/**
 * Validates one sync part against the bounds in G3. Everything here is a cheap range check except the
 * base64 decode, which is capped at the 16 KiB envelope size before it runs.
 */
function parseSyncPart(body: ArrayBuffer, limits: Limits): SyncPart | { status: 400 | 413; code: ErrorCode } {
  const bad: { status: 400 | 413; code: ErrorCode } = { status: 400, code: "bad_request" };
  const tooLarge: { status: 400 | 413; code: ErrorCode } = { status: 413, code: "envelope_too_large" };
  const value = parseJson(body);
  if (value?.v !== PROTOCOL_VERSION) return bad;
  const { id, rev, part, parts, total, hash, expires, data } = value;
  if (typeof id !== "string" || !SYNC_ID.test(id)) return bad;
  if (!Number.isSafeInteger(rev) || (rev as number) < 1 || (rev as number) > 0x7fffffff) return bad;
  if (!Number.isSafeInteger(parts) || (parts as number) < 1 || (parts as number) > limits.maxSyncParts || limits.maxSyncParts < 1) return bad;
  if (!Number.isSafeInteger(part) || (part as number) < 0 || (part as number) >= (parts as number)) return bad;
  if (!Number.isSafeInteger(total) || (total as number) < 0) return bad;
  if ((total as number) > limits.maxSyncBodyBytes) return tooLarge;
  if (typeof hash !== "string" || !/^[0-9a-f]{64}$/.test(hash)) return bad;
  if (!Number.isSafeInteger(expires) || (expires as number) <= 0) return bad;
  if (typeof data !== "string") return bad;
  const bytes = decodeBase64(data);
  if (!bytes) return bad;
  if (bytes.byteLength > limits.maxSyncBodyBytes) return tooLarge;
  // A part can never exceed the declared total of the whole envelope.
  if (bytes.byteLength > (total as number)) return bad;
  const now = Date.now();
  if ((expires as number) <= now) return bad;
  return {
    id,
    rev: rev as number,
    part: part as number,
    parts: parts as number,
    total: total as number,
    hash,
    // A client cannot extend retention past the server's own ceiling.
    expiresAt: Math.min(expires as number, now + limits.syncTtlMs),
    data: bytes,
  };
}

/** `after` is optional: without it the caller's stored device cursor is used. */
function parseSyncQuery(url: URL, limits: Limits): { after: number | null; limit: number } | null {
  for (const key of url.searchParams.keys()) {
    if (key !== "after" && key !== "limit" && key !== "device") return null;
  }
  if (deviceParam(url) === null) return null;
  const rawAfter = url.searchParams.get("after");
  const after = rawAfter === null ? null : /^\d{1,15}$/.test(rawAfter) ? Number(rawAfter) : NaN;
  if (after !== null && !isSeq(after)) return null;
  const rawLimit = url.searchParams.get("limit");
  const limit = rawLimit === null ? limits.maxSyncPageEvents : /^\d{1,6}$/.test(rawLimit) ? Number(rawLimit) : NaN;
  if (!isLimit(limit)) return null;
  return { after, limit: Math.min(limit, limits.maxSyncPageEvents) };
}
