import { describe, expect, it } from "vitest";
import { authHeader } from "../client/mailbox-client";
import { OUTSIDER } from "./identities";
import { BASE, client, signedRequest, upgrade, user, worker } from "./helpers";

const sendBody = (to: string) => JSON.stringify({ v: 1, deliveries: [{ to, id: "a1", envelope: "{}" }] });
const now = () => Math.floor(Date.now() / 1000);

/** Every authenticated route, as [method, path, body]. */
const routes: Array<[string, string, string | undefined]> = [
  ["POST", "/v1/send", sendBody("1".repeat(64))],
  ["GET", "/v1/inbox?after=0", undefined],
  ["POST", "/v1/ack", JSON.stringify({ v: 1, through: 0 })],
];

describe("edge router", () => {
  it("answers health without auth or a Durable Object", async () => {
    const res = await worker(new Request(`${BASE}/v1/health`));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true, v: 1 });
    expect(res.headers.get("x-relay-instance")).toBeNull();
  });

  it("rejects unknown routes", async () => {
    const res = await worker(new Request(`${BASE}/v1/anything`));
    expect(res.status).toBe(404);
  });

  it.each(routes)("rejects anonymous %s %s at the edge, before the mailbox", async (method, path, body) => {
    const res = await worker(new Request(BASE + path, { method, body }));
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: { code: "auth_missing" } });
    expect(res.headers.get("x-relay-instance")).toBeNull();
  });

  it("rejects a non-Nostr authorization scheme", async () => {
    const res = await worker(new Request(`${BASE}/v1/inbox`, { headers: { Authorization: "Bearer abc" } }));
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: { code: "auth_invalid" } });
  });
});

describe("NIP-98 request auth", () => {
  it.each(routes)("accepts a correctly signed %s %s", async (method, path, body) => {
    const res = await signedRequest(0, method, path, body);
    expect(res.status).toBe(200);
  });

  it("rejects an undecodable header", async () => {
    const res = await worker(new Request(`${BASE}/v1/inbox`, { headers: { Authorization: "Nostr not-base64!!" } }));
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: { code: "auth_invalid" } });
  });

  const wrong: Array<[string, Parameters<typeof signedRequest>[4]]> = [
    ["another event kind", { kind: 1 }],
    ["another URL", { url: `${BASE}/v1/inbox?after=5` }],
    ["another host", { url: "https://elsewhere.test/v1/send" }],
    ["another method", { method: "GET" }],
    ["another body", { payload: "0".repeat(64) }],
    ["no body hash", { payload: null }],
  ];

  it.each(wrong)("rejects a send signed for %s", async (_, opts) => {
    const res = await signedRequest(0, "POST", "/v1/send", sendBody(user(1).pk), opts);
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: { code: "auth_invalid" } });
  });

  it("rejects a forged signature", async () => {
    const header = await authHeader(user(0).sk, "GET", `${BASE}/v1/inbox`);
    const event = JSON.parse(atob(header.slice(6)));
    event.sig = (event.sig[0] === "0" ? "1" : "0") + event.sig.slice(1);
    const res = await worker(new Request(`${BASE}/v1/inbox`, { headers: { Authorization: `Nostr ${btoa(JSON.stringify(event))}` } }));
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: { code: "auth_invalid" } });
  });

  it("rejects another identity's pubkey on a signature it didn't make", async () => {
    const header = await authHeader(user(0).sk, "GET", `${BASE}/v1/inbox`);
    const event = JSON.parse(atob(header.slice(6)));
    event.pubkey = user(1).pk;
    const res = await worker(new Request(`${BASE}/v1/inbox`, { headers: { Authorization: `Nostr ${btoa(JSON.stringify(event))}` } }));
    expect(res.status).toBe(401);
  });

  it.each([["stale", -120], ["future", 120]])("rejects %s auth", async (_, offset) => {
    const res = await signedRequest(0, "GET", "/v1/inbox", undefined, { createdAt: now() + offset });
    expect(res.status).toBe(401);
    expect(await res.json()).toMatchObject({ error: { code: "auth_expired" } });
  });

  it.each(routes)("rejects a replayed %s %s", async (method, path, body) => {
    const url = BASE + path;
    const header = await authHeader(user(0).sk, method, url, body);
    const make = () => worker(new Request(url, { method, body, headers: { Authorization: header } }));
    expect((await make()).status).toBe(200);
    const replay = await make();
    expect(replay.status).toBe(401);
    expect(await replay.json()).toMatchObject({ error: { code: "auth_replayed" } });
  });

  it("refuses a validly signed identity that is not on the allowlist", async () => {
    const res = await signedRequest(OUTSIDER, "GET", "/v1/inbox");
    expect(res.status).toBe(403);
    expect(await res.json()).toMatchObject({ error: { code: "not_allowed" } });
  });
});

describe("WebSocket upgrade auth", () => {
  it("refuses an anonymous upgrade", async () => {
    const res = await worker(new Request(`${BASE}/v1/ws`, { headers: { Upgrade: "websocket" } }));
    expect(res.status).toBe(401);
    expect(res.webSocket).toBeNull();
  });

  it("refuses an outsider's upgrade", async () => {
    const res = await upgrade(OUTSIDER);
    expect(res.status).toBe(403);
  });

  it("refuses an upgrade signed for a different path", async () => {
    const header = await authHeader(user(0).sk, "GET", "wss://mailbox.test/v1/inbox");
    const res = await upgrade(0, { Authorization: header });
    expect(res.status).toBe(401);
  });

  it("refuses a replayed upgrade", async () => {
    const headers = await client(0).socketHeaders();
    const first = await upgrade(0, headers);
    expect(first.status).toBe(101);
    first.webSocket!.accept();
    first.webSocket!.close();
    const replay = await upgrade(0, headers);
    expect(replay.status).toBe(401);
    expect(await replay.json()).toMatchObject({ error: { code: "auth_replayed" } });
  });

  it("requires the Upgrade header", async () => {
    const c = client(0);
    const res = await worker(new Request(`${BASE}/v1/ws`, { headers: await c.socketHeaders() }));
    expect(res.status).toBe(426);
  });
});
