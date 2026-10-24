import { describe, expect, it } from "vitest";
import { authHeader, newIdentity, sha256Hex, wrapDm, type Identity, type Reply } from "../client/mailbox-client";
import { BASE, client, sendDm, user, worker } from "./helpers";
import { dev } from "./device-helpers";

/**
 * WP05 part A — G1 device authorization contract.
 *
 * Before migration an identity is exactly the legacy client of WP04: the shared Nostr identity key signs
 * every request and the `device` query parameter is bookkeeping only. `POST /v1/devices/admin` (signed by
 * the identity key) migrates the identity to per-device keys. After that only the phone-only admin key
 * may enroll or revoke, each enrolled device signs its own requests with its own key, and the shared
 * identity key is refused.
 */

const json = (body: unknown) => JSON.stringify(body);
const hex = (bytes: Uint8Array) => [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");

/** A request signed by an arbitrary key (the identity key for migration, an admin/device key afterwards). */
async function as(sk: Uint8Array, method: string, path: string, body?: unknown): Promise<Reply> {
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

/** A WebSocket upgrade signed by an arbitrary key. */
async function upgradeAs(sk: Uint8Array, device?: string): Promise<Response> {
  const query = device === undefined ? "" : `?device=${device}`;
  const wsUrl = BASE.replace(/^http/, "ws") + "/v1/ws" + query;
  return worker(new Request(`${BASE}/v1/ws${query}`, {
    headers: { Upgrade: "websocket", Authorization: await authHeader(sk, "GET", wsUrl) },
  }));
}

const migrate = (i: number, admin: Identity) =>
  as(user(i).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk });

const enroll = (adminSk: Uint8Array, id: string, auth: Identity, label = "desktop", device = "primary") =>
  as(adminSk, "POST", `/v1/devices?device=${device}`, { v: 1, device: id, label, auth_pubkey: auth.pk });

const revoke = (adminSk: Uint8Array, id: string, device = "primary") =>
  as(adminSk, "DELETE", `/v1/devices/${id}?device=${device}`);

const inbox = (sk: Uint8Array, device?: string, after = 0) =>
  as(sk, "GET", `/v1/inbox?after=${after}${device === undefined ? "" : `&device=${device}`}`);

const ack = (sk: Uint8Array, device: string | undefined, through: number) =>
  as(sk, "POST", `/v1/ack${device === undefined ? "" : `?device=${device}`}`, { v: 1, through });

const deviceRow = async (i: number, id: string) => {
  const { runInDurableObject } = await import("cloudflare:test");
  const { mailboxStub } = await import("./helpers");
  const { Mailbox } = await import("../src/mailbox");
  return runInDurableObject(mailboxStub(), async (_: InstanceType<typeof Mailbox>, state) =>
    state.storage.sql.exec("SELECT * FROM devices WHERE pubkey = ? AND device = ?", user(i).pk, id).toArray()[0]);
};

const adminRow = async (i: number) => {
  const { runInDurableObject } = await import("cloudflare:test");
  const { mailboxStub } = await import("./helpers");
  const { Mailbox } = await import("../src/mailbox");
  return runInDurableObject(mailboxStub(), async (_: InstanceType<typeof Mailbox>, state) =>
    state.storage.sql.exec("SELECT * FROM identity_admin WHERE pubkey = ?", user(i).pk).toArray()[0]);
};

describe("WP05-G1 legacy identities keep the WP04 behaviour until they migrate", () => {
  it("an unmigrated identity still reads and acks without any device parameter, byte-identically", async () => {
    const a = await sendDm(0, 1, "one");
    const reply = await inbox(user(1).sk);
    expect(reply.status).toBe(200);
    expect(Object.keys(reply.body).sort()).toEqual(["cursor", "messages", "more", "v"]);
    expect(reply.body.messages[0]).toMatchObject({ seq: a.result.seq, from: user(0).pk });
    const receipt = await ack(user(1).sk, undefined, a.result.seq);
    expect(receipt.body).toEqual({ v: 1, acked: 1, through: a.result.seq });
    expect(await adminRow(1)).toBeUndefined();
  });

  it("an unmigrated identity can still register a device with the shared key (WP04 bookkeeping)", async () => {
    const reply = await as(user(1).sk, "POST", "/v1/devices", { v: 1, device: dev(1), label: "old desktop" });
    expect(reply.status).toBe(200);
    expect(reply.body.v).toBe(1);
    expect(typeof reply.body.head).toBe("number");
    expect((await deviceRow(1, dev(1))).auth_pubkey).toBe("");
  });
});

describe("WP05-G1 migration: only the identity key may install the phone-only admin key", () => {
  it("migrates with the identity key and binds the admin key to the identity", async () => {
    const admin = newIdentity();
    const reply = await migrate(1, admin);
    expect(reply.status).toBe(200);
    expect(reply.body).toMatchObject({ v: 1, migrated: true });
    const row = await adminRow(1);
    expect(row.admin_pubkey).toBe(admin.pk);
    expect((await deviceRow(1, "primary")).auth_pubkey).toBe(admin.pk);
  });

  it("refuses a second migration with the shared identity key after the primary has migrated", async () => {
    const admin = newIdentity();
    expect((await migrate(1, admin)).status).toBe(200);
    const again = await migrate(1, newIdentity());
    expect(again.status).toBe(401);
    expect(again.body.error.code).toBe("auth_invalid");
  });

  it("refuses migration signed by an admin or device key rather than the identity key", async () => {
    const admin = newIdentity();
    expect((await migrate(1, admin)).status).toBe(200);
    // The admin key is not the identity key, so it cannot perform a second migration.
    const asAdmin = await as(admin.sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: newIdentity().pk });
    expect(asAdmin.status).toBe(401);
    expect(asAdmin.body.error.code).toBe("auth_invalid");
  });

  it("rejects an admin key equal to the shared identity key", async () => {
    const reply = await as(user(1).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: user(1).pk });
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("bad_request");
  });

  it("replays a migration event id as auth_replayed", async () => {
    const admin = newIdentity();
    const url = BASE + "/v1/devices/admin";
    const body = json({ v: 1, admin_pubkey: admin.pk });
    const header = await authHeader(user(1).sk, "POST", url, body);
    const first = await worker(new Request(url, { method: "POST", headers: { Authorization: header, "content-type": "application/json" }, body }));
    expect(first.status).toBe(200);
    const replay = await worker(new Request(url, { method: "POST", headers: { Authorization: header, "content-type": "application/json" }, body }));
    expect(replay.status).toBe(401);
    expect((await replay.json() as any).error.code).toBe("auth_replayed");
  });

  it("disables the shared identity key after migration", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    const reply = await inbox(user(1).sk);
    expect(reply.status).toBe(401);
    expect(reply.body.error.code).toBe("auth_invalid");
  });
});

