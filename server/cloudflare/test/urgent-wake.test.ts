import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { authHeader, newIdentity, wrapDm, type Reply } from "../client/mailbox-client";
import { DEFAULT_LIMITS } from "../src/protocol";
import { URGENT_WAKE_TTL_S } from "../src/push";
import type { Mailbox } from "../src/mailbox";
import { setLimit } from "./call-helpers";
import { dev } from "./device-helpers";
import { BASE, client, connect, mailboxStub, newId, user, worker } from "./helpers";
import { OUTSIDER } from "./identities";

/**
 * WP11-AC2 / G4 — `urgent` on a delivery. The server cannot read the sealed envelope, so the flag is bounded
 * by budgets instead of by content: one urgent delivery per request, never to oneself, 1 per recipient per
 * 3 s, a per-sender and a global per-minute budget, and an FCM message that lives at most 45 s.
 */

interface Captured { url: string; body: string }

async function fakeGoogle(fcmStatus = 200, fcmBody = "{}"): Promise<Captured[]> {
  const captured: Captured[] = [];
  await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
    (instance as any).pushFetch = async (request: Request) => {
      captured.push({ url: request.url, body: new TextDecoder().decode(await request.arrayBuffer()) });
      if (request.url.startsWith("https://oauth2.googleapis.com/")) {
        return new Response(JSON.stringify({ access_token: "test-access-token", expires_in: 3600 }), { headers: { "content-type": "application/json" } });
      }
      return new Response(fcmBody, { status: fcmStatus });
    };
  });
  return captured;
}
const fcm = (captured: Captured[]) => captured.filter((c) => c.url.includes("fcm.googleapis.com"));
const message = (c: Captured) => JSON.parse(c.body).message;

async function settle(predicate: () => boolean, ms = 1500) {
  const deadline = Date.now() + ms;
  while (!predicate() && Date.now() < deadline) await new Promise((r) => setTimeout(r, 20));
}
const quiet = (ms = 200) => new Promise((r) => setTimeout(r, ms));

const registerToken = (i: number, token = `device-token-${i}`) => client(i).request("POST", "/v1/push-token", { v: 1, token });

interface Raw { to?: number; id?: string; envelope?: string; urgent?: unknown }
function delivery(from: number, d: Raw) {
  const to = d.to ?? 1;
  const out: Record<string, unknown> = { to: user(to).pk, id: d.id ?? newId(), envelope: d.envelope ?? wrapDm(user(from), user(to).pk, "ring") };
  if ("urgent" in d) out.urgent = d.urgent;
  return out;
}
const send = (from: number, ...ds: Raw[]) => client(from).request("POST", "/v1/send", { v: 1, deliveries: ds.map((d) => delivery(from, d)) });
const urgent = (from: number, to = 1, extra: Raw = {}) => send(from, { to, urgent: true, ...extra });

