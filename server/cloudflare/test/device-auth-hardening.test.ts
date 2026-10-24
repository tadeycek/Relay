import { describe, expect, it } from "vitest";
import { runInDurableObject } from "cloudflare:test";
import { authHeader, newIdentity, sha256Hex, type Identity, type Reply } from "../client/mailbox-client";
import { BASE, TestSocket, mailboxStub, newId, sendDm, user, worker } from "./helpers";
import { pendingSeqs, dev } from "./device-helpers";
import type { Mailbox } from "../src/mailbox";

/**
 * Regression tests for the WP05-A security review (blockers 1-6 plus the low-severity phone fix).
 * They cover the paths the first implementation left open: sockets that outlive a migration or a key
 * rotation, duplicate device keys, admin keys that collide with device keys, and a send that is
 * authorized just before its device is revoked.
 */

const json = (body: unknown) => JSON.stringify(body);

/** A signed request with an arbitrary key. */
async function request(sk: Uint8Array, method: string, path: string, body?: unknown): Promise<Reply> {
  const url = BASE + path;
  const text = body === undefined ? undefined : json(body);
  const headers: Record<string, string> = { Authorization: await authHeader(sk, method, url, text) };
  if (text !== undefined) headers["content-type"] = "application/json";
  const response = await worker(new Request(url, { method, headers, body: text }));
  const raw = await response.text();
  let parsed: unknown = raw;
  try {
    parsed = raw ? JSON.parse(raw) : null;
  } catch {
    // keep text
  }
  return { status: response.status, body: parsed, headers: response.headers };
}

/** A WebSocket upgrade signed by an arbitrary key. The event signs the wss:// URL the client dials. */
async function upgrade(sk: Uint8Array, device?: string): Promise<Response> {
  const path = device === undefined ? "/v1/ws" : `/v1/ws?device=${device}`;
  const signed = (BASE + path).replace(/^http/, "ws");
  return worker(new Request(BASE + path, {
    headers: { Upgrade: "websocket", Authorization: await authHeader(sk, "GET", signed) },
  }));
}

/** An accepted socket, after its first frame. */
async function socket(sk: Uint8Array, device?: string): Promise<TestSocket> {
  const reply = await upgrade(sk, device);
  if (reply.status !== 101 || !reply.webSocket) throw new Error(`upgrade refused: ${reply.status} ${await reply.text()}`);
  const ws = new TestSocket(reply.webSocket);
  const ready = await ws.next();
  if (ready?.type !== "ready") throw new Error(`expected ready, got ${JSON.stringify(ready)}`);
  return ws;
}

const migrate = (i: number, admin: Identity) =>
  request(user(i).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk });

const enroll = (adminSk: Uint8Array, id: string, auth: Identity, device = "primary") =>
  request(adminSk, "POST", `/v1/devices?device=${device}`, { v: 1, device: id, label: "desktop", auth_pubkey: auth.pk });

const revoke = (adminSk: Uint8Array, id: string) =>
  request(adminSk, "DELETE", `/v1/devices/${id}?device=primary`);

const inbox = (sk: Uint8Array, device?: string) =>
  request(sk, "GET", `/v1/inbox?after=0${device === undefined ? "" : `&device=${device}`}`);

/** Marks the object's in-memory migrated set, as a migration that already committed would. */
async function markMigratedInMemory(i: number): Promise<void> {
  await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
    (instance as any).migrated.add(user(i).pk);
  });
}

const expectClosed = async (ws: TestSocket, code: number) => {
  expect(await ws.closed).toMatchObject({ code });
};

