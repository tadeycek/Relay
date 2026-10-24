import { DurableObject } from "cloudflare:workers";
import { AUTH_WINDOW_S, verifyRequestAuth } from "./auth";
import { DEVICE_ID, PRIMARY_DEVICE, PROTOCOL_VERSION, PUBKEY, deviceParam, errorResponse, json, limitsFrom, sha256Hex, type Limits } from "./protocol";

const SCHEMA_VERSION = 2;
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
 * Per-device confirmation (WP04): a recipient is a (pubkey, device) pair. At PUT the recipient's device
 * list is snapshotted from the Mailbox object in the same call that checks membership; an identity
 * without linked devices is the single pair (pubkey, "primary"), exactly as before. The object is erased
 * when every pair has confirmed. A pair whose device was unlinked, or has been dormant longer than
 * [Limits.dormantAfterMs], is waived, so a desktop that vanished after an upload can't hold the file for
 * the whole lifetime. The `device` query parameter is NOT authentication (see PROTOCOL.md).
 *
 *   PUT    /v1/media/<sha256>[?device=]   body = blob, `x-relay-recipients: <pubkey>,<pubkey>,...`
 *   GET    /v1/media/<sha256>[?device=]   recipients only
 *   DELETE /v1/media/<sha256>[?device=]   a recipient device confirms it has the file
 */