describe("WP11-AC2 urgent wake", () => {
  it("sends a high-priority, non-collapsible FCM with urgent=1, a 45 s lifetime and no content", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const reply = await urgent(0, 1);
    expect(reply.status).toBe(200);
    await settle(() => fcm(captured).length > 0);
    expect(fcm(captured)).toHaveLength(1);
    expect(message(fcm(captured)[0])).toEqual({
      token: "device-token-1",
      data: { type: "wake", urgent: "1" },
      android: { priority: "high", ttl: "45s" },
    });
    expect(message(fcm(captured)[0]).android).not.toHaveProperty("collapse_key");
    expect(fcm(captured)[0].body).not.toContain(user(0).pk);
    expect(fcm(captured)[0].body).not.toContain("ring");
    expect(URGENT_WAKE_TTL_S).toBeLessThanOrEqual(60);
  });

  it("bypasses the 20 s wake coalescing that governs ordinary messages", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    await send(0, {});
    await settle(() => fcm(captured).length === 1);
    expect(message(fcm(captured)[0]).data).toEqual({ type: "wake" });
    // Inside the 20 s window a second ordinary message is coalesced...
    await send(0, {});
    await quiet();
    expect(fcm(captured)).toHaveLength(1);
    // ...but an urgent one is not.
    expect((await urgent(0, 1)).status).toBe(200);
    await settle(() => fcm(captured).length === 2);
    expect(message(fcm(captured)[1]).data).toEqual({ type: "wake", urgent: "1" });
  });

  it("makes an ordinary message right after an urgent wake coalesce into it", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    await urgent(0, 1);
    await settle(() => fcm(captured).length === 1);
    await send(2, {});
    await quiet();
    expect(fcm(captured)).toHaveLength(1);
  });

  it("allows one urgent wake per recipient every 3 s and refuses the next with a retryable 429, storing nothing", async () => {
    expect(DEFAULT_LIMITS.urgentRecipientIntervalMs).toBe(3000);
    const captured = await fakeGoogle();
    await registerToken(1);
    expect((await urgent(0, 1)).status).toBe(200);
    const second = await urgent(2, 1);
    expect(second.status).toBe(429);
    expect(second.body).toEqual({ v: 1, error: { code: "rate_limited", retryable: true } });
    expect((await client(1).inbox()).body.messages).toHaveLength(1);
    await settle(() => fcm(captured).length >= 1);
    await quiet();
    expect(fcm(captured)).toHaveLength(1);
    // A different recipient is unaffected.
    await registerToken(3);
    expect((await urgent(2, 3)).status).toBe(200);
    // After the interval the same recipient can be rung again.
    await setLimit("urgentRecipientIntervalMs", 100);
    await quiet(150);
    expect((await urgent(2, 1)).status).toBe(200);
    await settle(() => fcm(captured).length === 3);
    expect(fcm(captured)).toHaveLength(3);
  });

  it("bounds each sender per minute and the whole server per minute", async () => {
    await setLimit("urgentRecipientIntervalMs", 1);
    await setLimit("urgentPerSenderPerMinute", 2);
    await setLimit("urgentGlobalPerMinute", 4);
    expect(DEFAULT_LIMITS.urgentPerSenderPerMinute).toBe(6);
    expect(DEFAULT_LIMITS.urgentGlobalPerMinute).toBe(60);
    const step = () => quiet(5);
    expect((await urgent(0, 1)).status).toBe(200);
    await step();
    expect((await urgent(0, 2)).status).toBe(200);
    await step();
    const sender = await urgent(0, 3);
    expect(sender.status).toBe(429);
    expect(sender.body.error.code).toBe("rate_limited");
    // Other senders still have budget, until the global one runs out.
    expect((await urgent(4, 5)).status).toBe(200);
    await step();
    expect((await urgent(4, 6)).status).toBe(200);
    await step();
    const global = await urgent(7, 8);
    expect(global.status).toBe(429);
    expect(global.body.error.code).toBe("rate_limited");
  });

  it("does not push to a recipient who is connected, but still delivers live", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const socket = await connect(1);
    expect((await urgent(0, 1)).status).toBe(200);
    expect((await socket.next()).type).toBe("message");
    await quiet();
    expect(fcm(captured)).toHaveLength(0);
    socket.close();
  });

  it("stores the invite like any message: the inbox entry has no urgent marker", async () => {
    await fakeGoogle();
    const envelope = wrapDm(user(0), user(1).pk, "invite");
    const id = newId();
    const reply = await send(0, { to: 1, id, envelope, urgent: true });
    expect(JSON.stringify(reply.body)).toBe(JSON.stringify({ v: 1, results: [{ to: user(1).pk, id, status: "stored", seq: reply.body.results[0].seq }] }));
    const inbox = await client(1).inbox(0);
    expect(Object.keys(inbox.body.messages[0])).toEqual(["seq", "from", "id", "envelope", "storedAt"]);
    expect(inbox.body.messages[0].envelope).toBe(envelope);
  });

  it("falls back to dropping a dead token exactly like an ordinary wake", async () => {
    const captured = await fakeGoogle(404, JSON.stringify({ error: { details: [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "UNREGISTERED" }] } }));
    await registerToken(1);
    await urgent(0, 1);
    await settle(() => fcm(captured).length === 1);
    await quiet();
    const rows = await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => state.storage.sql.exec("SELECT COUNT(*) AS n FROM push_tokens").one().n);
    expect(rows).toBe(0);
  });
});

