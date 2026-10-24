import { describe, expect, it } from "vitest";
import { runInDurableObject } from "cloudflare:test";
import { authHeader, newIdentity, sha256Hex, type Identity } from "../client/mailbox-client";
import { BASE, user, worker, mailboxStub } from "./helpers";
import type { Mailbox } from "../src/mailbox";

/**
 * WP05 part B: the link slot. The phone seals a bundle (identity keys, contacts, groups, settings; no
 * history, LP-D06) and parks it under sha256(one-time code). The desktop proves knowledge of the code
 * with a header and fetches it once, then explicitly completes the read so a lost response does not
 * destroy the only copy.
 */

const SLOT = /^[0-9a-f]{64}$/;
const enc = new TextEncoder();

const slotFor = (code: string) => sha256Hex(code);

interface Raw {
  status: number;
  bytes: Uint8Array;
  text: string;
  headers: Headers;
}

async function raw(request: Request): Promise<Raw> {
  const response = await worker(request);
  const bytes = new Uint8Array(await response.arrayBuffer());
  return { status: response.status, bytes, text: new TextDecoder().decode(bytes), headers: response.headers };
}

const jsonOf = (r: Raw): any => {
  try {
    return JSON.parse(r.text);
  } catch {
    return null;
  }
};

async function putLink(sk: Uint8Array, slot: string, body: Uint8Array, opts: { headers?: Record<string, string> } = {}): Promise<Raw> {
  const url = `${BASE}/v1/link/${slot}`;
  const headers: Record<string, string> = {
    Authorization: await authHeader(sk, "PUT", url, body),
    "content-type": "application/octet-stream",
    ...opts.headers,
  };
  return raw(new Request(url, { method: "PUT", headers, body: body as BodyInit }));
}

async function getLink(slot: string, proof?: string, extra: Record<string, string> = {}): Promise<Raw> {
  const headers: Record<string, string> = { ...extra };
  if (proof !== undefined) headers["x-relay-link-proof"] = proof;
  return raw(new Request(`${BASE}/v1/link/${slot}`, { headers }));
}

async function completeLink(slot: string, proof?: string): Promise<Raw> {
  const headers: Record<string, string> = {};
  if (proof !== undefined) headers["x-relay-link-proof"] = proof;
  return raw(new Request(`${BASE}/v1/link/${slot}`, { method: "DELETE", headers }));
}

const code = () => [...crypto.getRandomValues(new Uint8Array(32))].map((b) => b.toString(16).padStart(2, "0")).join("");

const migrate = (i: number, admin: Identity) => {
  const url = `${BASE}/v1/devices/admin`;
  return authHeader(user(i).sk, "POST", url, JSON.stringify({ v: 1, admin_pubkey: admin.pk })).then((Authorization) =>
    raw(new Request(url, { method: "POST", headers: { Authorization, "content-type": "application/json" }, body: JSON.stringify({ v: 1, admin_pubkey: admin.pk }) })),
  );
};