describe("WP05-A hardening: migration closes and disables already-open shared-key sockets", () => {
  it("closes an open shared-key socket as soon as the identity migrates", async () => {
    const ws = await socket(user(1).sk);
    const admin = newIdentity();
    expect((await migrate(1, admin)).status).toBe(200);
    await expectClosed(ws, 4001);
  });

  it("refuses sync on a shared-key socket whose close sweep was missed", async () => {
    const a = await sendDm(0, 1, "waiting");
    const ws = await socket(user(1).sk);
    await markMigratedInMemory(1);
    ws.send({ type: "sync", after: 0, ref: "s1" });
    await expectClosed(ws, 4001);
    // Nothing was paged out and the waiting message is untouched.
    expect(await pendingSeqs(1)).toEqual([a.result.seq]);
  });

  it("refuses ack on a shared-key socket whose close sweep was missed, so nothing is deleted", async () => {
    const a = await sendDm(0, 1, "must survive");
    const ws = await socket(user(1).sk);
    await markMigratedInMemory(1);
    ws.send({ type: "ack", through: a.result.seq, ref: "a1" });
    await expectClosed(ws, 4001);
    expect(await pendingSeqs(1)).toEqual([a.result.seq]);
  });

  it("refuses signal from a shared-key socket marked migrated", async () => {
    const sender = await socket(user(1).sk);
    const recipient = await socket(user(2).sk);
    await markMigratedInMemory(1);
    sender.send({ type: "signal", to: user(2).pk, envelope: "x", ref: "g1" });
    await expectClosed(sender, 4001);
    expect(await recipient.silent(300)).toBe(true);
  });

  it("refuses push to a shared-key socket marked migrated", async () => {
    const ws = await socket(user(1).sk);
    const delivered = await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
      (instance as any).migrated.add(user(1).pk);
      return (instance as any).pushLive(user(1).pk, { seq: 99, from: user(0).pk, id: newId(), envelope: "e", storedAt: Date.now() });
    });
    expect(delivered).toBe(false);
    await expectClosed(ws, 4001);
  });

  it("does not sweep a device-key socket when a later shared-key migration attempt fails", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    expect((await enroll(admin.sk, dev(1), desktop)).status).toBe(200);
    const ws = await socket(desktop.sk, dev(1));
    // The shared key is already disabled, so this attempt is refused and must not close anything.
    expect((await migrate(1, newIdentity())).status).toBe(401);
    expect(await ws.silent(100)).toBe(true);
    ws.send({ type: "sync", after: 0, ref: "s2" });
    expect(await ws.next()).toMatchObject({ type: "batch", ref: "s2" });
  });

  it("keeps an enrolled device socket working after migration", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), desktop);
    const a = await sendDm(0, 1, "after");
    const ws = await socket(desktop.sk, dev(1));
    ws.send({ type: "sync", after: 0, ref: "s3" });
    const batch = await ws.next();
    expect(batch.type).toBe("batch");
    expect(batch.messages.map((m: any) => m.seq)).toEqual([a.result.seq]);
  });
});

describe("WP05-A hardening: device keys are unique and resolve deterministically", () => {
  it("refuses enrolling one key for a second device id with 409", async () => {
    const admin = newIdentity();
    const shared = newIdentity();
    await migrate(1, admin);
    expect((await enroll(admin.sk, dev(1), shared)).status).toBe(200);
    const again = await enroll(admin.sk, dev(2), shared);
    expect(again.status).toBe(409);
    expect(again.body.error.code).toBe("auth_key_in_use");
    // Re-enrolling the same pair is still idempotent.
    expect((await enroll(admin.sk, dev(1), shared)).status).toBe(200);
  });

  it("refuses a key already used by another identity's device", async () => {
    const shared = newIdentity();
    const adminOne = newIdentity();
    const adminTwo = newIdentity();
    await migrate(1, adminOne);
    await migrate(2, adminTwo);
    expect((await enroll(adminOne.sk, dev(1), shared)).status).toBe(200);
    const other = await enroll(adminTwo.sk, dev(1), shared);
    expect(other.status).toBe(409);
    expect(other.body.error.code).toBe("auth_key_in_use");
  });

  it("keeps a UNIQUE index on non-empty device keys", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), newIdentity());
    const indexes = await runInDurableObject(mailboxStub(), async (_: Mailbox, state) =>
      state.storage.sql.exec<{ name: string; sql: string }>("SELECT name, sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'devices'").toArray());
    expect(indexes.some((i) => /unique/i.test(i.sql ?? ""))).toBe(true);
  });

  it("backfills duplicate keys deterministically during migrateDevices()", async () => {
    const duplicate = newIdentity();
    const [low, high] = [user(1).pk, user(2).pk].sort();
    const outcome = await runInDurableObject(mailboxStub(), async (instance: Mailbox, state) => {
      // Simulate a database written by the pre-fix schema: no index, two rows sharing one key.
      state.storage.sql.exec("DROP INDEX IF EXISTS devices_auth_pubkey");
      state.storage.sql.exec("UPDATE meta SET value = '2' WHERE key = 'devices_schema'");
      for (const [pubkey, device] of [[high, dev(2)], [low, dev(1)]]) {
        state.storage.sql.exec(
          "INSERT INTO devices (pubkey, device, label, acked, last_seen, added_at, auth_pubkey, protocol_version, revoked_at) VALUES (?, ?, '', 0, 0, 0, ?, 1, 0)",
          pubkey, device, duplicate.pk,
        );
      }
      (instance as any).migrateDevices();
      const rows = state.storage.sql.exec<{ pubkey: string; auth_pubkey: string }>(
        "SELECT pubkey, auth_pubkey FROM devices WHERE auth_pubkey = ?", duplicate.pk,
      ).toArray();
      const resolved = (instance as any).resolveSigner(duplicate.pk, dev(1));
      return { rows, resolved };
    });
    // Exactly one row keeps the key, and it is the lexicographically first (pubkey, device).
    expect(outcome.rows).toEqual([{ pubkey: low, auth_pubkey: duplicate.pk }]);
    expect(outcome.resolved.identity).toBe(low);
    expect(outcome.resolved.device).toBe(dev(1));
  });

  it("resolves a key to exactly its own identity and device", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), desktop);
    const reply = await inbox(desktop.sk, dev(1));
    expect(reply.status).toBe(200);
    const wrongIdentity = await inbox(desktop.sk, dev(2));
    expect(wrongIdentity.status).toBe(403);
    expect(wrongIdentity.body.error.code).toBe("unknown_device");
  });
});

