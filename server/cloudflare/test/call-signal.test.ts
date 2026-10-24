import { describe, expect, it } from "vitest";
import { authHeader, newIdentity, type Identity, type Reply } from "../client/mailbox-client";
import { DEFAULT_LIMITS, limitsFrom } from "../src/protocol";
import { dev } from "./device-helpers";
import { BASE, TestSocket, client, connect, upgrade, user, worker } from "./helpers";

/**
 * WP11 step 3 / G4 — call signalling over the websocket `signal` frame. WP02 measured the real encrypted call
 * frames (largest whole frame under 14 KiB, every envelope under the 16 KiB cap) and decided
 * "leave MAX_SIGNAL_BYTES unchanged". The WP says to raise it only if WP02 says so, so the default stays 16 KiB
 * and MAX_SIGNAL_BYTES stays an environment override. These tests pin the bound and the fan-out rules a call
 * depends on; none of this code path is new behaviour, so they pass on first run (see evidence: mutation checks).
 */

const KIB = 1024;

async function as(sk: Uint8Array, method: string, path: string, body?: unknown): Promise<Reply> {
  const url = BASE + path;
  const text = body === undefined ? undefined : JSON.stringify(body);
  const headers: Record<string, string> = { Authorization: await authHeader(sk, method, url, text) };
  if (text !== undefined) headers["content-type"] = "application/json";
  const response = await worker(new Request(url, { method, headers, body: text }));
  return { status: response.status, body: await response.json().catch(() => null), headers: response.headers };
}

async function deviceSocket(sk: Uint8Array, device: string): Promise<TestSocket> {
  const path = `/v1/ws?device=${device}`;
  const reply = await worker(new Request(BASE + path, { headers: { Upgrade: "websocket", Authorization: await authHeader(sk, "GET", (BASE + path).replace(/^http/, "ws")) } }));
  if (reply.status !== 101 || !reply.webSocket) throw new Error(`upgrade refused: ${reply.status}`);
  const socket = new TestSocket(reply.webSocket);
  expect((await socket.next()).type).toBe("ready");
  return socket;
}

describe("signal size bound (MAX_SIGNAL_BYTES)", () => {
  it("keeps the 16 KiB default, as WP02 measured, and still honours the environment override", () => {
    expect(DEFAULT_LIMITS.maxSignalBytes).toBe(16 * KIB);
    expect(limitsFrom({}).maxSignalBytes).toBe(16 * KIB);
    expect(limitsFrom({ MAX_SIGNAL_BYTES: String(32 * KIB) }).maxSignalBytes).toBe(32 * KIB);
    expect(limitsFrom({ MAX_SIGNAL_BYTES: "0" }).maxSignalBytes).toBe(16 * KIB);
    expect(limitsFrom({ MAX_SIGNAL_BYTES: "lots" }).maxSignalBytes).toBe(16 * KIB);
  });

  it("relays an envelope of exactly 16,384 bytes and refuses 16,385", async () => {
    const alice = await connect(5);
    const bob = await connect(6);
    // SIGNALS_PER_MINUTE is 5 in this pool: three frames here.
    alice.send({ type: "signal", to: user(6).pk, envelope: "e".repeat(16 * KIB), ref: "a" });
    expect((await bob.next()).envelope).toHaveLength(16 * KIB);
    alice.send({ type: "signal", to: user(6).pk, envelope: "e".repeat(16 * KIB + 1), ref: "b" });
    expect(await alice.next()).toMatchObject({ type: "error", ref: "b", error: { code: "envelope_too_large", retryable: false } });
    expect(await bob.silent()).toBe(true);
    alice.close();
    bob.close();
  });

  it("measures UTF-8 bytes, not characters", async () => {
    const alice = await connect(5);
    const bob = await connect(6);
    alice.send({ type: "signal", to: user(6).pk, envelope: "é".repeat(8 * KIB), ref: "fits" });
    expect((await bob.next()).envelope).toHaveLength(8 * KIB);
    alice.send({ type: "signal", to: user(6).pk, envelope: "é".repeat(8 * KIB + 1), ref: "over" });
    expect(await alice.next()).toMatchObject({ ref: "over", error: { code: "envelope_too_large" } });
    alice.close();
    bob.close();
  });

  it("relays a call-sized (14 KiB) frame intact", async () => {
    const alice = await connect(5);
    const bob = await connect(6);
    const envelope = JSON.stringify({ kind: 1059, content: btoa("z".repeat(10 * KIB)) }).slice(0, 14 * KIB);
    alice.send({ type: "signal", to: user(6).pk, envelope });
    expect(await bob.next()).toEqual({ type: "signal", from: user(5).pk, envelope });
    alice.close();
    bob.close();
  });
});

