import { afterEach, describe, expect, it, vi } from "vitest";
import { authHeader, newIdentity, type Identity, type Reply } from "../client/mailbox-client";
import { turnConfigFrom } from "../src/call";
import { API_TOKEN, CRED_PASS, CRED_USER, KEY_ID, fakeTurn, providerBody, setLimit } from "./call-helpers";
import { dev } from "./device-helpers";
import { BASE, client, user, worker } from "./helpers";
import { OUTSIDER } from "./identities";

/**
 * WP11-AC1 — GET /v1/call/ice (05 §5, G4). Every upstream call goes to a fake fetch: nothing here reaches
 * Cloudflare. The "provider" keys in call-helpers.ts are test constants, not real credentials.
 */

const ICE = "/v1/call/ice";
const ok = (body: unknown, status = 201) => new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
const ice = (i: number) => client(i).request("GET", ICE);

/** A request signed by an arbitrary key (identity key, admin key or device key). */
async function as(sk: Uint8Array, method: string, path: string, body?: unknown): Promise<Reply> {
  const url = BASE + path;
  const text = body === undefined ? undefined : JSON.stringify(body);
  const headers: Record<string, string> = { Authorization: await authHeader(sk, method, url, text) };
  if (text !== undefined) headers["content-type"] = "application/json";
  const response = await worker(new Request(url, { method, headers, body: text }));
  const raw = await response.text();
  let parsed: any = raw;
  try {
    parsed = raw ? JSON.parse(raw) : null;
  } catch {
    // keep text
  }
  return { status: response.status, body: parsed, headers: response.headers };
}

async function migratedWithDesktop(i = 1): Promise<{ admin: Identity; desktop: Identity }> {
  const admin = newIdentity();
  const desktop = newIdentity();
  expect((await as(user(i).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk })).status).toBe(200);
  expect((await as(admin.sk, "POST", "/v1/devices?device=primary", { v: 1, device: dev(1), label: "desktop", auth_pubkey: desktop.pk })).status).toBe(200);
  return { admin, desktop };
}

afterEach(() => vi.restoreAllMocks());

describe("WP11-AC1 mint short-lived ICE credentials for members", () => {
  it("returns STUN/TURN servers with credentials, port 53 stripped, ttl at most 600 s", async () => {
    const calls = await fakeTurn();
    const before = Date.now();
    const reply = await ice(0);
    expect(reply.status).toBe(200);
    expect(Object.keys(reply.body)).toEqual(["v", "iceServers", "expiresAt"]);
    expect(reply.body.v).toBe(1);
    const urls: string[] = reply.body.iceServers.flatMap((s: any) => s.urls);
    expect(urls.some((u) => /:53(\?|$)/.test(u))).toBe(false);
    expect(urls).toContain("turns:turn.cloudflare.com:443?transport=tcp");
    expect(urls).toContain("stun:stun.cloudflare.com:3478");
    const turn = reply.body.iceServers.find((s: any) => s.username);
    expect(turn).toEqual({ urls: expect.any(Array), username: CRED_USER, credential: CRED_PASS });
    // The first server is STUN only: no credential fields at all.
    expect(Object.keys(reply.body.iceServers[0])).toEqual(["urls"]);
    expect(reply.body.expiresAt).toBeGreaterThan(before + 590_000);
    expect(reply.body.expiresAt).toBeLessThanOrEqual(Date.now() + 600_000);
    expect(calls).toHaveLength(1);
    expect(JSON.parse(calls[0].body)).toEqual({ ttl: 600 });
  });

  it("calls the provider with the key id in the path, a bearer token, an honest User-Agent and no redirects", async () => {
    const calls = await fakeTurn();
    await ice(0);
    expect(calls[0].url).toBe(`https://rtc.live.cloudflare.com/v1/turn/keys/${KEY_ID}/credentials/generate-ice-servers`);
    expect(calls[0].method).toBe("POST");
    expect(calls[0].headers.authorization).toBe(`Bearer ${API_TOKEN}`);
    expect(calls[0].headers["content-type"]).toBe("application/json");
    expect(calls[0].headers["user-agent"]).toMatch(/^Relay[A-Za-z]*\/\d/);
    expect(calls[0].headers["user-agent"]).not.toMatch(/urllib|python|curl/i);
    expect(calls[0].redirect).toBe("manual");
  });

  it("never echoes the key id or API token, and never logs them", async () => {
    await fakeTurn();
    const logged: string[] = [];
    for (const method of ["error", "warn", "log", "info"] as const) {
      vi.spyOn(console, method).mockImplementation((...args: unknown[]) => void logged.push(JSON.stringify(args)));
    }
    const good = await client(0).request("GET", ICE);
    expect(JSON.stringify(good)).not.toContain(API_TOKEN);
    expect(JSON.stringify([...good.headers])).not.toContain(KEY_ID);
    expect(JSON.stringify(good.body)).not.toContain(KEY_ID);
    // A failing provider whose body echoes the token must not surface it either.
    await fakeTurn(() => new Response(`denied for ${API_TOKEN} key ${KEY_ID}`, { status: 403 }));
    const bad = await client(1).request("GET", ICE);
    expect(bad.status).toBe(502);
    expect(JSON.stringify(bad)).not.toContain(API_TOKEN);
    expect(JSON.stringify(bad)).not.toContain(KEY_ID);
    expect(logged.join("\n")).not.toContain(API_TOKEN);
    expect(logged.join("\n")).not.toContain(KEY_ID);
    expect(logged.join("\n")).not.toContain(CRED_PASS);
  });

  it("lets an enrolled device sign for itself and refuses the shared identity key after migration", async () => {
    const calls = await fakeTurn();
    const { desktop } = await migratedWithDesktop(1);
    const viaDevice = await as(desktop.sk, "GET", `${ICE}?device=${dev(1)}`);
    expect(viaDevice.status).toBe(200);
    expect(calls).toHaveLength(1);
    const viaIdentity = await ice(1);
    expect(viaIdentity.status).toBe(401);
    expect(viaIdentity.body.error.code).toBe("auth_invalid");
    expect(calls).toHaveLength(1);
  });
});

