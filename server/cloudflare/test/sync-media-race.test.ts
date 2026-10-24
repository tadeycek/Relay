import { runInDurableObject, runDurableObjectAlarm } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { authHeader, newIdentity } from "../client/mailbox-client";
import { BASE, worker } from "./helpers";
import { client, user, mailboxStub } from "./helpers";
import { dev, registerDevice, getMediaAs, confirmMediaAs, removeDevice, mediaStub, sha256Hex } from "./device-helpers";
import type { Mailbox } from "../src/mailbox";

const event = async (media?: unknown, id = "abcdef0123456789", parts = 1, part = 0) => ({
  v: 1, id, rev: 1, part, parts, total: 2, hash: await sha256Hex(new Uint8Array([1, 2])),
  expires: Date.now() + 60_000, data: btoa(parts === 1 ? "\x01\x02" : part === 0 ? "\x01" : "\x02"),
  ...(media === undefined ? {} : { media }),
});
const post = async (media?: unknown, id?: string, parts?: number, part?: number) =>
  client(0).request("POST", "/v1/sync", await event(media, id, parts, part));
const put = (own = true, from = 0) => client(from).putMedia(new Uint8Array([31, 42, 53]), own ? [user(0).pk, user(1).pk] : [user(1).pk]);
const outbox = () => runInDurableObject(mailboxStub(), async (_: Mailbox, s) => {
  if (!s.storage.sql.exec("SELECT 1 FROM sqlite_master WHERE name='media_grants'").toArray().length) return -1;
  return s.storage.sql.exec("SELECT COUNT(*) AS n FROM media_grants").one().n;
});