describe("link slot: PUT -> GET -> complete", () => {
  it("stores a sealed bundle and returns exactly those bytes once completed", async () => {
    const c = code();
    const slot = await slotFor(c);
    const bundle = crypto.getRandomValues(new Uint8Array(2048));
    const put = await putLink(user(1).sk, slot, bundle);
    expect(put.status).toBe(200);
    const meta = jsonOf(put);
    expect(meta.v).toBe(1);
    expect(meta.slot).toBe(slot);
    expect(meta.size).toBe(bundle.byteLength);
    expect(meta.expiresAt).toBeGreaterThan(Date.now());

    const first = await getLink(slot, c);
    expect(first.status).toBe(200);
    expect(first.bytes).toEqual(bundle);
    expect(first.headers.get("content-type")).toBe("application/octet-stream");
    expect(Number(first.headers.get("x-relay-link-lease"))).toBeGreaterThan(Date.now());

    const done = await completeLink(slot, c);
    expect(done.status).toBe(200);
    expect(jsonOf(done)).toMatchObject({ v: 1, consumed: true });

    expect((await getLink(slot, c)).status).toBe(404);
    expect((await completeLink(slot, c)).status).toBe(404);
  });

  it("returns the bundle on a retried GET (lease) and only the completion consumes it", async () => {
    const c = code();
    const slot = await slotFor(c);
    const bundle = enc.encode("sealed bundle");
    expect((await putLink(user(1).sk, slot, bundle)).status).toBe(200);

    const one = await getLink(slot, c);
    const two = await getLink(slot, c);
    expect(one.status).toBe(200);
    expect(two.status).toBe(200);
    expect(two.bytes).toEqual(bundle);

    expect((await completeLink(slot, c)).status).toBe(200);
    expect((await getLink(slot, c)).status).toBe(404);
  });

  it("keeps a slot available after an unconsumed lease expires, then expires it at the TTL", async () => {
    const c = code();
    const slot = await slotFor(c);
    const bundle = enc.encode("retry after a lost response");
    await putLink(user(1).sk, slot, bundle);
    expect((await getLink(slot, c)).status).toBe(200);

    // The 60 s lease elapsed without a completion: the slot is still there and readable again.
    await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
      state.storage.sql.exec("UPDATE link_slots SET lease_until = ? WHERE slot = ?", Date.now() - 1000, slot);
    });
    const retry = await getLink(slot, c);
    expect(retry.status).toBe(200);
    expect(retry.bytes).toEqual(bundle);

    // Past the 10-minute TTL nothing is served, and the completion is refused too.
    await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
      state.storage.sql.exec("UPDATE link_slots SET expires_at = ? WHERE slot = ?", Date.now() - 1, slot);
    });
    expect((await getLink(slot, c)).status).toBe(404);
    expect((await completeLink(slot, c)).status).toBe(404);
  });

  it("refuses a bundle over 128 KiB and stores nothing", async () => {
    const c = code();
    const slot = await slotFor(c);
    const tooBig = new Uint8Array(128 * 1024 + 1);
    const put = await putLink(user(1).sk, slot, tooBig);
    expect(put.status).toBe(413);
    expect(jsonOf(put).error.code).toBe("envelope_too_large");
    expect((await getLink(slot, c)).status).toBe(404);
  });

  it("refuses a second live slot for the same identity without replacing the first", async () => {
    const firstCode = code();
    const secondCode = code();
    const firstSlot = await slotFor(firstCode);
    const secondSlot = await slotFor(secondCode);
    expect((await putLink(user(1).sk, firstSlot, enc.encode("first"))).status).toBe(200);
    const second = await putLink(user(1).sk, secondSlot, enc.encode("second"));
    expect(second.status).toBe(409);
    expect(jsonOf(second).error.code).toBe("link_busy");
    // The live slot is untouched, and the identity may try again after completing it.
    expect((await getLink(firstSlot, firstCode)).bytes).toEqual(enc.encode("first"));
    expect((await getLink(secondSlot, secondCode)).status).toBe(404);
    await completeLink(firstSlot, firstCode);
    expect((await putLink(user(1).sk, secondSlot, enc.encode("second"))).status).toBe(200);
  });

  it("treats a different identity's live slot as independent", async () => {
    const one = code();
    const two = code();
    expect((await putLink(user(1).sk, await slotFor(one), enc.encode("one"))).status).toBe(200);
    expect((await putLink(user(2).sk, await slotFor(two), enc.encode("two"))).status).toBe(200);
  });
});