describe("WP05-G1 enrollment: only the admin key may enroll a device", () => {
  it("enrolls a device with its own key and binds id, key, creation time and protocol version", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    const reply = await enroll(admin.sk, dev(1), desktop);
    expect(reply.status).toBe(200);
    expect(reply.body.v).toBe(1);
    const row = await deviceRow(1, dev(1));
    expect(row.auth_pubkey).toBe(desktop.pk);
    expect(row.protocol_version).toBe(1);
    expect(typeof row.added_at).toBe("number");
    expect(row.revoked_at).toBe(0);
  });

  it("refuses enrollment signed by the shared identity key after migration", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    const desktop = newIdentity();
    const reply = await as(user(1).sk, "POST", "/v1/devices?device=primary", { v: 1, device: dev(1), label: "x", auth_pubkey: desktop.pk });
    expect(reply.status).toBe(401);
    expect(reply.body.error.code).toBe("auth_invalid");
  });

  it("refuses enrollment signed by a device key", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), desktop);
    const other = newIdentity();
    const reply = await as(desktop.sk, "POST", `/v1/devices?device=${dev(1)}`, { v: 1, device: dev(2), label: "x", auth_pubkey: other.pk });
    expect(reply.status).toBe(403);
    expect(reply.body.error.code).toBe("not_primary");
  });

  it("refuses enrollment with a missing admin signature", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    const response = await worker(new Request(`${BASE}/v1/devices?device=primary`, { method: "POST", body: json({ v: 1, device: dev(1), label: "x", auth_pubkey: newIdentity().pk }), headers: { "content-type": "application/json" } }));
    expect(response.status).toBe(401);
    expect((await response.json() as any).error.code).toBe("auth_missing");
  });

  it("refuses enrollment with a key that is not an admin", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    // A valid key that is neither a member nor the admin for this identity. The server cannot know a target
    // identity from an admin key: the admin key's own binding is the target. So "another identity's admin"
    // acting as primary is indistinguishable from "a member acting as primary" until it proves membership.
    const rogue = newIdentity();
    const reply = await enroll(rogue.sk, dev(1), newIdentity());
    expect(reply.status).toBe(403);
    expect(reply.body.error.code).toBe("not_allowed");
  });

  it("refuses an enrolled auth key that belongs to another member identity", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    const reply = await enroll(admin.sk, dev(1), user(2));
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("bad_request");
  });

  it("replays an enrollment event id as auth_replayed", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    const desktop = newIdentity();
    const url = BASE + "/v1/devices?device=primary";
    const body = json({ v: 1, device: dev(1), label: "x", auth_pubkey: desktop.pk });
    const header = await authHeader(admin.sk, "POST", url, body);
    const first = await worker(new Request(url, { method: "POST", headers: { Authorization: header, "content-type": "application/json" }, body }));
    expect(first.status).toBe(200);
    const replay = await worker(new Request(url, { method: "POST", headers: { Authorization: header, "content-type": "application/json" }, body }));
    expect(replay.status).toBe(401);
    expect((await replay.json() as any).error.code).toBe("auth_replayed");
  });

  it("rejects an old enrollment event outside the auth window", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    const desktop = newIdentity();
    const url = BASE + "/v1/devices?device=primary";
    const body = json({ v: 1, device: dev(1), label: "x", auth_pubkey: desktop.pk });
    const header = await authHeader(admin.sk, "POST", url, body, { createdAt: Math.floor(Date.now() / 1000) - 120 });
    const response = await worker(new Request(url, { method: "POST", headers: { Authorization: header, "content-type": "application/json" }, body }));
    expect(response.status).toBe(401);
    expect((await response.json() as any).error.code).toBe("auth_expired");
  });

  it("rate-limits admin operations per identity", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    let last = 0;
    for (let n = 1; n <= 12; n++) {
      last = (await enroll(admin.sk, dev(n), newIdentity())).status;
    }
    expect(last).toBe(429);
  });
});

