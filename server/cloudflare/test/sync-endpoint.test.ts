import { describe, expect, it } from "vitest";
import { runInDurableObject } from "cloudflare:test";
import { authHeader, newIdentity, sha256Hex, type Reply } from "../client/mailbox-client";
import { BASE, client, mailboxStub, user, worker } from "./helpers";
import type { Mailbox } from "../src/mailbox";

/**
 * WP05 part B: the bounded sync channel for SENT copies and KEYS-style envelopes that are too big or
 * too stateful for a `TYPE:SYNC` message. A versioned envelope is uploaded in parts, assembled when
 * every part is present, verified by hash and total size, and then fetched by our own devices with a
 * cursor. Malformed input must never move the cursor or damage an assembly in progress (G3).
 */

const MAX_BODY = 12 * 1024;

const b64 = (bytes: Uint8Array) => {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
};

interface EventSpec {
  id?: string;
  rev?: number;
  parts?: number;
  body?: Uint8Array;
  hash?: string;
  total?: number;
  expires?: number;
  v?: number;
}

const newId = () => [...crypto.getRandomValues(new Uint8Array(12))].map((b) => b.toString(16).padStart(2, "0")).join("");

async function post(device: string | null, body: unknown): Promise<Reply> {
  const path = device === null ? "/v1/sync" : `/v1/sync?device=${device}`;
  return client(1).request("POST", path, body);
}

const syncPath = (device: string | null) => (device === null ? "/v1/sync" : `/v1/sync?device=${device}`);

const get = (query: string): Promise<Reply> => client(1).request("GET", `/v1/sync${query ? `?${query}` : ""}`);

async function part(device: string | null, spec: EventSpec, index: number) {
  const body = spec.body ?? new Uint8Array(0);
  const parts = spec.parts ?? 1;
  const per = Math.ceil(body.byteLength / parts);
  const slice = body.slice(index * per, Math.min(body.byteLength, (index + 1) * per));
  return post(device, {
    v: spec.v ?? 1,
    id: spec.id ?? newId(),
    rev: spec.rev ?? 1,
    part: index,
    parts,
    total: spec.total ?? body.byteLength,
    hash: spec.hash ?? (await sha256Hex(body)),
    expires: spec.expires ?? Date.now() + 60_000,
    data: b64(slice),
  });
}

/** Uploads every part in the given order and returns the last reply. */
async function upload(device: string | null, spec: EventSpec & { order?: number[] } = {}) {
  const parts = spec.parts ?? 1;
  const order = spec.order ?? Array.from({ length: parts }, (_, i) => i);
  let last: Reply | null = null;
  for (const index of order) last = await part(device, spec, index);
  return last!;
}