describe("WP11 urgent flag validation", () => {
  it.each([
    ["a string", "yes"],
    ["a number", 1],
    ["null", null],
    ["an object", {}],
  ])("rejects urgent given as %s with 400 bad_request and stores nothing", async (_name, value) => {
    const reply = await send(0, { to: 1, urgent: value });
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("bad_request");
    expect((await client(1).inbox()).body.messages).toHaveLength(0);
  });

  it("rejects more than one urgent delivery in a request", async () => {
    const reply = await send(0, { to: 1, urgent: true }, { to: 2, urgent: true });
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("bad_request");
    expect((await client(1).inbox()).body.messages).toHaveLength(0);
    expect((await client(2).inbox()).body.messages).toHaveLength(0);
  });

  it("allows one urgent delivery next to ordinary ones", async () => {
    const reply = await send(0, { to: 1, urgent: true }, { to: 2 }, { to: 3, urgent: false });
    expect(reply.status).toBe(200);
    expect(reply.body.results.map((r: any) => r.status)).toEqual(["stored", "stored", "stored"]);
  });

  it("rejects an urgent delivery addressed to the sender's own identity", async () => {
    const reply = await send(0, { to: 0, urgent: true });
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("bad_request");
  });

  it("rejects an oversize urgent envelope per delivery and does not spend the budget", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const big = await urgent(0, 1, { envelope: "x".repeat(8 * 1024 + 1) });
    expect(big.status).toBe(200);
    expect(big.body.results[0]).toMatchObject({ status: "rejected", error: { code: "envelope_too_large", retryable: false } });
    await quiet();
    expect(fcm(captured)).toHaveLength(0);
    expect((await urgent(0, 1)).body.results[0].status).toBe("stored");
    await settle(() => fcm(captured).length === 1);
    expect(fcm(captured)).toHaveLength(1);
  });

  it("an unknown recipient is rejected and does not spend the budget", async () => {
    await fakeGoogle();
    const reply = await send(0, { to: OUTSIDER, urgent: true });
    expect(reply.body.results[0]).toMatchObject({ status: "rejected", error: { code: "unknown_recipient" } });
    expect((await urgent(0, 1)).body.results[0].status).toBe("stored");
  });

  it("a resent duplicate is not woken again", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    await setLimit("urgentRecipientIntervalMs", 50);
    const id = newId();
    const envelope = wrapDm(user(0), user(1).pk, "ring");
    expect((await send(0, { to: 1, id, envelope, urgent: true })).body.results[0].status).toBe("stored");
    await quiet(80);
    expect((await send(0, { to: 1, id, envelope, urgent: true })).body.results[0].status).toBe("duplicate");
    await quiet();
    expect(fcm(captured)).toHaveLength(1);
  });
});