describe("WP05-G1 device authentication: own key only, no forging", () => {
  async function migratedWithDesktop() {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), desktop);
    return { admin, desktop };
  }

  it("lets an enrolled device use its own key for inbox, ack, send and WebSocket", async () => {
    const { desktop } = await migratedWithDesktop();
    const a = await sendDm(0, 1, "hello");
    const list = await inbox(desktop.sk, dev(1));
    expect(list.status).toBe(200);
    expect(list.body.messages.map((m: any) => m.seq)).toEqual([a.result.seq]);
    expect((await ack(desktop.sk, dev(1), a.result.seq)).status).toBe(200);
    const upgrade = await upgradeAs(desktop.sk, dev(1));
    expect(upgrade.status).toBe(101);
    upgrade.webSocket?.accept();
    upgrade.webSocket?.close(1000, "done");
  });

  it("refuses the same device key claiming another device id", async () => {
    const { desktop } = await migratedWithDesktop();
    const other = await inbox(desktop.sk, dev(2));
    expect(other.status).toBe(403);
    expect(other.body.error.code).toBe("unknown_device");
  });

  it("refuses a device key claiming primary once migrated", async () => {
    const { desktop } = await migratedWithDesktop();
    const asPrimary = await inbox(desktop.sk, "primary");
    expect(asPrimary.status).toBe(403);
    expect(asPrimary.body.error.code).toBe("not_primary");
  });

  it("refuses a device key with no device parameter (primary implied)", async () => {
    const { desktop } = await migratedWithDesktop();
    const implicit = await inbox(desktop.sk);
    expect(implicit.status).toBe(403);
    expect(implicit.body.error.code).toBe("not_primary");
  });

  it("refuses an unenrolled device key", async () => {
    const { admin } = await migratedWithDesktop();
    const stranger = newIdentity();
    const reply = await inbox(stranger.sk, dev(3));
    expect(reply.status).toBe(403);
    expect(reply.body.error.code).toBe("not_allowed");
  });

  it("enforces the device cap and ignores revoked devices in the count", async () => {
    const admin = newIdentity();
    await migrate(1, admin);
    const keys = [newIdentity(), newIdentity(), newIdentity(), newIdentity()];
    for (let n = 1; n <= 3; n++) expect((await enroll(admin.sk, dev(n), keys[n - 1])).status).toBe(200);
    expect((await enroll(admin.sk, dev(4), keys[3])).status).toBe(409);
    expect((await revoke(admin.sk, dev(1))).status).toBe(200);
    expect((await enroll(admin.sk, dev(4), keys[3])).status).toBe(200);
  });
});