describe("signal fan-out is limited to the addressed identity", () => {
  it("reaches every socket of the addressed identity, and nobody else, including not the sender", async () => {
    const alice = await connect(5);
    const phone = await connect(6);
    const desktop = await connect(6);
    const carol = await connect(7);
    alice.send({ type: "signal", to: user(6).pk, envelope: "invite" });
    expect(await phone.next()).toEqual({ type: "signal", from: user(5).pk, envelope: "invite" });
    expect(await desktop.next()).toEqual({ type: "signal", from: user(5).pk, envelope: "invite" });
    expect(await carol.silent()).toBe(true);
    expect(await alice.silent()).toBe(true);
    for (const s of [alice, phone, desktop, carol]) s.close();
  });

  it("caps the fan-out at the socket limit: the fifth socket evicts the oldest", async () => {
    const alice = await connect(5);
    const sockets: TestSocket[] = [];
    for (let i = 0; i < 5; i++) sockets.push(await connect(6));
    expect(await sockets[0].closed).toMatchObject({ code: 4008 });
    alice.send({ type: "signal", to: user(6).pk, envelope: "ring" });
    for (const s of sockets.slice(1)) expect((await s.next()).envelope).toBe("ring");
    for (const s of [alice, ...sockets]) s.close();
  });

  it("answered_elsewhere: a signal addressed to oneself reaches the identity's other sockets", async () => {
    const phone = await connect(5);
    const desktop = await connect(5);
    phone.send({ type: "signal", to: user(5).pk, envelope: "answered-elsewhere" });
    expect(await desktop.next()).toEqual({ type: "signal", from: user(5).pk, envelope: "answered-elsewhere" });
    phone.close();
    desktop.close();
  });

  it("never stores a signal and never replays it to a socket that connects later", async () => {
    const alice = await connect(5);
    alice.send({ type: "signal", to: user(6).pk, envelope: "invite" });
    const late = await connect(6);
    expect(await late.silent()).toBe(true);
    expect((await client(6).inbox()).body.messages).toHaveLength(0);
    alice.close();
    late.close();
  });
});

describe("signal frame validation", () => {
  it.each([
    ["no recipient", { type: "signal", envelope: "x" }, "bad_request"],
    ["a non-string envelope", { type: "signal", to: "PK", envelope: { a: 1 } }, "bad_request"],
    ["an array recipient", { type: "signal", to: ["PK"], envelope: "x" }, "bad_request"],
    ["a recipient that is not a pubkey", { type: "signal", to: "alice", envelope: "x" }, "unknown_recipient"],
    ["an empty envelope", { type: "signal", to: "PK", envelope: "" }, "envelope_too_large"],
  ])("refuses %s", async (_name, frame, code) => {
    const alice = await connect(5);
    const bob = await connect(6);
    const body = JSON.parse(JSON.stringify(frame).replace('"PK"', JSON.stringify(user(6).pk)));
    alice.send({ ...body, ref: "m1" });
    expect(await alice.next()).toMatchObject({ type: "error", ref: "m1", error: { code, retryable: false } });
    expect(await bob.silent()).toBe(true);
    alice.close();
    bob.close();
  });

  it("refuses non-JSON and binary frames without relaying", async () => {
    const alice = await connect(5);
    const bob = await connect(6);
    alice.send("this is not json");
    expect(await alice.next()).toMatchObject({ type: "error", error: { code: "bad_request" } });
    alice.ws.send(new Uint8Array([1, 2, 3]));
    expect(await alice.next()).toMatchObject({ type: "error", error: { code: "bad_request" } });
    expect(await bob.silent()).toBe(true);
    alice.close();
    bob.close();
  });

  it("rate-limits a socket at SIGNALS_PER_MINUTE and keeps relaying to the recipient only up to the limit", async () => {
    const alice = await connect(5);
    const bob = await connect(6);
    for (let i = 0; i < 7; i++) alice.send({ type: "signal", to: user(6).pk, envelope: `s${i}`, ref: `r${i}` });
    for (let i = 0; i < 5; i++) expect((await bob.next()).envelope).toBe(`s${i}`);
    expect(await alice.next()).toMatchObject({ ref: "r5", error: { code: "rate_limited" } });
    expect(await alice.next()).toMatchObject({ ref: "r6", error: { code: "rate_limited" } });
    expect(await bob.silent()).toBe(true);
    alice.close();
    bob.close();
  });
});

describe("signal authentication", () => {
  it("refuses a websocket upgrade without a valid NIP-98 header", async () => {
    const none = await worker(new Request(`${BASE}/v1/ws`, { headers: { Upgrade: "websocket" } }));
    expect(none.status).toBe(401);
    const forged = await upgrade(5, { Authorization: "Nostr bm9wZQ==" });
    expect(forged.status).toBe(401);
  });

  it("closes a revoked device's socket, and a signal to the identity no longer reaches it", async () => {
    const admin = newIdentity();
    const desktop = newIdentity();
    expect((await as(user(6).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk })).status).toBe(200);
    expect((await as(admin.sk, "POST", "/v1/devices?device=primary", { v: 1, device: dev(1), label: "d", auth_pubkey: desktop.pk })).status).toBe(200);
    const ws = await deviceSocket(desktop.sk, dev(1));
    expect((await as(admin.sk, "DELETE", `/v1/devices/${dev(1)}?device=primary`)).status).toBe(200);
    expect(await ws.closed).toMatchObject({ code: 4009, reason: "unlinked" });
    const alice = await connect(5);
    alice.send({ type: "signal", to: user(6).pk, envelope: "ring" });
    expect(await alice.silent()).toBe(true);
    // The revoked device cannot send either: the socket is gone and an upgrade is refused.
    const again = await worker(new Request(`${BASE}/v1/ws?device=${dev(1)}`, {
      headers: { Upgrade: "websocket", Authorization: await authHeader(desktop.sk, "GET", `${BASE.replace(/^http/, "ws")}/v1/ws?device=${dev(1)}`) },
    }));
    expect(again.status).toBe(401);
    alice.close();
  });

  it("a socket opened before its identity migrated cannot signal afterwards", async () => {
    const sender = await connect(5);
    const bob = await connect(6);
    const admin: Identity = newIdentity();
    expect((await as(user(5).sk, "POST", "/v1/devices/admin", { v: 1, admin_pubkey: admin.pk })).status).toBe(200);
    // The migration closes the identity's shared-key sockets; the missed-sweep case is in device-auth-hardening.
    expect(await sender.closed).toMatchObject({ code: 4001 });
    expect(await bob.silent()).toBe(true);
    bob.close();
  });
});