export class MediaBlob extends DurableObject<Env> {
  private readonly sql: SqlStorage;
  private readonly limits: Limits;
  private readonly exposeMetrics: boolean;
  private readonly rows = { read: 0, written: 0 };

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    const vars = env as unknown as Record<string, unknown>;
    this.limits = limitsFrom(vars);
    this.exposeMetrics = vars.EXPOSE_METRICS === "1";
    this.migrate();
  }

  async fetch(request: Request): Promise<Response> {
    // A mailbox RPC, deleteAlarm or deleteAll yields. Keep the entire read/settle/erase and PUT
    // sequence exclusive for this hash; otherwise an older confirmation can erase a newer PUT.
    return this.ctx.blockConcurrencyWhile(() => this.fetchExclusive(request));
  }

  private async fetchExclusive(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const hash = url.pathname.split("/").pop() ?? "";
    const device = deviceParam(url);
    if (device === null) {
      await request.arrayBuffer(); // drain, so the caller's upload isn't cut off mid-stream
      return errorResponse(400, "bad_request");
    }
    const start = { ...this.rows };
    let response: Response;
    try {
      switch (request.method) {
        case "PUT": response = await this.put(request, hash, device); break;
        case "GET": response = await this.get(request, hash, device); break;
        case "DELETE": response = await this.confirm(request, hash, device); break;
        default: response = errorResponse(405, "method_not_allowed");
      }
    } catch (e) {
      console.error("media request failed", { method: request.method, error: (e as Error)?.name });
      response = errorResponse(503, "storage_error", true);
    }
    // Rows of this object only; the Mailbox object's rows for the same request are counted there.
    if (this.exposeMetrics) {
      response.headers.set("x-relay-sql-rows", `read=${this.rows.read - start.read};written=${this.rows.written - start.written}`);
    }
    return response;
  }

  /**
   * RPC from the Mailbox before it queues a send that attaches this blob (WP04-AC4): true only while the object
   * exists, was uploaded by [uploader] and has not outlived its lifetime. The answer is the same for "absent",
   * "someone else's" and "expired", so it is not an oracle.
   */
  async attachCheck(uploader: string): Promise<boolean> {
    return this.ctx.blockConcurrencyWhile(async () => this.attachable(uploader));
  }

  /**
   * RPC from the Mailbox after it queued the message (WP04-AC4). [pairs] are the (recipient, device) pairs that
   * were active when the message was queued. Only pairs whose recipient pubkey is ALREADY a recipient of this
   * blob (named at PUT, which checked membership) are added, so access never widens to a new identity; a pair
   * already present keeps its confirmation. Exclusive like every other request on this object, so it cannot
   * interleave with a settle, a confirm, a repeat PUT or the alarm.
   */
  async grant(uploader: string, pairs: Array<[string, string]>): Promise<{ status: "attached" | "gone"; added: number; rows: { read: number; written: number } }> {
    return this.ctx.blockConcurrencyWhile(async () => {
      const start = { ...this.rows };
      const done = (status: "attached" | "gone", added: number) =>
        ({ status, added, rows: { read: this.rows.read - start.read, written: this.rows.written - start.written } });
      if (!this.attachable(uploader)) return done("gone", 0);
      const known = new Set(this.run<{ pubkey: string }>("SELECT DISTINCT pubkey FROM recipients").map((r) => r.pubkey));
      const wanted = pairs.filter(([p, d]) => PUBKEY.test(p) && DEVICE_ID.test(d) && known.has(p));
      let linkedAdded = false;
      let added = 0;
      this.ctx.storage.transactionSync(() => {
        for (const [pubkey, device] of wanted) {
          const inserted = this.run("INSERT INTO recipients (pubkey, device) VALUES (?, ?) ON CONFLICT(pubkey, device) DO NOTHING RETURNING pubkey", pubkey, device).length;
          added += inserted;
          if (inserted && device !== PRIMARY_DEVICE) linkedAdded = true;
        }
      });
      // A new linked device may turn dormant before the lifetime alarm: let settle re-arm it, exactly as after a confirm.
      if (linkedAdded) await this.settle();
      return done("attached", added);
    });
  }

  private attachable(uploader: string): boolean {
    const info = this.run<{ stored_at: number; uploader: string }>("SELECT stored_at, uploader FROM info")[0];
    return !!info && info.uploader === uploader && Date.now() < info.stored_at + this.limits.mediaLifetimeMs;
  }

  async alarm(): Promise<void> {
    return this.ctx.blockConcurrencyWhile(() => this.alarmExclusive());
  }

  private async alarmExclusive(): Promise<void> {
    const info = this.run<{ stored_at: number }>("SELECT stored_at FROM info")[0];
    const waitingOnDevices = this.run("SELECT 1 FROM recipients WHERE confirmed = 0 AND device <> 'primary' LIMIT 1").length > 0;
    // Lifetime reached, or nobody linked is waiting (the original behaviour): erase. Otherwise this is the
    // early alarm set for a device that may have gone dormant, so look again and re-arm.
    if (!info || !waitingOnDevices || Date.now() >= info.stored_at + this.limits.mediaLifetimeMs) return this.forget();
    await this.settle();
  }

  /** Every statement goes through here so the rows read/written (what Cloudflare bills) can be reported. */
  private run<T extends Record<string, SqlStorageValue>>(query: string, ...params: SqlStorageValue[]): T[] {
    const cursor = this.sql.exec<T>(query, ...params);
    const result = cursor.toArray();
    this.rows.read += cursor.rowsRead;
    this.rows.written += cursor.rowsWritten;
    return result;
  }

  /** Deletes the file and everything known about it, leaving an empty layout for late requests. */
  private async forget(): Promise<void> {
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    this.migrate();
  }

  private async put(request: Request, hash: string, device: string): Promise<Response> {
    const body = await request.arrayBuffer();
    if (body.byteLength === 0) return errorResponse(400, "bad_request");
    if (body.byteLength > this.limits.maxMediaBytes) return errorResponse(413, "request_too_large");
    const auth = await verifyRequestAuth(request, body, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const who = await this.mailbox().resolveSigner(auth.pubkey, device);
    if (!who) return errorResponse(401, "auth_invalid");
    if (who.revoked) return errorResponse(401, "auth_revoked");
    if (who.device !== device) return errorResponse(403, device === PRIMARY_DEVICE ? "not_primary" : "unknown_device");
    const identity = who.identity;
    // The address is the content: it can't be stored under someone else's hash.
    if ((await sha256Hex(body)) !== hash) return errorResponse(400, "bad_request");
    const recipients = [...new Set((request.headers.get("x-relay-recipients") ?? "").split(",").map((r) => r.trim().toLowerCase()).filter(Boolean))];
    if (recipients.length === 0 || recipients.length > this.limits.maxMediaRecipients) return errorResponse(400, "bad_request");
    if (!recipients.every((r) => PUBKEY.test(r))) return errorResponse(400, "bad_request");
    // One call to the Mailbox object answers both questions: who is a member, and which devices are active.
    const { members, devices } = await this.mailbox().mediaRecipients([identity, ...recipients]);
    const [uploaderIsMember, ...recipientsAreMembers] = members;
    if (!uploaderIsMember) return errorResponse(403, "not_allowed");
    if (!recipientsAreMembers.every(Boolean)) return errorResponse(400, "unknown_recipient");
    // The snapshot: one (pubkey, device) pair per active device; an identity without devices is just "primary".
    const pairs = recipients.flatMap((r, i): Array<[string, string]> => {
      const ids = devices[i + 1];
      return (ids.length > 0 ? ids : [PRIMARY_DEVICE]).map((d): [string, string] => [r, d]);
    });

    const now = Date.now();
    const outcome = this.ctx.storage.transactionSync((): "replayed" | "stored" | "duplicate" => {
      if (!this.recordAuth(auth.eventId, auth.createdAt)) return "replayed";
      const existed = this.run("SELECT 1 FROM info").length > 0;
      if (!existed) {
        for (let i = 0, chunk = 0; i < body.byteLength; i += this.limits.mediaChunkBytes, chunk++) {
          this.run("INSERT INTO chunks (n, data) VALUES (?, ?)", chunk, body.slice(i, i + this.limits.mediaChunkBytes));
        }
        this.run("INSERT INTO info (id, size, uploader, stored_at) VALUES (1, ?, ?, ?)", body.byteLength, identity, now);
      }
      // Sending the same file again (a retry, or to more people) only adds recipients.
      for (const [pubkey, device] of pairs) {
        this.run("INSERT INTO recipients (pubkey, device) VALUES (?, ?) ON CONFLICT(pubkey, device) DO NOTHING", pubkey, device);
      }
      return existed ? "duplicate" : "stored";
    });
    if (outcome === "replayed") return errorResponse(401, "auth_replayed");
    if (outcome === "stored") await this.ctx.storage.setAlarm(now + this.limits.mediaLifetimeMs);
    return json({ v: PROTOCOL_VERSION, stored: true, duplicate: outcome === "duplicate" });
  }

  private async get(request: Request, hash: string, device: string): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const who = await this.mailbox().resolveSigner(auth.pubkey, device);
    if (!who) return errorResponse(401, "auth_invalid");
    if (who.revoked) return errorResponse(401, "auth_revoked");
    if (who.device !== device) return errorResponse(403, device === PRIMARY_DEVICE ? "not_primary" : "unknown_device");
    if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
    const refused = await this.refuseUnknownDevice(who.identity, device);
    if (refused) return refused;
    if (!this.isRecipient(who.identity, device)) return this.missingOrForbidden();
    const chunks = this.run<{ data: ArrayBuffer }>("SELECT data FROM chunks ORDER BY n");
    const size = chunks.reduce((sum, c) => sum + c.data.byteLength, 0);
    const out = new Uint8Array(size);
    let offset = 0;
    for (const c of chunks) {
      out.set(new Uint8Array(c.data), offset);
      offset += c.data.byteLength;
    }
    return new Response(out, { headers: { "content-type": "application/octet-stream", "cache-control": "no-store", "x-relay-sha256": hash } });
  }

  private async confirm(request: Request, hash: string, device: string): Promise<Response> {
    const auth = await verifyRequestAuth(request, null, nowS(), null);
    if (!auth.ok) return errorResponse(auth.status, auth.code);
    const who = await this.mailbox().resolveSigner(auth.pubkey, device);
    if (!who) return errorResponse(401, "auth_invalid");
    if (who.revoked) return errorResponse(401, "auth_revoked");
    if (who.device !== device) return errorResponse(403, device === PRIMARY_DEVICE ? "not_primary" : "unknown_device");
    if (!this.recordAuth(auth.eventId, auth.createdAt)) return errorResponse(401, "auth_replayed");
    const refused = await this.refuseUnknownDevice(who.identity, device);
    if (refused) return refused;
    if (!this.isRecipient(who.identity, device)) return this.missingOrForbidden();
    this.run("UPDATE recipients SET confirmed = 1 WHERE pubkey = ? AND device = ?", who.identity, device);
    return json({ v: PROTOCOL_VERSION, remaining: await this.settle(), hash });
  }

  /**
   * Works out what is still owed and erases the object when nothing is. A pair whose device was unlinked or
   * has been dormant too long is waived (its row is dropped). Legacy and primary-only objects need no RPC.
   * Returns the number of pairs still unconfirmed.
   */
  private async settle(): Promise<number> {
    const unconfirmed = () => this.run<{ pubkey: string; device: string }>("SELECT pubkey, device FROM recipients WHERE confirmed = 0");
    let waiting = unconfirmed();
    if (waiting.length === 0) {
      await this.forget();
      return 0;
    }
    const linked = waiting.filter((r) => r.device !== PRIMARY_DEVICE);
    if (linked.length === 0) return waiting.length;
    const states = await this.mailbox().deviceStates(linked.map((r): [string, string] => [r.pubkey, r.device]));
    // Synchronous again from here: re-read, because other requests may have run while we awaited.
    const state = new Map(linked.map((r, i) => [`${r.pubkey}/${r.device}`, states[i]]));
    waiting = unconfirmed();
    let nextDormancy = Infinity;
    for (const r of waiting) {
      const st = state.get(`${r.pubkey}/${r.device}`);
      if (!st) continue;
      if (!st.exists || !st.active) this.run("DELETE FROM recipients WHERE pubkey = ? AND device = ?", r.pubkey, r.device);
      else if (st.dormantAt > 0) nextDormancy = Math.min(nextDormancy, st.dormantAt);
    }
    const remaining = unconfirmed().length;
    if (remaining === 0) {
      await this.forget();
      return 0;
    }
    // Every surviving blob retains its lifetime alarm, even when the last linked pair was just waived.
    const info = this.run<{ stored_at: number }>("SELECT stored_at FROM info")[0];
    const expiry = (info?.stored_at ?? Date.now()) + this.limits.mediaLifetimeMs;
    await this.ctx.storage.setAlarm(Math.min(expiry, nextDormancy + 1000));
    return remaining;
  }

  /** Membership and devices live in the mailbox object (founders plus everyone admitted by pairing). */
  private mailbox() {
    return this.env.MAILBOX.get(this.env.MAILBOX.idFromName("pilot"));
  }

  /** 403 unknown_device for an unlinked device. The primary needs no lookup, so legacy requests cost nothing extra. */
  private async refuseUnknownDevice(pubkey: string, device: string): Promise<Response | null> {
    if (device === PRIMARY_DEVICE) return null;
    const [state] = await this.mailbox().deviceStates([[pubkey, device]]);
    return state.exists ? null : errorResponse(403, "unknown_device");
  }

  /** Recipients were members when the file was uploaded; only their snapshotted devices can read or confirm it. */
  private isRecipient(pubkey: string, device: string): boolean {
    return this.run("SELECT 1 FROM recipients WHERE pubkey = ? AND device = ?", pubkey, device).length > 0;
  }

  /** Never tells a stranger whether a file exists. */
  private missingOrForbidden(): Response {
    return errorResponse(404, "not_found");
  }

  private recordAuth(eventId: string, createdAt: number): boolean {
    const expiresAt = (createdAt + AUTH_WINDOW_S + 1) * 1000;
    this.run("DELETE FROM auth_seen WHERE expires_at < ?", Date.now());
    return this.run("INSERT INTO auth_seen (id, expires_at) VALUES (?, ?) ON CONFLICT(id) DO NOTHING RETURNING id", eventId, expiresAt).length === 1;
  }

  private migrate(): void {
    this.sql.exec("CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID");
    const current = Number(this.sql.exec<{ value: string }>("SELECT value FROM meta WHERE key = 'schema'").toArray()[0]?.value ?? 0);
    if (current >= SCHEMA_VERSION) return;
    this.ctx.storage.transactionSync(() => {
      if (current < 1) {
        this.sql.exec("CREATE TABLE IF NOT EXISTS info (id INTEGER PRIMARY KEY CHECK (id = 1), size INTEGER NOT NULL, uploader TEXT NOT NULL, stored_at INTEGER NOT NULL)");
        this.sql.exec("CREATE TABLE IF NOT EXISTS chunks (n INTEGER PRIMARY KEY, data BLOB NOT NULL)");
        this.sql.exec("CREATE TABLE IF NOT EXISTS recipients (pubkey TEXT PRIMARY KEY, confirmed INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID");
        this.sql.exec("CREATE TABLE IF NOT EXISTS auth_seen (id TEXT PRIMARY KEY, expires_at INTEGER NOT NULL) WITHOUT ROWID");
      }
      if (current < 2) {
        // v2 (WP04): recipients become (pubkey, device) pairs. Rows written before were single-device: "primary".
        this.sql.exec("CREATE TABLE recipients_v2 (pubkey TEXT NOT NULL, device TEXT NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (pubkey, device)) WITHOUT ROWID");
        this.sql.exec("INSERT INTO recipients_v2 (pubkey, device, confirmed) SELECT pubkey, 'primary', confirmed FROM recipients");
        this.sql.exec("DROP TABLE recipients");
        this.sql.exec("ALTER TABLE recipients_v2 RENAME TO recipients");
      }
      this.sql.exec("INSERT INTO meta (key, value) VALUES ('schema', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value", String(SCHEMA_VERSION));
    });
  }
}