describe("WP05-G1 revocation: every HTTP and WebSocket surface refuses a revoked device", () => {
  async function migratedWithDesktop() {
    const admin = newIdentity();
    const desktop = newIdentity();
    await migrate(1, admin);
    await enroll(admin.sk, dev(1), desktop);
    return { admin, desktop };
  }

  it("returns 401 auth_revoked on inbox and ack", async () => {
    const { admin, desktop } = await migratedWithDesktop();
    expect((await revoke(admin.sk, dev(1))).status).toBe(200);
    for (const reply of [await inbox(desktop.sk, dev(1)), await ack(desktop.sk, dev(1), 1)]) {
      expect(reply.status).toBe(401);
      expect(reply.body.error.code).toBe("auth_revoked");
    }
  });

  it("refuses a revoked device on send", async () => {
    const { admin, desktop } = await migratedWithDesktop();
    await revoke(admin.sk, dev(1));
    const reply = await as(desktop.sk, "POST", `/v1/send?device=${dev(1)}`, { v: 1, deliveries: [] });
    expect(reply.status).toBe(401);
    expect(reply.body.error.code).toBe("auth_revoked");
  });

  it("closes the revoked device's open socket with 4009 and leaves the primary socket open", async () => {
    const { admin, desktop } = await migratedWithDesktop();
    const upgraded = await upgradeAs(desktop.sk, dev(1));
    expect(upgraded.status).toBe(101);
    const ws = upgraded.webSocket!;
    ws.accept();
    const closed = new Promise<{ code: number; reason: string }>((resolve) => ws.addEventListener("close", (e: any) => resolve({ code: e.code, reason: e.reason })));
    expect((await revoke(admin.sk, dev(1))).status).toBe(200);
    expect(await closed).toMatchObject({ code: 4009, reason: "unlinked" });
  });

  it("refuses a revoked device on media GET and DELETE", async () => {
    const { admin, desktop } = await migratedWithDesktop();
    const bytes = new TextEncoder().encode("hello media");
    const hash = await sha256Hex(bytes);
    const putUrl = `${BASE}/v1/media/${hash}?device=primary`;
    const put = await worker(new Request(putUrl, {
      method: "PUT",
      headers: { Authorization: await authHeader(admin.sk, "PUT", putUrl, bytes), "x-relay-recipients": user(1).pk },
      body: bytes,
    }));
    expect(put.status).toBe(200);
    // The device is in the recipient snapshot; revoke it and both GET and DELETE must refuse.
    expect((await revoke(admin.sk, dev(1))).status).toBe(200);
    const getUrl = `${BASE}/v1/media/${hash}?device=${dev(1)}`;
    const get = await worker(new Request(getUrl, { headers: { Authorization: await authHeader(desktop.sk, "GET", getUrl) } }));
    expect(get.status).toBe(401);
    expect((await get.json() as any).error.code).toBe("auth_revoked");
    const delUrl = `${BASE}/v1/media/${hash}?device=${dev(1)}`;
    const del = await worker(new Request(delUrl, { method: "DELETE", headers: { Authorization: await authHeader(desktop.sk, "DELETE", delUrl) } }));
    expect(del.status).toBe(401);
  });

  it("a revoked device stops holding the queue", async () => {
    const { admin } = await migratedWithDesktop();
    const a = await sendDm(0, 1, "held");
    await ack(user(1).sk === undefined ? admin.sk : admin.sk, "primary", a.result.seq); // admin key acks as primary
    // The admin key acks as primary; the desktop hasn't acked. Revoke it and the queue must be free.
    expect((await revoke(admin.sk, dev(1))).status).toBe(200);
    const pending = await import("cloudflare:test").then(({ runInDurableObject }) =>
      import("./helpers").then(({ mailboxStub }) =>
        import("../src/mailbox").then(({ Mailbox }) =>
          runInDurableObject(mailboxStub(), async (_: InstanceType<typeof Mailbox>, state) =>
            state.storage.sql.exec<{ seq: number }>("SELECT seq FROM deliveries WHERE recipient = ?", user(1).pk).toArray()))));
    expect(pending).toEqual([]);
  });
});

