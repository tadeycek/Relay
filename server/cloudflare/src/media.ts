import { DurableObject } from "cloudflare:workers";
import { AUTH_WINDOW_S, verifyRequestAuth } from "./auth";
import { PROTOCOL_VERSION, PUBKEY, allowlistFrom, errorResponse, json, limitsFrom, sha256Hex, type Limits } from "./protocol";

const SCHEMA_VERSION = 1;
const nowS = () => Math.floor(Date.now() / 1000);

/**
 * One encrypted photo or video, in its own SQLite-backed Durable Object named after the blob's
 * SHA-256, so a 25 MiB upload never holds up the message mailbox. The app encrypts before uploading
 * (AES-256-GCM; the key travels only inside the end-to-end encrypted message), so this stores bytes
 * it can't read.
 *
 * The uploader names the recipients. Only they can download it, and each confirms with DELETE once
 * the decrypted file is safely stored on the phone. When all have confirmed, or after
 * [Limits.mediaLifetimeMs] (an alarm), the object deletes everything it holds.
 *
 *   PUT    /v1/media/<sha256>   body = blob, `x-relay-recipients: <pubkey>,<pubkey>,...`
 *   GET    /v1/media/<sha256>   recipients only
 *   DELETE /v1/media/<sha256>   a recipient confirms it has the file
 */
export class MediaBlob extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  private readonly limits: Limits;
  private readonly allowed: Set<string>;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    const vars = env as unknown as Record<string, unknown>;
    this.limits = limitsFrom(vars);
    this.allowed = allowlistFrom(vars.ALLOWED_PUBKEYS);
    this.migrate();
  }

  async fetch(request: Request): Promise<Response> {
    const hash = new URL(request.url).pathname.split("/").pop() ?? "";
    try {
      switch (request.method) {
        case "PUT": return await this.put(request, hash);
        case "GET": return await this.get(request, hash);
        case "DELETE": return await this.confirm(request, hash);
        default: return errorResponse(405, "method_not_allowed");
      }
    } catch (e) {
      console.error("media request failed", { method: request.method, error: (e as Error)?.name });
      return errorResponse(503, "storage_error", true);
    }
  }

  async alarm(): Promise<void> {
    await this.forget();
  }

  /** Deletes the file and everything known about it, leaving an empty layout for late requests. */
  private async forget(): Promise<void> {
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    this.migrate();
  }

  private async put(request: Request, hash: string): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength === 0) return errorResponse(400, "bad_request");
    if (body.byteLength > this.limits.maxMediaBytes) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), this.allowed);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    // The address is the content: it can't be stored under someone else's hash.
    if ((await sha256Hex(body)) !== hash) return errorResponse(400, "bad_request");
    const recipients = [...new Set((request.headers.get("x-relay-recipients") ?? "").split(",").map((r) => r.trim().toLowerCase()).filter(Boolean))];
    if (recipients.length === 0 || recipients.length > this.limits.maxMediaRecipients) return errorResponse(400, "bad_request");
    if (!recipients.every((r) => PUBKEY.test(r))) return errorResponse(400, "bad_request");
    if (!recipients.every((r) => this.allowed.has(r))) return errorResponse(400, "unknown_recipient");

    const now = Date.now();
    const outcome = this.ctx.storage.transactionSync((): "replayed" | "stored" | "duplicate" => {
      if (!this.recordAuth(auth.eventId, auth.createdAt)) return "replayed";
      const existed = this.sql.exec("SELECT 1 FROM info").toArray().length > 0;
      if (!existed) {
        for (let i = 0, chunk = 0; i < body.byteLength; i += this.limits.mediaChunkBytes, chunk++) {
          this.sql.exec("INSERT INTO chunks (n, data) VALUES (?, ?)", chunk, body.slice(i, i + this.limits.mediaChunkBytes));
        }
        this.sql.exec("INSERT INTO info (id, size, uploader, stored_at) VALUES (1, ?, ?, ?)", body.byteLength, auth.pubkey, now);
      }
      // Sending the same file again (a retry, or to more people) only adds recipients.
      for (const r of recipients) this.sql.exec("INSERT INTO recipients (pubkey) VALUES (?) ON CONFLICT(pubkey) DO NOTHING", r);
      return existed ? "duplicate" : "stored";
    });
    if (outcome === "replayed") return errorResponse(401, "auth_replayed");
    if (outcome === "stored") await this.ctx.storage.setAlarm(now + this.limits.mediaLifetimeMs);
    return json({ v: PROTOCOL_VERSION, stored: true, duplicate: outcome === "duplicate" });
  }

  private async get(request: Request, hash: string): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), this.allowed);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
    if (!this.isRecipient(auth.pubkey)) return this.missingOrForbidden();
    const chunks = this.sql.exec<{ data: ArrayBuffer }>("SELECT data FROM chunks ORDER BY n").toArray();
    const size = chunks.reduce((sum, c) => sum + c.data.byteLength, 0);
    const out = new Uint8Array(size);
    let offset = 0;
    for (const c of chunks) {
      out.set(new Uint8Array(c.data), offset);
      offset += c.data.byteLength;
    }
    return new Response(out, { headers: { "content-type": "application/octet-stream", "cache-control": "no-store", "x-relay-sha256": hash } });
  }

  private async confirm(request: Request, hash: string): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), this.allowed);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
    if (!this.isRecipient(auth.pubkey)) return this.missingOrForbidden();
    this.sql.exec("UPDATE recipients SET confirmed = 1 WHERE pubkey = ?", auth.pubkey);
    const remaining = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM recipients WHERE confirmed = 0").one().n;
    // Everyone has it: nothing about the file stays on the server.
    if (remaining === 0) await this.forget();
    return json({ v: PROTOCOL_VERSION, remaining, hash });
  }

  private isRecipient(pubkey: string): boolean {
    return this.sql.exec("SELECT 1 FROM recipients WHERE pubkey = ?", pubkey).toArray().length > 0;
  }

  /** Never tells a stranger whether a file exists. */
  private missingOrForbidden(): Response {
    return errorResponse(404, "not_found");
  }

  private recordAuth(eventId: string, createdAt: number): boolean {
    const expiresAt = (createdAt + AUTH_WINDOW_S + 1) * 1000;
    this.sql.exec("DELETE FROM auth_seen WHERE expires_at < ?", Date.now());
    return this.sql.exec("INSERT INTO auth_seen (id, expires_at) VALUES (?, ?) ON CONFLICT(id) DO NOTHING RETURNING id", eventId, expiresAt).toArray().length === 1;
  }

  private migrate(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID");
    const current = Number(this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'schema'").toArray()[0]?.value ?? 0);
    if (current >= SCHEMA_VERSION) return;
    this.ctx.storage.transactionSync(() => {
      this.sql.exec("CREATE TABLE IF NOT EXISTS info (id INTEGER PRIMARY KEY CHECK (id = 1), size INTEGER NOT NULL, uploader TEXT NOT NULL, stored_at INTEGER NOT NULL)");
      this.sql.exec("CREATE TABLE IF NOT EXISTS chunks (n INTEGER PRIMARY KEY, data BLOB NOT NULL)");
      this.sql.exec("CREATE TABLE IF NOT EXISTS recipients (pubkey TEXT PRIMARY KEY, confirmed INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID");
      this.sql.exec("CREATE TABLE IF NOT EXISTS auth_seen (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL) WITHOUT ROWID");
      this.sql.exec("INSERT INTO meta (key, value) VALUES ('schema', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", String(SCHEMA_VERSION));
    });
  }
}