describe("sync channel: part reassembly", () => {
  it("assembles two parts uploaded in order and serves the exact bytes", async () => {
    const body = crypto.getRandomValues(new Uint8Array(9000));
    const id = newId();
    const first = await part(null, { id, parts: 2, body }, 0);
    expect(first.status).toBe(200);
    expect(first.body).toMatchObject({ v: 1, id, part: 0, parts: 2, complete: false });
    const second = await part(null, { id, parts: 2, body }, 1);
    expect(second.status).toBe(200);
    expect(second.body.complete).toBe(true);
    expect(second.body.cursor).toBeGreaterThan(0);

    const list = await get("after=0");
    expect(list.status).toBe(200);
    expect(list.body.events).toHaveLength(1);
    expect(list.body.events[0]).toMatchObject({ id, rev: 1, total: body.byteLength });
    const served = Uint8Array.from(atob(list.body.events[0].data), (c) => c.charCodeAt(0));
    expect(served).toEqual(body);

    const single = await client(1).request("GET", `/v1/sync/${id}`);
    expect(single.status).toBe(200);
    expect(single.body).toMatchObject({ v: 1, id, rev: 1, total: body.byteLength, hash: await sha256Hex(body) });
  });

  it("accepts out-of-order parts and only completes when all are present", async () => {
    const body = crypto.getRandomValues(new Uint8Array(6000));
    const id = newId();
    const last = await upload(null, { id, parts: 3, body, order: [2, 0, 1] });
    expect(last.status).toBe(200);
    expect(last.body.complete).toBe(true);
    const list = await get("after=0");
    expect(list.body.events).toHaveLength(1);
    expect(list.body.events[0].id).toBe(id);
  });

  it("treats an identical duplicate part as idempotent and refuses different bytes", async () => {
    const body = crypto.getRandomValues(new Uint8Array(4000));
    const id = newId();
    await part(null, { id, parts: 2, body }, 0);
    const duplicate = await part(null, { id, parts: 2, body }, 0);
    expect(duplicate.status).toBe(200);
    expect(duplicate.body.duplicate).toBe(true);

    const conflicting = await post(null, {
      v: 1,
      id,
      rev: 1,
      part: 0,
      parts: 2,
      total: body.byteLength,
      hash: await sha256Hex(body),
      expires: Date.now() + 60_000,
      data: b64(crypto.getRandomValues(new Uint8Array(2000))),
    });
    expect(conflicting.status).toBe(409);
    expect(conflicting.body.error.code).toBe("sync_conflict");
    // The original part is intact: completing with the correct bytes still works.
    expect((await part(null, { id, parts: 2, body }, 1)).body.complete).toBe(true);
  });

  it("rejects a conflicting revision for the same sync id", async () => {
    const body = crypto.getRandomValues(new Uint8Array(3000));
    const id = newId();
    expect((await upload(null, { id, rev: 1, body })).status).toBe(200);
    const other = await part(null, { id, rev: 2, body }, 0);
    expect(other.status).toBe(409);
    expect(other.body.error.code).toBe("sync_conflict");
    const list = await get("after=0");
    expect(list.body.events).toHaveLength(1);
    expect(list.body.events[0].rev).toBe(1);
  });

  it("rejects a bad content hash and leaves no event and no part", async () => {
    const body = crypto.getRandomValues(new Uint8Array(2000));
    const id = newId();
    const reply = await post(null, {
      v: 1,
      id,
      rev: 1,
      part: 0,
      parts: 1,
      total: body.byteLength,
      hash: "0".repeat(64),
      expires: Date.now() + 60_000,
      data: b64(body),
    });
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("bad_request");
    expect((await client(1).request("GET", `/v1/sync/${id}`)).status).toBe(404);
    const remaining = await runInDurableObject(mailboxStub(), async (_: Mailbox, state) =>
      state.storage.sql.exec("SELECT COUNT(*) AS n FROM sync_parts").one().n);
    expect(remaining).toBe(0);
  });
});

describe("sync channel: bounds", () => {
  it("rejects an event whose decoded body exceeds the 16 KiB envelope bound", async () => {
    const body = new Uint8Array(MAX_BODY + 1).fill(1);
    const reply = await post(null, {
      v: 1,
      id: newId(),
      rev: 1,
      part: 0,
      parts: 1,
      total: body.byteLength,
      hash: await sha256Hex(body),
      expires: Date.now() + 60_000,
      data: b64(body),
    });
    expect(reply.status).toBe(413);
    expect(reply.body.error.code).toBe("envelope_too_large");
    // Base64 of a maximum body still fits the existing 16 KiB envelope bound.
    const max = new Uint8Array(MAX_BODY).fill(2);
    expect(b64(max).length).toBeLessThanOrEqual(16 * 1024);
    expect((await upload(null, { body: max })).status).toBe(200);
  });

  it("rejects a declared total that disagrees with the parts or is impossible", async () => {
    const body = new Uint8Array(100).fill(3);
    const overParts = await post(null, {
      v: 1, id: newId(), rev: 1, part: 0, parts: 9, total: 100, hash: await sha256Hex(body), expires: Date.now() + 60_000, data: b64(body),
    });
    expect(overParts.status).toBe(400);
    const mismatch = await post(null, {
      v: 1, id: newId(), rev: 1, part: 0, parts: 1, total: 999, hash: await sha256Hex(body), expires: Date.now() + 60_000, data: b64(body),
    });
    expect(mismatch.status).toBe(400);
    const badIndex = await post(null, {
      v: 1, id: newId(), rev: 1, part: 2, parts: 2, total: 100, hash: await sha256Hex(body), expires: Date.now() + 60_000, data: b64(body),
    });
    expect(badIndex.status).toBe(400);
  });

  it("rejects an unknown version and malformed input without moving the cursor", async () => {
    const body = new Uint8Array(500).fill(4);
    const good = await upload(null, { body });
    expect(good.body.cursor).toBeGreaterThan(0);
    const cursor = good.body.cursor;

    const version = await upload(null, { body, v: 2 });
    expect(version.status).toBe(400);
    expect(version.body.error.code).toBe("bad_request");
    const malformed = await post(null, { v: 1, id: newId(), rev: 1, part: 0, parts: 1, total: 10, hash: "x", expires: 1, data: "!!" });
    expect(malformed.status).toBe(400);
    const notJson = await client(1).request("POST", "/v1/sync", "not an object");
    expect(notJson.status).toBe(400);
    // The channel still holds exactly the first event at the old cursor.
    const list = await get(`after=${cursor - 1}`);
    expect(list.body.events).toHaveLength(1);
    const none = await get(`after=${cursor}`);
    expect(none.body.events).toHaveLength(0);
  });

  it("bounds concurrent assemblies per identity", async () => {
    const ids = Array.from({ length: 4 }, () => newId());
    for (let i = 0; i < 4; i++) expect((await part(null, { id: ids[i], parts: 2, body: new Uint8Array(100 + i) }, 0)).status).toBe(200);
    const fifth = await part(null, { id: newId(), parts: 2, body: new Uint8Array(120) }, 0);
    expect(fifth.status).toBe(409);
    expect(fifth.body.error.code).toBe("sync_limit");
    // Completing one assembly frees a slot.
    const body = new Uint8Array(100);
    expect((await part(null, { id: ids[0], parts: 2, body }, 1)).status).toBe(200);
    const again = await part(null, { id: newId(), parts: 2, body }, 0);
    expect(again.status).toBe(200);
  });

  it("expires an event after its TTL and treats parts as gone", async () => {
    const body = new Uint8Array(600).fill(5);
    const id = newId();
    await upload(null, { id, parts: 2, body });
    await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
      state.storage.sql.exec("UPDATE sync_events SET expires_at = ? WHERE id = ?", Date.now() - 1, id);
    });
    expect((await client(1).request("GET", `/v1/sync/${id}`)).status).toBe(404);
    const list = await get("after=0");
    expect(list.body.events).toHaveLength(0);
  });
});