describe("WP05-A hardening: an admin key cannot collide with a device key", () => {
  it("refuses an admin key already enrolled as a device key", async () => {
    const adminOne = newIdentity();
    const deviceKey = newIdentity();
    await migrate(1, adminOne);
    await enroll(adminOne.sk, dev(1), deviceKey);
    const reply = await migrate(2, deviceKey);
    expect(reply.status).toBe(409);
    expect(reply.body.error.code).toBe("admin_key_in_use");
  });

  it("refuses an admin key already bound to another identity", async () => {
    const first = newIdentity();
    await migrate(1, first);
    const second = await migrate(2, first);
    expect(second.status).toBe(409);
    expect(second.body.error.code).toBe("admin_key_in_use");
  });
});

describe("WP05-A hardening: key rotation and revocation", () => {
  it("closes the device's socket when its key is rotated, and the old key stops working", async () => {
    const admin = newIdentity();
    const oldKey = newIdentity();
    const newKey = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), oldKey);
    const ws = await socket(oldKey.sk, dev(1));
    expect((await enroll(admin.sk, dev(1), newKey)).status).toBe(200);
    await expectClosed(ws, 4001);
    expect((await inbox(oldKey.sk, dev(1))).status).not.toBe(200);
    expect((await inbox(newKey.sk, dev(1))).status).toBe(200);
  });

  it("refuses a send whose device was revoked between authorization and storage", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), desktop);
    const self = user(1);
    const body = json({ v: 1, deliveries: [{ to: user(2).pk, id: newId(), envelope: "envelope" }] });
    const url = `${BASE}/v1/send?device=${dev(1)}`;
    const authorization = await authHeader(desktop.sk, "POST", url, body);
    const bytes = new TextEncoder().encode(body);

    const result = await runInDurableObject(mailboxStub(), async (instance: Mailbox, state) => {
      // Authorize while the device is still active, then revoke it before the storage transaction runs.
      const draft = new Request(url, { method: "POST", headers: { "content-type": "application/json", Authorization: authorization }, body });
      const authorized = await (instance as any).authorize(draft, bytes.buffer as ArrayBuffer, new URL(url));
      state.storage.sql.exec("UPDATE devices SET revoked_at = ? WHERE pubkey = ? AND device = ?", Date.now(), self.pk, dev(1));
      const parsed = [{ to: user(2).pk, id: "race-1", envelope: "envelope", size: 8 }];
      const hashes = [await sha256Hex("envelope")];
      return (instance as any).sendStored(authorized, parsed, hashes, Date.now()) as Response;
    });
    expect(result.status).toBe(401);
    expect((await result.json() as any).error.code).toBe("auth_revoked");
    expect(await pendingSeqs(2)).toEqual([]);
    expect((await revoke(admin.sk, dev(1))).status).toBe(404);
  });
});

describe("WP05-A hardening: migration of a previously unlinked identity", () => {
  it("migrates, acks as the admin primary and then enrolls a device", async () => {
    const admin = newIdentity();
    const a = await sendDm(0, 1, "before migration");
    expect((await migrate(1, admin)).status).toBe(200);
    const ack = await request(admin.sk, "POST", "/v1/ack?device=primary", { v: 1, through: a.result.seq });
    expect(ack.status).toBe(200);
    expect(ack.body.acked).toBe(1);
    expect(await pendingSeqs(1)).toEqual([]);
    const desktop = newIdentity();
    expect((await enroll(admin.sk, dev(1), desktop)).status).toBe(200);
    expect((await inbox(desktop.sk, dev(1))).status).toBe(200);
  });

  it("refuses media GET and DELETE with the shared key after migration", async () => {
    const bytes = new TextEncoder().encode("hello media");
    const hash = await sha256Hex(bytes);
    const putUrl = `${BASE}/v1/media/${hash}?device=primary`;
    const put = await worker(new Request(putUrl, {
      method: "PUT",
      headers: { Authorization: await authHeader(user(1).sk, "PUT", putUrl, bytes), "x-relay-recipients": user(1).pk },
      body: bytes,
    }));
    expect(put.status).toBe(200);
    const admin = newIdentity();
    expect((await migrate(1, admin)).status).toBe(200);
    const getUrl = `${BASE}/v1/media/${hash}?device=primary`;
    const get = await worker(new Request(getUrl, { headers: { Authorization: await authHeader(user(1).sk, "GET", getUrl) } }));
    expect(get.status).toBe(401);
    expect((await get.json() as any).error.code).toBe("auth_invalid");
    const delUrl = `${BASE}/v1/media/${hash}?device=primary`;
    const del = await worker(new Request(delUrl, { method: "DELETE", headers: { Authorization: await authHeader(user(1).sk, "DELETE", delUrl) } }));
    expect(del.status).toBe(401);
  });
});