describe("link slot: proof and guessing bounds", () => {
  it("rejects a missing proof with 401 and a wrong proof with 404", async () => {
    const c = code();
    const slot = await slotFor(c);
    await putLink(user(1).sk, slot, enc.encode("secret bundle"));
    const missing = await getLink(slot);
    expect(missing.status).toBe(401);
    expect(jsonOf(missing).error.code).toBe("auth_missing");
    const wrong = await getLink(slot, code());
    expect(wrong.status).toBe(404);
    expect(jsonOf(wrong).error.code).toBe("not_found");
    // A malformed proof is refused the same way, without saying anything about the slot.
    const malformed = await getLink(slot, "not-hex");
    expect(malformed.status).toBe(401);
    expect((await getLink(await slotFor(code()), c)).status).toBe(404);
    // The bundle is still there for the real code holder.
    expect((await getLink(slot, c)).status).toBe(200);
  });

  it("never accepts the proof as a query parameter", async () => {
    const c = code();
    const slot = await slotFor(c);
    await putLink(user(1).sk, slot, enc.encode("bundle"));
    const viaQuery = await raw(new Request(`${BASE}/v1/link/${slot}?proof=${c}`));
    expect(viaQuery.status).toBe(400);
    expect((await getLink(slot, c)).status).toBe(200);
  });

  it("bounds guesses per slot and per client bucket", async () => {
    const c = code();
    const slot = await slotFor(c);
    await putLink(user(1).sk, slot, enc.encode("bundle"));
    // The test pool sets MAX_LINK_ATTEMPTS_PER_MINUTE=5.
    const statuses: number[] = [];
    for (let i = 0; i < 5; i++) statuses.push((await getLink(slot, code())).status);
    expect(statuses).toEqual([404, 404, 404, 404, 404]);
    const blocked = await getLink(slot, code());
    expect(blocked.status).toBe(429);
    expect(jsonOf(blocked).error.code).toBe("rate_limited");
    // A guessed slot that does not exist is bounded the same way, so 429 does not reveal existence.
    const unknownSlot = await slotFor(code());
    expect((await getLink(unknownSlot, c)).status).toBe(429);
    // The owner can still complete it once the attempt window resets.
    await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
      (instance as any).linkAttempts.clear();
    });
    expect((await getLink(slot, c)).status).toBe(200);
  });
});

describe("link slot: authorization", () => {
  it("allows the shared identity key before migration", async () => {
    const slot = await slotFor(code());
    expect((await putLink(user(3).sk, slot, enc.encode("legacy"))).status).toBe(200);
  });

  it("refuses the shared key after migration and allows only the admin key", async () => {
    const admin = newIdentity();
    expect((await migrate(4, admin)).status).toBe(200);
    const slot = await slotFor(code());
    const shared = await putLink(user(4).sk, slot, enc.encode("shared"));
    expect(shared.status).toBe(401);
    expect(jsonOf(shared).error.code).toBe("auth_invalid");
    const byAdmin = await putLink(admin.sk, slot, enc.encode("admin"));
    expect(byAdmin.status).toBe(200);
  });

  it("refuses a linked device's own key, which is not primary", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    expect((await migrate(5, admin)).status).toBe(200);
    const enrollUrl = `${BASE}/v1/devices?device=primary`;
    const enrollBody = JSON.stringify({ v: 1, device: "1".repeat(32), label: "desktop", auth_pubkey: desktop.pk });
    const enrolled = await raw(new Request(enrollUrl, {
      method: "POST",
      headers: { Authorization: await authHeader(admin.sk, "POST", enrollUrl, enrollBody), "content-type": "application/json" },
      body: enrollBody,
    }));
    expect(enrolled.status).toBe(200);
    const slot = await slotFor(code());
    const reply = await putLink(desktop.sk, slot, enc.encode("device"));
    expect(reply.status).toBe(403);
    expect(jsonOf(reply).error.code).toBe("not_primary");
  });

  it("refuses a non-member and a replayed PUT", async () => {
    const outsider = newIdentity();
    const slot = await slotFor(code());
    const refused = await putLink(outsider.sk, slot, enc.encode("x"));
    expect(refused.status).toBe(403);
    expect(jsonOf(refused).error.code).toBe("not_allowed");

    const c = code();
    const liveSlot = await slotFor(c);
    const body = enc.encode("bundle");
    const url = `${BASE}/v1/link/${liveSlot}`;
    const Authorization = await authHeader(user(1).sk, "PUT", url, body);
    const first = await raw(new Request(url, { method: "PUT", headers: { Authorization }, body: body as BodyInit }));
    expect(first.status).toBe(200);
    const replay = await raw(new Request(url, { method: "PUT", headers: { Authorization }, body: body as BodyInit }));
    expect(replay.status).toBe(401);
    expect(jsonOf(replay).error.code).toBe("auth_replayed");
  });

  it("refuses a malformed or unknown method", async () => {
    // The edge router does not recognise a malformed slot at all, so it is a plain 404.
    expect((await putLink(user(1).sk, "not-a-slot", enc.encode("x"))).status).toBe(404);
    const slot = await slotFor(code());
    const post = await raw(new Request(`${BASE}/v1/link/${slot}`, { method: "POST" }));
    expect(post.status).toBe(405);
  });
});
