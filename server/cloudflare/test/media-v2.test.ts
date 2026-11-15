import { env, evictDurableObject, runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { authHeader, sha256Hex } from "../client/mailbox-client";
import { MediaDigest } from "../src/media-digest";
import { MAX_RESUMABLE_MEDIA_BYTES, MEDIA_PART_BYTES } from "../src/protocol";
import { BASE, client, user, worker } from "./helpers";
import { OUTSIDER } from "./identities";

const stub = (hash: string) => env.MEDIA.get(env.MEDIA.idFromName(hash));
const path = (hash: string, action = "") => `/v2/media/${hash}${action ? `/${action}` : ""}`;

async function request(who: number, method: string, target: string, body?: Uint8Array | string, extra: Record<string, string> = {}) {
  const url = BASE + target;
  const headers = { Authorization: await authHeader(user(who).sk, method, url, body), ...extra };
  return worker(new Request(url, { method, headers, body }));
}

const init = (hash: string, size: number, recipients = [user(1).pk], who = 0) =>
  request(who, "POST", path(hash, "init"), JSON.stringify({ size, partSize: MEDIA_PART_BYTES, recipients }));

async function fixture(size = MEDIA_PART_BYTES + 79) {
  const bytes = new Uint8Array(size);
  crypto.getRandomValues(bytes.subarray(0, Math.min(32, size)));
  const hash = await sha256Hex(bytes);
  const parts: Uint8Array[] = [];
  const checksums: string[] = [];
  for (let n = 0; n < size; n += MEDIA_PART_BYTES) {
    const part = bytes.subarray(n, Math.min(size, n + MEDIA_PART_BYTES));
    parts.push(part);
    checksums.push(await sha256Hex(part));
  }
  return { bytes, hash, parts, checksums };
}

const upload = (f: Awaited<ReturnType<typeof fixture>>, n: number, who = 0) =>
  request(who, "PUT", path(f.hash, `parts/${n}`), f.parts[n], { "x-relay-part-sha256": f.checksums[n] });
const complete = (f: Awaited<ReturnType<typeof fixture>>, parts = f.checksums) =>
  request(0, "POST", path(f.hash, "complete"), JSON.stringify({ parts }));

describe("resumable media v2", () => {
  it("resumes a transfer from durable state and delivers exact parts only after completion", async () => {
    const f = await fixture();
    expect(await (await init(f.hash, f.bytes.length)).json()).toMatchObject({ v: 2, state: "uploading", nextPart: 0, receivedParts: [], totalParts: 2 });
    expect(await (await upload(f, 0)).json()).toMatchObject({ stored: true, duplicate: false, nextPart: 1 });
    await evictDurableObject(stub(f.hash)); // resume from SQLite, including the incremental hash checkpoint
    expect(await (await request(0, "GET", path(f.hash, "status"))).json()).toMatchObject({ state: "uploading", nextPart: 1, receivedParts: [0], size: f.bytes.length });
    expect((await request(1, "GET", path(f.hash, "status"))).status).toBe(404);
    expect((await request(1, "GET", path(f.hash, "parts/0"))).status).toBe(404);
    expect((await request(0, "GET", path(f.hash, "parts/0"))).status).toBe(404);
    expect((await request(1, "DELETE", path(f.hash))).status).toBe(404);
    expect((await client(1).getMedia(f.hash)).status).toBe(404);
    expect((await client(1).confirmMedia(f.hash)).status).toBe(404);
    expect((await complete(f)).status).toBe(409);
    expect(await (await upload(f, 0)).json()).toMatchObject({ duplicate: true, nextPart: 1 });
    expect(await (await init(f.hash, f.bytes.length)).json()).toMatchObject({ nextPart: 1 });
    expect((await upload(f, 1)).status).toBe(200);
    expect(await (await complete(f)).json()).toEqual({ v: 2, stored: true, duplicate: false });
    expect(await (await complete(f)).json()).toMatchObject({ duplicate: true });
    expect(await (await request(1, "GET", path(f.hash, "status"))).json()).toMatchObject({ state: "complete", receivedParts: [0, 1] });
    for (let n = 0; n < f.parts.length; n++) {
      const got = await request(1, "GET", path(f.hash, `parts/${n}`));
      expect(got.status).toBe(200);
      expect(got.headers.get("x-relay-part-sha256")).toBe(f.checksums[n]);
      expect(new Uint8Array(await got.arrayBuffer())).toEqual(f.parts[n]);
    }
    // The legacy route cannot accidentally assemble a multi-GB v2 file in memory.
    expect((await client(1).getMedia(f.hash)).status).toBe(426);
    expect((await request(1, "DELETE", path(f.hash))).status).toBe(200);
    expect((await request(0, "GET", path(f.hash, "status"))).status).toBe(404);
  });

  it("enforces uploader, immutable metadata, ordering, exact lengths and checksums", async () => {
    const f = await fixture();
    expect((await init(f.hash, f.bytes.length)).status).toBe(200);
    expect((await init(f.hash, f.bytes.length, [user(2).pk])).status).toBe(409);
    expect((await init(f.hash, f.bytes.length + 1)).status).toBe(409);
    expect((await init(f.hash, f.bytes.length, [user(1).pk], 2)).status).toBe(404);
    expect((await upload(f, 0, 2)).status).toBe(404);
    expect((await request(2, "GET", path(f.hash, "status"))).status).toBe(404);
    expect((await upload(f, 1)).status).toBe(409);
    expect((await request(0, "PUT", path(f.hash, "parts/0"), f.parts[0], { "x-relay-part-sha256": "00".repeat(32) })).status).toBe(400);
    expect((await request(0, "PUT", path(f.hash, "parts/0"), new Uint8Array(3), { "x-relay-part-sha256": await sha256Hex(new Uint8Array(3)) })).status).toBe(400);
    expect((await upload(f, 0)).status).toBe(200);
    const replacement = new Uint8Array(f.parts[0]);
    replacement[0] ^= 1;
    expect((await request(0, "PUT", path(f.hash, "parts/0"), replacement, { "x-relay-part-sha256": await sha256Hex(replacement) })).status).toBe(409);
    expect((await upload(f, 1)).status).toBe(200);
    expect((await complete(f, [...f.checksums].reverse())).status).toBe(409);
    expect((await complete(f)).status).toBe(200);
    expect((await request(2, "GET", path(f.hash, "parts/0"))).status).toBe(404);
    expect((await request(OUTSIDER, "GET", path(f.hash, "status"))).status).toBe(404);
    expect((await client(2).putMedia(f.bytes, [user(2).pk])).status).toBe(413); // legacy cap still applies
  });

  it("does not publish a complete manifest under the wrong whole-file address", async () => {
    const f = await fixture(79);
    f.hash = "a1".repeat(32);
    expect((await init(f.hash, f.bytes.length)).status).toBe(200);
    expect((await upload(f, 0)).status).toBe(200);
    expect((await complete(f)).status).toBe(400);
    expect((await request(1, "GET", path(f.hash, "status"))).status).toBe(404);
    expect((await request(1, "GET", path(f.hash, "parts/0"))).status).toBe(404);
  });

  it("accepts Long-sized totals up to the cipher cap without allocating the file and rejects larger totals", async () => {
    const hash = "b1".repeat(32);
    const started = await init(hash, MAX_RESUMABLE_MEDIA_BYTES);
    expect(started.status).toBe(200);
    expect(await started.json()).toMatchObject({ size: 3222274048, totalParts: 3073, nextPart: 0 });
    await runInDurableObject(stub(hash), async (_, state) => {
      expect(state.storage.sql.exec("SELECT size FROM info").one().size).toBe(3222274048);
      expect(state.storage.sql.exec("SELECT COUNT(*) AS n FROM chunks").one().n).toBe(0);
    });
    expect((await init("b2".repeat(32), MAX_RESUMABLE_MEDIA_BYTES + 1)).status).toBe(413);
    expect((await init("b3".repeat(32), 2 ** 53)).status).toBe(400);
    expect((await init("b4".repeat(32), 3.5)).status).toBe(400);
    expect((await init("b5".repeat(32), 0)).status).toBe(400);
    expect((await init("b6".repeat(32), 1, [user(1).pk], OUTSIDER)).status).toBe(403);
    expect((await init("b7".repeat(32), 1, ["ff".repeat(32)])).status).toBe(400);
  });

  it("bounds chunked requests even without a Content-Length header", async () => {
    const f = await fixture(79);
    await init(f.hash, f.bytes.length);
    const bytes = new Uint8Array(MEDIA_PART_BYTES + 1);
    const url = BASE + path(f.hash, "parts/0");
    const headers = { Authorization: await authHeader(user(0).sk, "PUT", url, bytes), "x-relay-part-sha256": await sha256Hex(bytes) };
    const stream = new ReadableStream<Uint8Array>({ start(c) { c.enqueue(bytes.subarray(0, MEDIA_PART_BYTES)); c.enqueue(bytes.subarray(MEDIA_PART_BYTES)); c.close(); } });
    const res = await worker(new Request(url, { method: "PUT", headers, body: stream }));
    expect(res.status).toBe(413);
    expect(await (await request(0, "GET", path(f.hash, "status"))).json()).toMatchObject({ nextPart: 0 });
  });

  it("rejects replayed auth and expires abandoned uploads without extending their lifetime", async () => {
    const f = await fixture();
    const first = await (await init(f.hash, f.bytes.length)).json() as { expiresAt: number };
    await upload(f, 0);
    expect(await (await init(f.hash, f.bytes.length)).json()).toMatchObject({ expiresAt: first.expiresAt });
    const url = BASE + path(f.hash, "status");
    const headers = { Authorization: await authHeader(user(0).sk, "GET", url) };
    expect((await worker(new Request(url, { headers }))).status).toBe(200);
    expect((await worker(new Request(url, { headers }))).status).toBe(401);
    expect(await runDurableObjectAlarm(stub(f.hash))).toBe(true);
    expect((await request(0, "GET", path(f.hash, "status"))).status).toBe(404);
    await runInDurableObject(stub(f.hash), async (_, state) => {
      expect(state.storage.sql.exec("SELECT COUNT(*) AS n FROM chunks").one().n).toBe(0);
      expect(await state.storage.getAlarm()).toBeNull();
    });
  });

  it("never lets a different legacy uploader add a new recipient", async () => {
    const f = await fixture(79);
    expect((await client(0).putMedia(f.bytes, [user(1).pk])).status).toBe(200);
    expect((await client(2).putMedia(f.bytes, [user(2).pk])).status).toBe(404);
    expect((await client(2).getMedia(f.hash)).status).toBe(404);
    expect((await client(1).getMedia(f.hash)).status).toBe(200);
    expect((await init(f.hash, f.bytes.length)).status).toBe(404);
  });

  it("preserves a legacy schema-1 file, recipients and alarm during migration", async () => {
    const f = await fixture(79);
    await client(0).putMedia(f.bytes, [user(1).pk]);
    const alarm = await runInDurableObject(stub(f.hash), async (_, state) => {
      state.storage.transactionSync(() => {
        for (const column of ["version", "part_size", "next_part", "complete", "digest"]) state.storage.sql.exec(`ALTER TABLE info DROP COLUMN ${column}`);
        state.storage.sql.exec("ALTER TABLE chunks DROP COLUMN sha256");
        state.storage.sql.exec("UPDATE meta SET value = '1' WHERE key = 'schema'");
      });
      return state.storage.getAlarm();
    });
    await evictDurableObject(stub(f.hash));
    const got = await client(1).getMedia(f.hash);
    expect(got.status).toBe(200);
    expect(got.bytes).toEqual(f.bytes);
    await runInDurableObject(stub(f.hash), async (_, state) => {
      expect(state.storage.sql.exec("SELECT value FROM meta WHERE key = 'schema'").one().value).toBe("2");
      expect(state.storage.sql.exec("SELECT version, complete FROM info").one()).toMatchObject({ version: 1, complete: 1 });
      expect(await state.storage.getAlarm()).toBe(alarm);
    });
  });

  it("rejects expired content even before its alarm runs", async () => {
    const f = await fixture(79);
    await init(f.hash, f.bytes.length);
    await upload(f, 0);
    await complete(f);
    await runInDurableObject(stub(f.hash), async (_, state) => { state.storage.sql.exec("UPDATE info SET stored_at = 1"); });
    expect((await request(1, "GET", path(f.hash, "parts/0"))).status).toBe(404);
    expect((await request(0, "GET", path(f.hash, "status"))).status).toBe(404);
    await runInDurableObject(stub(f.hash), async (_, state) => {
      expect(state.storage.sql.exec("SELECT COUNT(*) AS n FROM chunks").one().n).toBe(0);
      expect(state.storage.sql.exec("SELECT COUNT(*) AS n FROM recipients").one().n).toBe(0);
    });
  });

  it("commits concurrent identical retries once and waits for every recipient to confirm", async () => {
    const f = await fixture(79);
    await init(f.hash, f.bytes.length, [user(1).pk, user(2).pk]);
    const replies = await Promise.all([upload(f, 0), upload(f, 0)]);
    expect(replies.map((r) => r.status)).toEqual([200, 200]);
    expect((await complete(f)).status).toBe(200);
    expect(await (await request(1, "DELETE", path(f.hash))).json()).toMatchObject({ remaining: 1 });
    expect((await request(2, "GET", path(f.hash, "parts/0"))).status).toBe(200);
    expect(await (await request(2, "DELETE", path(f.hash))).json()).toMatchObject({ remaining: 0 });
    expect((await request(0, "GET", path(f.hash, "status"))).status).toBe(404);
  });
});

describe("durable incremental SHA-256", () => {
  it.each([1, 55, 56, 63, 64, 65, 127, 1024, MEDIA_PART_BYTES + 79])("restores checkpoints and hashes %i bytes exactly", async (size) => {
    const bytes = new Uint8Array(size);
    crypto.getRandomValues(bytes.subarray(0, Math.min(size, 65536)));
    const split = Math.floor(size / 2);
    const checkpoint = new MediaDigest().update(bytes.subarray(0, split)).checkpoint();
    expect(MediaDigest.restore(checkpoint).update(bytes.subarray(split)).hex()).toBe(await sha256Hex(bytes));
  });

  it("keeps byte counters above signed-32-bit range intact", () => {
    const checkpoint = JSON.parse(new MediaDigest().checkpoint());
    checkpoint.length = 2 ** 31;
    const digest = MediaDigest.restore(JSON.stringify(checkpoint)).update(new Uint8Array(64));
    expect(JSON.parse(digest.checkpoint()).length).toBe(2 ** 31 + 64);
  });
});