describe("sync channel: cursor", () => {
  it("advances the caller's stored cursor and returns only what was missed", async () => {
    const first = await upload(null, { body: new Uint8Array(100).fill(6) });
    const second = await upload(null, { body: new Uint8Array(100).fill(7) });
    expect(second.body.cursor).toBeGreaterThan(first.body.cursor);

    const page = await get("");
    expect(page.body.events.map((e: any) => e.cursor)).toEqual([first.body.cursor, second.body.cursor]);

    const ack = await client(1).request("POST", "/v1/sync/ack", { v: 1, through: first.body.cursor });
    expect(ack.status).toBe(200);
    expect(ack.body).toMatchObject({ v: 1, cursor: first.body.cursor });

    const afterAck = await get("");
    expect(afterAck.body.events.map((e: any) => e.cursor)).toEqual([second.body.cursor]);
    // A stale ack never moves the stored cursor back.
    await client(1).request("POST", "/v1/sync/ack", { v: 1, through: 0 });
    expect((await get("")).body.events.map((e: any) => e.cursor)).toEqual([second.body.cursor]);
  });

  it("keeps each device's cursor separate", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    const device = "2".repeat(32);
    const signed = async (sk: Uint8Array, method: string, path: string, body?: unknown): Promise<any> => {
      const url = BASE + path;
      const text = body === undefined ? undefined : JSON.stringify(body);
      const headers: Record<string, string> = { Authorization: await authHeader(sk, method, url, text) };
      if (text !== undefined) headers["content-type"] = "application/json";
      const response = await worker(new Request(url, { method, headers, body: text }));
      return { status: response.status, body: await response.json().catch(() => null) };
    };
    expect((await signed(user(1).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk })).status).toBe(200);
    expect((await signed(admin.sk, "POST", "/v1/devices?device=primary", { v: 1, device, label: "desktop", auth_pubkey: desktop.pk })).status).toBe(200);

    const uploaded = await signed(admin.sk, "POST", "/v1/sync", {
      v: 1, id: newId(), rev: 1, part: 0, parts: 1, total: 100,
      hash: await sha256Hex(new Uint8Array(100).fill(8)), expires: Date.now() + 60_000,
      data: b64(new Uint8Array(100).fill(8)),
    });
    expect(uploaded.body.complete).toBe(true);
    const cursor = uploaded.body.cursor;

    // The desktop acks; the phone's own cursor is untouched.
    expect((await signed(desktop.sk, "POST", `/v1/sync/ack?device=${device}`, { v: 1, through: cursor })).status).toBe(200);
    const phone = await signed(admin.sk, "GET", "/v1/sync");
    expect(phone.body.events.map((e: any) => e.cursor)).toEqual([cursor]);
    const computer = await signed(desktop.sk, "GET", `/v1/sync?device=${device}`);
    expect(computer.body.events).toEqual([]);
  });
});
