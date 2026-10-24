import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { authHeader, newIdentity, wrapDm } from "../client/mailbox-client";
import type { Mailbox } from "../src/mailbox";
import { fakeTurn, rowCounters, setLimit, sqlRows } from "./call-helpers";
import { BASE, TestSocket, client, connect, mailboxStub, newId, user, worker } from "./helpers";
import { dev } from "./device-helpers";

/**
 * Row costs of the WP11 paths, from x-relay-sql-rows (EXPOSE_METRICS=1 in the test pool) and, for work done
 * after the response (the wake-up push), from the object's own row counters. The printed `WP11-ROWS` line is
 * what linux-port/evidence/WP11.md quotes; the assertions are ceilings, not the measurements.
 */

const quiet = (ms = 150) => new Promise((r) => setTimeout(r, ms));
const fakeGoogle = () =>
  runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
    (instance as any).pushFetch = async (request: Request) =>
      request.url.startsWith("https://oauth2.googleapis.com/")
        ? new Response(JSON.stringify({ access_token: "t", expires_in: 3600 }))
        : new Response("{}", { status: 200 });
  });

const send = (from: number, to: number, urgent?: boolean) =>
  client(from).request("POST", "/v1/send", {
    v: 1,
    deliveries: [{ to: user(to).pk, id: newId(), envelope: wrapDm(user(from), user(to).pk, "x"), ...(urgent === undefined ? {} : { urgent }) }],
  });

async function delta<T>(work: () => Promise<T>): Promise<{ result: T; read: number; written: number }> {
  await quiet(50);
  const before = await rowCounters();
  const result = await work();
  await quiet(150);
  const after = await rowCounters();
  return { result, read: after.read - before.read, written: after.written - before.written };
}

describe("WP11 row costs", () => {
  it("measures the ICE endpoint, the urgent wake and the signal relay", async () => {
    const m: Record<string, { read: number; written: number }> = {};

    // --- GET /v1/call/ice ---------------------------------------------------------------------
    await fakeTurn();
    const miss = await client(0).request("GET", "/v1/call/ice");
    m.ice_miss = sqlRows(miss.headers);
    const hit = await client(0).request("GET", "/v1/call/ice");
    m.ice_cache_hit = sqlRows(hit.headers);
    await fakeTurn(undefined, null);
    m.ice_not_configured = sqlRows((await client(1).request("GET", "/v1/call/ice")).headers);
    await fakeTurn();
    await setLimit("callIcePerMinute", 1);
    await client(2).request("GET", "/v1/call/ice");
    const limited = await client(2).request("GET", "/v1/call/ice");
    expect(limited.status).toBe(429);
    m.ice_rate_limited = limited.headers.get("x-relay-sql-rows") ? sqlRows(limited.headers) : { read: 0, written: 0 };

    // --- send: ordinary wake vs urgent wake (token registered, recipient offline) -------------------
    await fakeGoogle();
    await client(3).request("POST", "/v1/push-token", { v: 1, token: "device-token-3" });
    await client(4).request("POST", "/v1/push-token", { v: 1, token: "device-token-4" });
    await send(0, 6); // warm-up: the first write also runs the once-a-minute prune, which is not a per-send cost
    const ordinary = await delta(() => send(0, 3));
    m.send_ordinary_with_wake = { read: ordinary.read, written: ordinary.written };
    const plain = await delta(() => send(0, 5));
    m.send_ordinary_no_token = { read: plain.read, written: plain.written };
    const urgent = await delta(() => send(0, 4, true));
    m.send_urgent_with_wake = { read: urgent.read, written: urgent.written };
    expect(urgent.result.status).toBe(200);
    const refused = await delta(() => send(1, 4, true));
    expect(refused.result.status).toBe(429);
    m.send_urgent_refused = { read: refused.read, written: refused.written };

    // --- websocket signal relay -------------------------------------------------------------------
    const alice = await connect(6);
    const bob = await connect(7);
    const bob2 = await connect(7);
    const one = await delta(async () => {
      alice.send({ type: "signal", to: user(8).pk, envelope: "nobody-online" });
    });
    m.signal_no_target_socket = { read: one.read, written: one.written };
    const two = await delta(async () => {
      alice.send({ type: "signal", to: user(7).pk, envelope: "ring" });
      await bob.next();
      await bob2.next();
    });
    m.signal_two_target_sockets = { read: two.read, written: two.written };
    for (const s of [alice, bob, bob2]) s.close();

    // A migrated identity: every target socket is re-checked against the device table on each signal.
    const admin = newIdentity();
    const desktop = newIdentity();
    const signed = async (sk: Uint8Array, method: string, path: string, body?: unknown) => {
      const text = body === undefined ? undefined : JSON.stringify(body);
      return worker(new Request(BASE + path, { method, body: text, headers: { Authorization: await authHeader(sk, method, BASE + path, text) } }));
    };
    expect((await signed(user(9).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk })).status).toBe(200);
    expect((await signed(admin.sk, "POST", "/v1/devices?device=primary", { v: 1, device: dev(1), label: "d", auth_pubkey: desktop.pk })).status).toBe(200);
    const wsUrl = `${BASE.replace(/^http/, "ws")}/v1/ws?device=${dev(1)}`;
    const upgraded = await worker(new Request(`${BASE}/v1/ws?device=${dev(1)}`, { headers: { Upgrade: "websocket", Authorization: await authHeader(desktop.sk, "GET", wsUrl) } }));
    const linked = new TestSocket(upgraded.webSocket!);
    await linked.next();
    const sender = await connect(6);
    const migrated = await delta(async () => {
      sender.send({ type: "signal", to: user(9).pk, envelope: "ring" });
      await linked.next();
    });
    m.signal_one_enrolled_device_socket = { read: migrated.read, written: migrated.written };
    sender.close();
    linked.close();

    console.log("WP11-ROWS " + JSON.stringify(m));
    // Ceilings: replay record only (1 insert plus its index entry), no storage for the credentials themselves.
    expect(m.ice_miss.written).toBeLessThanOrEqual(1);
    expect(m.ice_cache_hit.written).toBeLessThanOrEqual(1);
    expect(m.ice_not_configured.written).toBeLessThanOrEqual(1);
    expect(m.ice_rate_limited.written).toBe(0);
    // An urgent wake costs the same writes as an ordinary one: the replay record, the message, the last-wake stamp.
    expect(m.send_urgent_with_wake.written).toBeLessThanOrEqual(m.send_ordinary_with_wake.written);
    expect(m.send_urgent_refused.written).toBe(0);
    expect(m.signal_two_target_sockets.written).toBe(0);
    expect(m.signal_no_target_socket.written).toBe(0);
    expect(m.signal_one_enrolled_device_socket.written).toBe(0);
    expect(m.signal_one_enrolled_device_socket.read).toBeLessThanOrEqual(6);
  });
});
