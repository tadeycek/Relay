import { DurableObject } from "cloudflare:workers";
import { AUTH_WINDOW_S, verifyRequestAuth } from "./auth";
import { MediaDigest } from "./media-digest";
import { MAX_RESUMABLE_MEDIA_BYTES, MEDIA_CONTROL_BYTES, MEDIA_PART_BYTES, PROTOCOL_VERSION, PUBKEY, errorResponse, json, limitsFrom, sha256Hex, type Limits } from "./protocol";

const SCHEMA_VERSION = 2;
const nowS = () => Math.floor(Date.now() / 1000);
type Info = { size: number; uploader: string; stored_at: number; version: number; part_size: number; next_part: number; complete: number; digest: string | null };
class BodyTooLarge extends Error {}

/** Content-Length is only a hint: enforce the same bound while reading a chunked body. */
async function boundedBody(request: Request, maximum: number): Promise<ArrayBuffer> {
  const reader = request.body?.getReader();
  if (!reader) return new ArrayBuffer(0);
  const out = new Uint8Array(maximum);
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (value.byteLength > maximum - length) {
        await reader.cancel();
        throw new BodyTooLarge();
      }
      out.set(value, length);
      length += value.byteLength;
    }
    return out.buffer.slice(0, length);
  } finally {
    reader.releaseLock();
  }
}

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

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    const vars = env as unknown as Record<string, unknown>;
    this.limits = limitsFrom(vars);
    this.migrate();
  }

  async fetch(request: Request): Promise<Response> {
    const path = new URL(request.url).pathname;
    const v2 = /^\/v2\/media\/([0-9a-f]{64})(?:\/(init|status|complete|parts\/(?:0|[1-9][0-9]{0,3})))?$/.exec(path);
    const hash = v2?.[1] ?? path.split("/").pop() ?? "";
    try {
      if (v2) return await this.resumable(request, hash, v2[2]);
      switch (request.method) {
        case "PUT": return await this.put(request, hash);
        case "GET": return await this.get(request, hash);
        case "DELETE": return await this.confirm(request, hash);
        default: return errorResponse(405, "method_not_allowed");
      }
    } catch (e) {
      if (e instanceof BodyTooLarge) return errorResponse(413, "request_too_large");
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
    const body = await boundedBody(request, this.limits.maxMediaBytes);
    if (body.byteLength === 0) return errorResponse(400, "bad_request");
    if (body.byteLength > this.limits.maxMediaBytes) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    // The address is the content: it can't be stored under someone else's hash.
    if ((await sha256Hex(body)) !== hash) return errorResponse(400, "bad_request");
    const recipients = [...new Set((request.headers.get("x-relay-recipients") ?? "").split(",").map((r) => r.trim().toLowerCase()).filter(Boolean))];
    if (recipients.length === 0 || recipients.length > this.limits.maxMediaRecipients) return errorResponse(400, "bad_request");
    if (!recipients.every((r) => PUBKEY.test(r))) return errorResponse(400, "bad_request");
    const [uploaderIsMember, ...recipientsAreMembers] = await this.areMembers([auth.pubkey, ...recipients]);
    if (!uploaderIsMember) return errorResponse(403, "not_allowed");
    if (!recipientsAreMembers.every(Boolean)) return errorResponse(400, "unknown_recipient");

    const now = Date.now();
    const outcome = this.ctx.storage.transactionSync((): "replayed" | "stored" | "duplicate" | "forbidden" => {
      if (!this.recordAuth(auth.eventId, auth.createdAt)) return "replayed";
      const existing = this.info();
      // A duplicate must come from the original uploader. Otherwise a member who obtained the
      // encrypted bytes could silently grant new people access to somebody else's transfer.
      if (existing && (existing.uploader !== auth.pubkey || existing.version !== 1)) return "forbidden";
      const existed = !!existing;
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
    if (outcome === "forbidden") return this.missingOrForbidden();
    if (outcome === "stored") await this.ctx.storage.setAlarm(now + this.limits.mediaLifetimeMs);
    return json({ v: PROTOCOL_VERSION, stored: true, duplicate: outcome === "duplicate" });
  }

  private async get(request: Request, hash: string): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
    if (!this.isRecipient(auth.pubkey)) return this.missingOrForbidden();
    const info = this.info();
    if (!info?.complete) return this.missingOrForbidden();
    if (info.version !== 1) return errorResponse(426, "upgrade_required");
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

  private async confirm(request: Request, hash: string, version?: number): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
    const info = this.info();
    if (!info?.complete || (version !== undefined && info.version !== version) || !this.isRecipient(auth.pubkey)) return this.missingOrForbidden();
    this.sql.exec("UPDATE recipients SET confirmed = 1 WHERE pubkey = ?", auth.pubkey);
    const remaining = this.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM recipients WHERE confirmed = 0").one().n;
    // Everyone has it: nothing about the file stays on the server.
    if (remaining === 0) await this.forget();
    return json({ v: PROTOCOL_VERSION, remaining, hash });
  }

  private async resumable(request: Request, hash: string, action: string | undefined): Promise<Response> {
    const method = request.method;
    if (action === "init" && method === "POST") return this.initUpload(request);
    if (!action && method === "DELETE") {
      return this.confirm(request, hash, 2);
    }
    const part = action?.startsWith("parts/") ? Number(action.slice(6)) : null;
    if (!((action === "status" && method === "GET") || (action === "complete" && method === "POST") ||
        (part !== null && (method === "GET" || method === "PUT")))) return errorResponse(405, "method_not_allowed");
    const body = method === "PUT" ? await boundedBody(request, MEDIA_PART_BYTES) : method === "POST" ? await boundedBody(request, MEDIA_CONTROL_BYTES) : null;
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
    const info = this.info();
    if (!info || info.version !== 2) return this.missingOrForbidden();
    const uploader = info.uploader === auth.pubkey;
    if (method === "POST" || method === "PUT") {
      if (!uploader) return this.missingOrForbidden();
    } else if (!uploader && !(info.complete && this.isRecipient(auth.pubkey))) return this.missingOrForbidden();
    if (action === "status") return this.uploadStatus(info);
    if (action === "complete") return this.completeUpload(info, hash, body!);
    if (part === null || part >= Math.ceil(info.size / info.part_size)) return errorResponse(400, "bad_request");
    if (method === "GET") {
      if (!info.complete) return this.missingOrForbidden();
      const chunk = this.sql.exec<{ data: ArrayBuffer; sha256: string }>("SELECT data, sha256 FROM chunks WHERE n = ?", part).toArray()[0];
      if (!chunk) return errorResponse(503, "storage_error", true);
      return new Response(chunk.data, { headers: { "content-type": "application/octet-stream", "cache-control": "no-store", "x-relay-part-sha256": chunk.sha256, "x-relay-sha256": hash } });
    }
    const checksum = request.headers.get("x-relay-part-sha256") ?? "";
    if (!PUBKEY.test(checksum) || body!.byteLength !== Math.min(info.part_size, info.size - part * info.part_size) ||
        await sha256Hex(body!) !== checksum) return errorResponse(400, "bad_request");
    // Hashing the part awaited WebCrypto, so re-read inside a synchronous transaction. A competing
    // retry/complete cannot replace a committed part or advance this checkpoint twice.
    return this.ctx.storage.transactionSync(() => {
      const current = this.info();
      if (!current || current.version !== 2 || current.uploader !== auth.pubkey || current.stored_at !== info.stored_at) return this.missingOrForbidden();
      const existing = this.sql.exec<{ sha256: string }>("SELECT sha256 FROM chunks WHERE n = ?", part).toArray()[0];
      if (existing) return existing.sha256 === checksum ? json({ v: 2, stored: true, duplicate: true, nextPart: current.next_part }) : errorResponse(409, "id_conflict");
      if (current.complete || part !== current.next_part) return errorResponse(409, "id_conflict");
      const digest = MediaDigest.restore(current.digest!).update(new Uint8Array(body!));
      this.sql.exec("INSERT INTO chunks (n, data, sha256) VALUES (?, ?, ?)", part, body!, checksum);
      this.sql.exec("UPDATE info SET next_part = next_part + 1, digest = ? WHERE id = 1", digest.checkpoint());
      return json({ v: 2, stored: true, duplicate: false, nextPart: part + 1 });
    });
  }

  private async initUpload(request: Request): Promise<Response> {
    const body = await boundedBody(request, MEDIA_CONTROL_BYTES);
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    let input: { size?: unknown; partSize?: unknown; recipients?: unknown };
    try { input = JSON.parse(new TextDecoder().decode(body)); } catch { return errorResponse(400, "bad_request"); }
    if (!input || !Number.isSafeInteger(input.size) || (input.size as number) <= 0 || input.partSize !== MEDIA_PART_BYTES ||
        !Array.isArray(input.recipients) || !input.recipients.every((r) => typeof r === "string" && PUBKEY.test(r))) return errorResponse(400, "bad_request");
    if ((input.size as number) > MAX_RESUMABLE_MEDIA_BYTES) return errorResponse(413, "request_too_large");
    const recipients = [...new Set(input.recipients as string[])].sort();
    if (!recipients.length || recipients.length > this.limits.maxMediaRecipients) return errorResponse(400, "bad_request");
    const [uploaderIsMember, ...members] = await this.areMembers([auth.pubkey, ...recipients]);
    if (!uploaderIsMember) return errorResponse(403, "not_allowed");
    if (!members.every(Boolean)) return errorResponse(400, "unknown_recipient");
    const now = Date.now();
    const response = this.ctx.storage.transactionSync(() => {
      if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
      const current = this.info();
      if (current) {
        if (current.version !== 2 || current.uploader !== auth.pubkey) return this.missingOrForbidden();
        const original = this.sql.exec<{ pubkey: string }>("SELECT pubkey FROM recipients ORDER BY pubkey").toArray().map((r) => r.pubkey);
        if (current.size !== input.size || current.part_size !== input.partSize || JSON.stringify(recipients) !== JSON.stringify(original)) return errorResponse(409, "id_conflict");
        return this.uploadStatus(current);
      }
      this.sql.exec("INSERT INTO info (id, size, uploader, stored_at, version, part_size, next_part, complete, digest) VALUES (1, ?, ?, ?, 2, ?, 0, 0, ?)", input.size as number, auth.pubkey, now, MEDIA_PART_BYTES, new MediaDigest().checkpoint());
      for (const r of recipients) this.sql.exec("INSERT INTO recipients (pubkey) VALUES (?)", r);
      return this.uploadStatus(this.info()!);
    });
    if (response.ok) await this.ctx.storage.setAlarm(this.info()!.stored_at + this.limits.mediaLifetimeMs);
    return response;
  }

  private completeUpload(info: Info, hash: string, body: ArrayBuffer): Response {
    let manifest: { parts?: unknown };
    try { manifest = JSON.parse(new TextDecoder().decode(body)); } catch { return errorResponse(400, "bad_request"); }
    const count = Math.ceil(info.size / info.part_size);
    if (!manifest || !Array.isArray(manifest.parts) || manifest.parts.length !== count ||
        !manifest.parts.every((p) => typeof p === "string" && PUBKEY.test(p))) return errorResponse(400, "bad_request");
    if (info.next_part !== count) return errorResponse(409, "id_conflict", true);
    const parts = this.sql.exec<{ n: number; sha256: string }>("SELECT n, sha256 FROM chunks ORDER BY n").toArray();
    if (parts.length !== count || parts.some((p, n) => p.n !== n || p.sha256 !== (manifest.parts as string[])[n])) return errorResponse(409, "id_conflict");
    if (MediaDigest.restore(info.digest!).hex() !== hash) return errorResponse(400, "bad_request");
    this.sql.exec("UPDATE info SET complete = 1 WHERE id = 1");
    return json({ v: 2, stored: true, duplicate: !!info.complete });
  }

  private uploadStatus(info: Info): Response {
    return json({ v: 2, size: info.size, partSize: info.part_size, totalParts: Math.ceil(info.size / info.part_size), nextPart: info.next_part,
      receivedParts: Array.from({ length: info.next_part }, (_, n) => n), state: info.complete ? "complete" : "uploading", expiresAt: info.stored_at + this.limits.mediaLifetimeMs });
  }

  private info(): Info | undefined {
    const info = this.sql.exec<Info>("SELECT * FROM info WHERE id = 1").toArray()[0];
    if (info && info.stored_at + this.limits.mediaLifetimeMs <= Date.now()) {
      // Enforce expiry even when alarm delivery is delayed; no retry can extend an abandoned upload.
      this.sql.exec("DELETE FROM chunks");
      this.sql.exec("DELETE FROM recipients");
      this.sql.exec("DELETE FROM info");
      return undefined;
    }
    return info;
  }

  /** Membership lives in the mailbox object (founders plus everyone admitted by pairing). */
  private areMembers(pubkeys: string[]): Promise<boolean[]> {
    return this.env.MAILBOX.get(this.env.MAILBOX.idFromName("pilot")).areMembers(pubkeys);
  }

  /** Recipients were members when the file was uploaded; only they can read or confirm it. */
  private isRecipient(pubkey: string): boolean {
    return !!this.info() && this.sql.exec("SELECT 1 FROM recipients WHERE pubkey = ?", pubkey).toArray().length > 0;
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
      if (current < 2) {
        this.sql.exec("ALTER TABLE info ADD COLUMN version INTEGER NOT NULL DEFAULT 1");
        this.sql.exec("ALTER TABLE info ADD COLUMN part_size INTEGER NOT NULL DEFAULT 0");
        this.sql.exec("ALTER TABLE info ADD COLUMN next_part INTEGER NOT NULL DEFAULT 0");
        this.sql.exec("ALTER TABLE info ADD COLUMN complete INTEGER NOT NULL DEFAULT 1");
        this.sql.exec("ALTER TABLE info ADD COLUMN digest TEXT");
        this.sql.exec("ALTER TABLE chunks ADD COLUMN sha256 TEXT NOT NULL DEFAULT ''");
      }
      this.sql.exec("INSERT INTO meta (key, value) VALUES ('schema', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", String(SCHEMA_VERSION));
    });
  }
}