describe("WP11 ICE endpoint authentication", () => {
  it("refuses a missing, malformed or wrongly bound Authorization header without calling the provider", async () => {
    const calls = await fakeTurn();
    const missing = await worker(new Request(BASE + ICE));
    expect(missing.status).toBe(401);
    expect(((await missing.json()) as any).error.code).toBe("auth_missing");
    const garbage = await worker(new Request(BASE + ICE, { headers: { Authorization: "Nostr not-base64!!" } }));
    expect(garbage.status).toBe(401);
    // A header signed for a different URL (the inbox) is not valid here.
    const wrongUrl = await worker(new Request(BASE + ICE, { headers: { Authorization: await authHeader(user(0).sk, "GET", BASE + "/v1/inbox") } }));
    expect(wrongUrl.status).toBe(401);
    expect(((await wrongUrl.json()) as any).error.code).toBe("auth_invalid");
    // And a POST-signed header is not valid for a GET.
    const wrongMethod = await worker(new Request(BASE + ICE, { headers: { Authorization: await authHeader(user(0).sk, "POST", BASE + ICE) } }));
    expect(wrongMethod.status).toBe(401);
    expect(calls).toHaveLength(0);
  });

  it("refuses a key that is not a member with 403 and does not call the provider", async () => {
    const calls = await fakeTurn();
    const reply = await client(OUTSIDER).request("GET", ICE);
    expect(reply.status).toBe(403);
    expect(reply.body.error.code).toBe("not_allowed");
    expect(calls).toHaveLength(0);
  });

  it("refuses a revoked device with 401 auth_revoked", async () => {
    const calls = await fakeTurn();
    const { admin, desktop } = await migratedWithDesktop(1);
    expect((await as(admin.sk, "DELETE", `/v1/devices/${dev(1)}?device=primary`)).status).toBe(200);
    const reply = await as(desktop.sk, "GET", `${ICE}?device=${dev(1)}`);
    expect(reply.status).toBe(401);
    expect(reply.body.error.code).toBe("auth_revoked");
    expect(calls).toHaveLength(0);
  });

  it("refuses a device revoked while the provider call is in flight, and returns no credentials", async () => {
    const { admin, desktop } = await migratedWithDesktop(1);
    await fakeTurn(async () => {
      expect((await as(admin.sk, "DELETE", `/v1/devices/${dev(1)}?device=primary`)).status).toBe(200);
      return ok(providerBody());
    });
    const reply = await as(desktop.sk, "GET", `${ICE}?device=${dev(1)}`);
    expect(reply.status).toBe(401);
    expect(reply.body.error.code).toBe("auth_revoked");
    expect(JSON.stringify(reply.body)).not.toContain(CRED_PASS);
  });

  it("refuses a replayed authorization and calls the provider only once", async () => {
    const calls = await fakeTurn();
    const header = await authHeader(user(0).sk, "GET", BASE + ICE);
    const first = await worker(new Request(BASE + ICE, { headers: { Authorization: header } }));
    expect(first.status).toBe(200);
    const second = await worker(new Request(BASE + ICE, { headers: { Authorization: header } }));
    expect(second.status).toBe(401);
    expect(((await second.json()) as any).error.code).toBe("auth_replayed");
    expect(calls).toHaveLength(1);
  });
});