describe("WP11 urgent flag authentication", () => {
  it("refuses an unauthenticated urgent send", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const body = JSON.stringify({ v: 1, deliveries: [delivery(0, { to: 1, urgent: true })] });
    const none = await worker(new Request(BASE + "/v1/send", { method: "POST", body }));
    expect(none.status).toBe(401);
    const forged = await worker(new Request(BASE + "/v1/send", { method: "POST", body, headers: { Authorization: "Nostr bm9wZQ==" } }));
    expect(forged.status).toBe(401);
    await quiet();
    expect(fcm(captured)).toHaveLength(0);
  });

  it("refuses a non-member sender", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const reply = await client(OUTSIDER).request("POST", "/v1/send", { v: 1, deliveries: [delivery(OUTSIDER, { to: 1, urgent: true })] });
    expect(reply.status).toBe(403);
    await quiet();
    expect(fcm(captured)).toHaveLength(0);
  });

  it("refuses a revoked device, and the refused request spends no urgent budget", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const admin = newIdentity();
    const desktop = newIdentity();
    const signed = async (sk: Uint8Array, method: string, path: string, body?: unknown): Promise<Reply> => {
      const text = body === undefined ? undefined : JSON.stringify(body);
      const res = await worker(new Request(BASE + path, { method, body: text, headers: { Authorization: await authHeader(sk, method, BASE + path, text) } }));
      return { status: res.status, body: await res.json().catch(() => null), headers: res.headers };
    };
    expect((await signed(user(0).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk })).status).toBe(200);
    expect((await signed(admin.sk, "POST", "/v1/devices?device=primary", { v: 1, device: dev(1), label: "d", auth_pubkey: desktop.pk })).status).toBe(200);
    expect((await signed(admin.sk, "DELETE", `/v1/devices/${dev(1)}?device=primary`)).status).toBe(200);
    const reply = await signed(desktop.sk, "POST", `/v1/send?device=${dev(1)}`, { v: 1, deliveries: [delivery(0, { to: 1, urgent: true })] });
    expect(reply.status).toBe(401);
    expect(reply.body.error.code).toBe("auth_revoked");
    await quiet();
    expect(fcm(captured)).toHaveLength(0);
    // The primary phone can still ring straight away: the refused call did not use the recipient window.
    expect((await signed(admin.sk, "POST", "/v1/send?device=primary", { v: 1, deliveries: [delivery(0, { to: 1, urgent: true })] })).status).toBe(200);
  });

  it("refuses a replayed urgent send and wakes once", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const text = JSON.stringify({ v: 1, deliveries: [delivery(0, { to: 1, urgent: true })] });
    const header = await authHeader(user(0).sk, "POST", BASE + "/v1/send", text);
    const call = () => worker(new Request(BASE + "/v1/send", { method: "POST", body: text, headers: { Authorization: header } }));
    expect((await call()).status).toBe(200);
    await setLimit("urgentRecipientIntervalMs", 1);
    await quiet(10);
    const again = await call();
    expect(again.status).toBe(401);
    expect(((await again.json()) as any).error.code).toBe("auth_replayed");
    await settle(() => fcm(captured).length === 1);
    await quiet();
    expect(fcm(captured)).toHaveLength(1);
    expect((await client(1).inbox()).body.messages).toHaveLength(1);
  });
});

describe("WP11 legacy sends are unchanged by the urgent flag", () => {
  it("replies byte for byte as before and keeps the collapsible wake for ordinary and urgent:false sends", async () => {
    const captured = await fakeGoogle();
    await registerToken(1);
    const id = newId();
    const envelope = wrapDm(user(0), user(1).pk, "legacy");
    const reply = await client(0).send([{ to: user(1).pk, id, envelope }]);
    expect(JSON.stringify(reply.body)).toBe(JSON.stringify({ v: 1, results: [{ to: user(1).pk, id, status: "stored", seq: reply.body.results[0].seq }] }));
    await settle(() => fcm(captured).length === 1);
    expect(message(fcm(captured)[0])).toEqual({ token: "device-token-1", data: { type: "wake" }, android: { priority: "high", ttl: "86400s", collapse_key: "relay-wake" } });
    // Exact serialization, not just structure.
    expect(fcm(captured)[0].body).toBe('{"message":{"token":"device-token-1","data":{"type":"wake"},"android":{"priority":"high","ttl":"86400s","collapse_key":"relay-wake"}}}');
    const flagged = await send(2, { to: 3, urgent: false });
    expect(Object.keys(flagged.body)).toEqual(["v", "results"]);
    expect(Object.keys(flagged.body.results[0])).toEqual(["to", "id", "status", "seq"]);
  });

  it("an urgent:false send is not subject to the urgent limiter", async () => {
    await fakeGoogle();
    for (let i = 0; i < 4; i++) expect((await send(0, { to: 1, urgent: false })).status).toBe(200);
  });
});