describe("WP09 own SENT media publication", () => {
  it("PUT -> origin confirms -> enrol -> SENT gives the new own device blob access", async () => {
    const p = await put();
    expect((await confirmMediaAs(0, p.hash)).status).toBe(200);
    await registerDevice(0, dev(1));
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(404);
    const r = await post([p.hash]);
    expect(r.body.complete).toBe(true);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(200);
    expect(r.body.media).toEqual([{ hash: p.hash, status: "attached" }]);
    console.log("WP09-SYNC-MEDIA-ROWS", r.headers.get("x-relay-sql-rows"), r.headers.get("x-relay-media-rows"));
    expect(await outbox()).toBe(0);
    await confirmMediaAs(1, p.hash);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(200);
    const alarm = await runInDurableObject(mediaStub(p.hash), async (_, s) => s.storage.getAlarm());
    expect(alarm).not.toBeNull();
    expect((await confirmMediaAs(0, p.hash, dev(1))).body.remaining).toBe(0);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(404);
  });

  it.each([false, true])("refuses unavailable ownership/own-recipient (%s)", async (foreign) => {
    const p = await put(!foreign ? false : true, foreign ? 1 : 0);
    const r = await post([p.hash]);
    expect(r.status).toBe(409);
    expect(r.body.error.code).toBe("media_unavailable");
    expect((await client(0).request("GET", "/v1/sync?after=0")).body.events).toHaveLength(0);
    expect(await outbox()).toBe(-1);
  });

  it.each([null, "bad", ["A".repeat(64)], ["a".repeat(64), "a".repeat(64)], [5], Array.from({length:9}, (_, i) => i.toString(16).padStart(64,"0"))].map(media => ({media})))("rejects invalid/capped media $media", async ({media}) => {
    const r = await post(media);
    expect(r.status).toBe(400);
    expect(r.body.error.code).toBe(Array.isArray(media) && media.length > 8 ? "too_many_media" : "bad_request");
    expect(await outbox()).toBe(-1);
  });

  it("partial publication grants nothing; completing part snapshots devices enrolled in between", async () => {
    const p = await put();
    await registerDevice(0, dev(1));
    expect((await post([p.hash], undefined, 2, 0)).body.complete).toBe(false);
    expect(await outbox()).toBe(-1);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(404);
    await registerDevice(0, dev(2));
    const r = await post([p.hash], undefined, 2, 1);
    expect(r.body.complete).toBe(true);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(200);
    expect((await getMediaAs(0, p.hash, dev(2))).status).toBe(200);
  });

  it("bad assembled hash cannot grant", async () => {
    const p = await put();
    await registerDevice(0, dev(1));
    const e = await event([p.hash]); e.hash = "0".repeat(64);
    expect((await client(0).request("POST", "/v1/sync", e)).status).toBe(400);
    expect(await outbox()).toBe(-1);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(404);
  });

  it("late enrollee can backfill SENT but duplicate retry never widens blob access", async () => {
    const p = await put();
    await post([p.hash]);
    await registerDevice(0, dev(1));
    expect((await client(0).request("GET", `/v1/sync?device=${dev(1)}&after=0`)).body.events).toHaveLength(1);
    const r = await post([p.hash]);
    expect(r.body.duplicate).toBe(true);
    expect(r.body.media).toEqual([{hash:p.hash,status:"unchanged"}]);
    for (const refs of [[], undefined]) {
      const duplicate = await post(refs);
      expect(duplicate.body.complete).toBe(true);
      expect(duplicate.body.duplicate).toBe(true);
      expect(duplicate.body.cursor).toBe(r.body.cursor);
      expect(duplicate.body.media).toBeUndefined();
    }
    expect((await client(0).request("GET", "/v1/sync?after=0")).body.events).toHaveLength(1);
    expect(await outbox()).toBe(0);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(404);
  });

  it("revoked at publication is not added", async () => {
    const p = await put();
    await registerDevice(0, dev(1)); await removeDevice(0, dev(1));
    await post([p.hash]);
    expect([401,403]).toContain((await getMediaAs(0, p.hash, dev(1))).status);
    const pairs = await runInDurableObject(mediaStub(p.hash), async (_, s) => s.storage.sql.exec("SELECT device FROM recipients").toArray());
    expect(pairs.map(p => p.device)).not.toContain(dev(1));
  });

  it("expired blob refuses publication; repeat of held completing part can recover after reupload", async () => {
    const p = await put();
    await runInDurableObject(mediaStub(p.hash), async (_, s) => s.storage.sql.exec("UPDATE info SET stored_at=0"));
    expect((await post([p.hash])).status).toBe(409);
    await runDurableObjectAlarm(mediaStub(p.hash));
    await put();
    expect((await post([p.hash])).body.complete).toBe(true);
  });

  it.each([{ media: [], label: "empty" }, { media: undefined, label: "missing" }])(
    "held completion can drop unavailable optional media with $label refs",
    async ({ media }) => {
      const part = await event(["f".repeat(64)]);
      const first = await client(0).request("POST", "/v1/sync", part);
      expect(first.status).toBe(409);
      expect(first.body.error.code).toBe("media_unavailable");
      expect((await client(0).request("GET", "/v1/sync?after=0")).body.events).toHaveLength(0);
      const { media: _refs, ...bytes } = part;
      const corrected = { ...bytes, ...(media === undefined ? {} : { media }) };
      const retry = await client(0).request("POST", "/v1/sync", corrected);
      expect(retry.status).toBe(200);
      expect(retry.body.complete).toBe(true);
      expect(retry.body.media).toBeUndefined();
      expect(retry.headers.get("x-relay-media-rows")).toBeNull();
      expect(await outbox()).toBe(-1);
      const duplicate = await client(0).request("POST", "/v1/sync", corrected);
      expect(duplicate.body).toEqual({ v: 1, id: part.id, rev: 1, complete: true, duplicate: true, cursor: retry.body.cursor });
      const listed = await client(0).request("GET", "/v1/sync?after=0");
      expect(listed.body.events).toHaveLength(1);
      expect(listed.body.events[0].data).toBe(part.data);
    },
  );

  it("identical incomplete legacy part stays incomplete until the missing part arrives", async () => {
    const part = await event(undefined, undefined, 2, 0);
    const first = await client(0).request("POST", "/v1/sync", part);
    const duplicate = await client(0).request("POST", "/v1/sync", part);
    expect(duplicate.status).toBe(200);
    expect(duplicate.body).toEqual(first.body);
    expect(duplicate.body.complete).toBe(false);
    expect(duplicate.body.duplicate).toBe(true);
    expect((await client(0).request("GET", "/v1/sync?after=0")).body.events).toHaveLength(0);
    expect((await post(undefined, undefined, 2, 1)).body.complete).toBe(true);
    expect((await client(0).request("GET", "/v1/sync?after=0")).body.events).toHaveLength(1);
    expect(await outbox()).toBe(-1);
  });

  it("grant RPC failure leaves durable snapshot and alarm retry adds no later enrollee", async () => {
    const p = await put(); await registerDevice(0, dev(1));
    await runInDurableObject(mailboxStub(), async (m: Mailbox) => {
      const original = (m as any).blob.bind(m);
      (m as any).blob = (h: string) => ({ attachCheck: (...a: any[]) => original(h).attachCheck(...a), grant: () => {throw new Error("offline");} });
    });
    const r = await post([p.hash]);
    expect(r.body.media).toEqual([{hash:p.hash,status:"pending"}]);
    expect(await outbox()).toBe(1);
    await registerDevice(0, dev(2));
    const duplicate = await post([p.hash]);
    expect(duplicate.body.duplicate).toBe(true);
    expect(duplicate.body.media).toEqual([{hash:p.hash,status:"unchanged"}]);
    expect(await outbox()).toBe(1);
    await runInDurableObject(mailboxStub(), async (m: Mailbox, s) => {
      expect(await s.storage.getAlarm()).not.toBeNull();
      delete (m as any).blob;
    });
    expect(await runDurableObjectAlarm(mailboxStub())).toBe(true);
    expect(await outbox()).toBe(0);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(200);
    expect((await getMediaAs(0, p.hash, dev(2))).status).toBe(404);
  });

  it("confirmation/lifetime gap reports gone without resurrecting a blob", async () => {
    const p = await put(); await registerDevice(0, dev(1));
    await runInDurableObject(mailboxStub(), async (m: Mailbox) => {
      const original = (m as any).blob.bind(m);
      (m as any).blob = (h: string) => ({ attachCheck: async (...a: any[]) => {
        const ok = await original(h).attachCheck(...a);
        await confirmMediaAs(0, h); await confirmMediaAs(1, h); return ok;
      }, grant: (...a: any[]) => original(h).grant(...a) });
    });
    const r = await post([p.hash]);
    await runInDurableObject(mailboxStub(), async (m: Mailbox) => {delete (m as any).blob;});
    expect(r.body.complete).toBe(true);
    expect(r.body.media).toEqual([{hash:p.hash,status:"gone"}]);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(404);
    expect(await outbox()).toBe(0);
  });

  it("revoked/re-enrolled stale publishing key cannot complete after ownership RPC yields", async () => {
    const p = await put();
    const admin = newIdentity(), old = newIdentity(), fresh = newIdentity();
    const request = async (sk: Uint8Array, method: string, path: string, payload: unknown) => {
      const body = new TextEncoder().encode(JSON.stringify(payload));
      return worker(new Request(BASE + path, {method, body, headers: {"content-type":"application/json", Authorization: await authHeader(sk, method, BASE + path, body)}}));
    };
    expect((await client(0).request("POST", "/v1/devices/admin", {v:1,admin_pubkey:admin.pk})).status).toBe(200);
    const enrol = (key: string) => request(admin.sk, "POST", "/v1/devices?device=primary", {v:1,device:dev(1),label:"test",auth_pubkey:key});
    expect((await enrol(old.pk)).status).toBe(200);
    await runInDurableObject(mailboxStub(), async (m: Mailbox) => {
      const original = (m as any).blob.bind(m);
      (m as any).blob = (h: string) => ({attachCheck: async (...a: any[]) => {
        const ok = await original(h).attachCheck(...a);
        // Rotate by re-enrolment while the ownership check is suspended.
        await request(admin.sk, "DELETE", `/v1/devices/${dev(1)}?device=primary`, {});
        await enrol(fresh.pk); return ok;
      }, grant:(...a:any[]) => original(h).grant(...a)});
    });
    const response = await request(old.sk, "POST", `/v1/sync?device=${dev(1)}`, await event([p.hash]));
    await runInDurableObject(mailboxStub(), async (m: Mailbox) => {delete (m as any).blob;});
    expect(response.status).toBe(401);
    const url = `${BASE}/v1/media/${p.hash}?device=${dev(1)}`;
    expect([401, 403]).toContain((await worker(new Request(url, {headers:{Authorization:await authHeader(old.sk,"GET",url)}}))).status);
  });

  it("event expiring during ownership RPC cannot publish or grant", async () => {
    const p = await put(); await registerDevice(0, dev(1));
    await runInDurableObject(mailboxStub(), async (m: Mailbox) => {
      const original = (m as any).blob.bind(m);
      (m as any).blob = (h: string) => ({attachCheck: async (...a:any[]) => {
        const ok = await original(h).attachCheck(...a);
        await new Promise(resolve => setTimeout(resolve, 300)); return ok;
      }, grant:(...a:any[]) => original(h).grant(...a)});
    });
    const e = await event([p.hash]); e.expires = Date.now() + 200;
    const r = await client(0).request("POST", "/v1/sync", e);
    await runInDurableObject(mailboxStub(), async (m: Mailbox) => {delete (m as any).blob;});
    expect(r.status).toBe(400);
    expect((await getMediaAs(0, p.hash, dev(1))).status).toBe(404);
  });

  it("missing/empty media retain byte-identical reply shape and equal rows", async () => {
    const a = await post(undefined, "11111111");
    const b = await client(1).request("POST", "/v1/sync", await event([], "22222222"));
    expect(JSON.stringify(a.body)).toBe(`{"v":1,"id":"11111111","rev":1,"parts":1,"complete":true,"cursor":${a.body.cursor}}`);
    expect(JSON.stringify(b.body)).toBe(`{"v":1,"id":"22222222","rev":1,"parts":1,"complete":true,"cursor":${b.body.cursor}}`);
    expect(a.headers.get("x-relay-media-rows")).toBeNull();
    expect(b.headers.get("x-relay-media-rows")).toBeNull();
    expect(b.headers.get("x-relay-sql-rows")).toBe(a.headers.get("x-relay-sql-rows"));
    expect(await outbox()).toBe(-1);
  });
});