describe("WP11 ICE endpoint request validation", () => {
  it("rejects unknown query parameters, a malformed device and non-GET methods", async () => {
    const calls = await fakeTurn();
    for (const path of [`${ICE}?ttl=99999`, `${ICE}?device=NOPE`, `${ICE}?device=primary&x=1`]) {
      const reply = await client(0).request("GET", path);
      expect(reply.status, path).toBe(400);
      expect(reply.body.error.code).toBe("bad_request");
    }
    const post = await client(0).request("POST", ICE, { v: 1 });
    expect(post.status).toBe(405);
    expect(post.body.error.code).toBe("method_not_allowed");
    const del = await client(0).request("DELETE", ICE);
    expect(del.status).toBe(405);
    expect(calls).toHaveLength(0);
  });

  it("caps request size at the edge before the mailbox is involved", async () => {
    const calls = await fakeTurn();
    const response = await worker(new Request(BASE + ICE, { headers: { Authorization: await authHeader(user(0).sk, "GET", BASE + ICE), "Content-Length": "999999999" } }));
    expect(response.status).toBe(413);
    expect(calls).toHaveLength(0);
  });
});

describe("WP11 ICE endpoint limits", () => {
  it("rate-limits per identity (6 a minute) and does not call the provider for the refused request", async () => {
    const calls = await fakeTurn();
    await setLimit("callIceCacheMs", 1); // every allowed request reaches the provider
    await new Promise((r) => setTimeout(r, 5));
    for (let i = 0; i < 6; i++) {
      expect((await ice(0)).status, `request ${i}`).toBe(200);
      await new Promise((r) => setTimeout(r, 3));
    }
    const refused = await ice(0);
    expect(refused.status).toBe(429);
    expect(refused.body).toEqual({ v: 1, error: { code: "rate_limited", retryable: true } });
    expect(calls).toHaveLength(6);
    // Another member has their own budget.
    expect((await ice(1)).status).toBe(200);
  });

  it("serves a repeat request from a per-caller cache and gives other callers their own credentials", async () => {
    let n = 0;
    const calls = await fakeTurn(() => {
      n++;
      const body = providerBody();
      body.iceServers[1].username = `user-${n}`;
      return ok(body);
    });
    const a1 = await ice(0);
    const a2 = await ice(0);
    expect(a2.body).toEqual(a1.body);
    expect(calls).toHaveLength(1);
    const b = await ice(1);
    expect(b.body.iceServers[1].username).toBe("user-2");
    expect(calls).toHaveLength(2);
    // Past the cache window the provider is asked again.
    await setLimit("callIceCacheMs", 1);
    await new Promise((r) => setTimeout(r, 5));
    const a3 = await ice(0);
    expect(a3.body.iceServers[1].username).toBe("user-3");
  });

  it("coalesces concurrent requests from one caller into one provider call", async () => {
    const calls = await fakeTurn(async () => {
      await new Promise((r) => setTimeout(r, 50));
      return ok(providerBody());
    });
    const replies = await Promise.all([ice(0), ice(0), ice(0)]);
    expect(replies.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(calls).toHaveLength(1);
  });

  it("clamps the requested lifetime to 600 s even if configured higher", async () => {
    const calls = await fakeTurn();
    await setLimit("callIceTtlS", 10_800);
    await ice(0);
    expect(JSON.parse(calls[0].body)).toEqual({ ttl: 600 });
  });
});

describe("WP11 ICE endpoint configuration and upstream failures", () => {
  it("reports not_configured when the TURN secrets are absent, without any upstream call", async () => {
    const calls = await fakeTurn(undefined, null);
    const reply = await ice(0);
    expect(reply.status).toBe(503);
    expect(reply.body).toEqual({ v: 1, error: { code: "not_configured", retryable: false } });
    expect(calls).toHaveLength(0);
  });

  it("is not configured by default in this test pool (no TURN bindings are set)", async () => {
    const reply = await ice(0);
    expect(reply.status).toBe(503);
    expect(reply.body.error.code).toBe("not_configured");
  });

  it("reads TURN_KEY_ID and TURN_API_TOKEN only from env bindings and rejects absent or malformed values", () => {
    expect(turnConfigFrom({ TURN_KEY_ID: KEY_ID, TURN_API_TOKEN: API_TOKEN })).toEqual({ keyId: KEY_ID, token: API_TOKEN });
    expect(turnConfigFrom({})).toBeNull();
    expect(turnConfigFrom({ TURN_KEY_ID: KEY_ID })).toBeNull();
    expect(turnConfigFrom({ TURN_API_TOKEN: API_TOKEN })).toBeNull();
    expect(turnConfigFrom({ TURN_KEY_ID: "", TURN_API_TOKEN: API_TOKEN })).toBeNull();
    expect(turnConfigFrom({ TURN_KEY_ID: "short", TURN_API_TOKEN: API_TOKEN })).toBeNull();
    // A key id with path characters must never reach the URL.
    expect(turnConfigFrom({ TURN_KEY_ID: "../../accounts/1234567890", TURN_API_TOKEN: API_TOKEN })).toBeNull();
    expect(turnConfigFrom({ TURN_KEY_ID: KEY_ID, TURN_API_TOKEN: "has space in it" })).toBeNull();
    expect(turnConfigFrom({ TURN_KEY_ID: KEY_ID, TURN_API_TOKEN: "x".repeat(513) })).toBeNull();
    expect(turnConfigFrom({ TURN_KEY_ID: 12345678, TURN_API_TOKEN: API_TOKEN })).toBeNull();
  });

  const failing: Array<[string, () => Response | Promise<Response>]> = [
    ["a 500", () => new Response("boom", { status: 500 })],
    ["Cloudflare error 1010 (blocked client)", () => new Response("error code: 1010", { status: 403 })],
    ["a redirect", () => new Response(null, { status: 302, headers: { location: "https://evil.example/steal" } })],
    ["malformed JSON", () => new Response("{not json", { status: 201 })],
    ["a JSON array", () => ok([1, 2, 3])],
    ["no iceServers", () => ok({ other: true })],
    ["no TURN server in the list", () => ok({ iceServers: [{ urls: ["stun:stun.cloudflare.com:3478"] }] })],
    ["a TURN server without credentials", () => ok({ iceServers: [{ urls: ["turn:turn.cloudflare.com:3478?transport=udp"] }] })],
    ["a TURN host outside cloudflare.com", () => ok({ iceServers: [{ urls: ["turn:turn.evil.example:3478"], username: "u", credential: "c" }] })],
    ["a non-ICE URL scheme", () => ok({ iceServers: [{ urls: ["https://turn.cloudflare.com/x"], username: "u", credential: "c" }] })],
    ["only port-53 TURN URLs", () => ok({ iceServers: [{ urls: ["turn:turn.cloudflare.com:53?transport=udp"], username: "u", credential: "c" }] })],
    ["an oversized credential", () => ok({ iceServers: [{ urls: ["turn:turn.cloudflare.com:3478"], username: "u".repeat(600), credential: "c" }] })],
  ];

  it.each(failing)("answers 502 upstream_failed (retryable) for %s", async (_name, respond) => {
    const calls = await fakeTurn(respond);
    const reply = await ice(0);
    expect(reply.status).toBe(502);
    expect(reply.body).toEqual({ v: 1, error: { code: "upstream_failed", retryable: true } });
    // Never follows a redirect: exactly one outbound request.
    expect(calls).toHaveLength(1);
  });

  it("does not cache a failure: the next request asks the provider again", async () => {
    let healthy = false;
    const calls = await fakeTurn(() => (healthy ? ok(providerBody()) : new Response("no", { status: 500 })));
    expect((await ice(0)).status).toBe(502);
    healthy = true;
    expect((await ice(0)).status).toBe(200);
    expect(calls).toHaveLength(2);
  });

  it("bounds the provider response: a body over 8 KiB is refused, also when it is streamed without a length", async () => {
    await fakeTurn(() => new Response("x".repeat(9000), { status: 201, headers: { "content-length": "9000" } }));
    expect((await ice(0)).status).toBe(502);
    let sent = 0;
    const stream = new ReadableStream({
      pull(controller) {
        sent += 1024;
        controller.enqueue(new Uint8Array(1024).fill(32));
        if (sent > 5_000_000) controller.close();
      },
    });
    await fakeTurn(() => new Response(stream, { status: 201 }));
    expect((await ice(1)).status).toBe(502);
    // The reader stopped long before the 5 MB the fake was willing to produce.
    expect(sent).toBeLessThan(200_000);
  });

  it("times out a provider that never answers", async () => {
    await fakeTurn(() => new Promise<Response>(() => {}));
    await setLimit("callIceTimeoutMs", 50);
    const started = Date.now();
    const reply = await ice(0);
    expect(reply.status).toBe(502);
    expect(reply.body.error.code).toBe("upstream_failed");
    expect(Date.now() - started).toBeLessThan(2000);
  });

  it("accepts the dictionary form of iceServers, a single URL string, and drops unknown fields", async () => {
    await fakeTurn(() => ok({
      iceServers: { urls: "turn:turn.cloudflare.com:3478?transport=udp", username: CRED_USER, credential: CRED_PASS, extra: "x", ttl: 5 },
      unrelated: { secret: API_TOKEN },
    }));
    const reply = await ice(0);
    expect(reply.status).toBe(200);
    expect(reply.body.iceServers).toEqual([{ urls: ["turn:turn.cloudflare.com:3478?transport=udp"], username: CRED_USER, credential: CRED_PASS }]);
    expect(JSON.stringify(reply.body)).not.toContain(API_TOKEN);
  });
});