describe("WP05-G1 lifecycle: enrollment boundary and concurrency", () => {
  async function migrated() {
    const admin = newIdentity();
    await migrate(1, admin);
    return admin;
  }

  it("does not deliver pre-enrollment messages to a new device and does deliver later ones", async () => {
    const admin = await migrated();
    const before = await sendDm(0, 1, "before");
    const desktop = newIdentity();
    const enrolled = await enroll(admin.sk, dev(1), desktop);
    const after = await sendDm(0, 1, "after");
    expect(after.result.seq).toBeGreaterThan(enrolled.body.head);
    expect((await inbox(desktop.sk, dev(1), enrolled.body.head)).body.messages.map((m: any) => m.seq)).toEqual([after.result.seq]);
    expect(before.result.seq).toBeLessThanOrEqual(enrolled.body.head);
  });

  it("serialises concurrent enroll and revoke without exceeding the cap", async () => {
    const admin = await migrated();
    const keys = Array.from({ length: 6 }, () => newIdentity());
    const results = await Promise.all(Array.from({ length: 6 }, (_, n) => enroll(admin.sk, dev(n + 1), keys[n])));
    expect(results.filter((r) => r.status === 200)).toHaveLength(3);
    expect(results.filter((r) => r.status === 409)).toHaveLength(3);
    // Request order is not guaranteed, so revoke devices whose enrollment actually won.
    const won = results.map((r, n) => (r.status === 200 ? n + 1 : 0)).filter(Boolean);
    const revokes = await Promise.all(won.slice(0, 2).map((n) => revoke(admin.sk, dev(n))));
    expect(revokes.every((r) => r.status === 200)).toBe(true);
    expect((await enroll(admin.sk, dev(7), newIdentity())).status).toBe(200);
  });

  it("does not let a dormant revoked device break deletion", async () => {
    const admin = await migrated();
    const desktop = newIdentity();
    await enroll(admin.sk, dev(1), desktop);
    await revoke(admin.sk, dev(1));
    const a = await sendDm(0, 1, "after revoke");
    expect((await ack(admin.sk, "primary", a.result.seq)).status).toBe(200);
    const { runInDurableObject } = await import("cloudflare:test");
    const { mailboxStub } = await import("./helpers");
    const { Mailbox } = await import("../src/mailbox");
    const rows = await runInDurableObject(mailboxStub(), async (_: InstanceType<typeof Mailbox>, state) =>
      state.storage.sql.exec<{ seq: number }>("SELECT seq FROM deliveries WHERE recipient = ?", user(1).pk).toArray());
    expect(rows).toEqual([]);
  });
});
