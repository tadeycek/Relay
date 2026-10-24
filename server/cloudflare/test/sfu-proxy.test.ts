import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CallRoom } from "../src/callroom";
import { BASE, user, worker } from "./helpers";
import { OUTSIDER } from "./identities";
import {
  SFU_APP_ID, SFU_APP_SECRET, createRoomAs, fakeSfu, inCallRoom, joinSecret, joinSocket, reqAs, roomId,
} from "./callroom-helpers";

/**
 * WP15 — the SFU proxy on the Worker. Every upstream request goes to a fake fetch; nothing here reaches
 * Cloudflare. The credentials are test constants, never real keys.
 */

let ROOM = roomId(1);
let nextRoom = 500;
const SECRET = joinSecret(7);

const ok = (body: unknown, status = 201) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const sfu = (room: string, op: string) => `/v1/call/rooms/${room}/sfu/${op}`;

beforeEach(() => {
  ROOM = roomId(nextRoom++);
});

afterEach(() => vi.restoreAllMocks());

async function roomWith(...ids: number[]): Promise<Array<{ socket: any; welcome: any }>> {
  expect((await createRoomAs(user(0).sk, ROOM, SECRET)).status).toBe(200);
  const out = [];
  for (const i of ids) out.push(await joinSocket(user(i).sk, ROOM, SECRET));
  return out;
}

const setLimit = (room: string, name: string, value: number): Promise<void> =>
  inCallRoom(room, (instance: CallRoom) => {
    (instance as any).limits[name] = value;
  });

describe("WP15 SFU proxy configuration", () => {
  it("answers 503 not_configured when SFU_APP_ID or SFU_APP_SECRET is absent", async () => {
    const sockets = await roomWith(0);
    const reply = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "v=0\r\n" });
    expect(reply.status).toBe(503);
    expect(reply.body.error.code).toBe("not_configured");
    sockets[0].socket.close();
  });

  it("refuses an unauthenticated request before touching the provider", async () => {
    expect((await createRoomAs(user(0).sk, ROOM, SECRET)).status).toBe(200);
    const calls = await fakeSfu(ROOM);
    const missing = await worker(new Request(BASE + sfu(ROOM, "sessions-new"), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ v: 1, offer: "v=0\r\n" }),
    }));
    expect(missing.status).toBe(401);
    expect(((await missing.json()) as any).error.code).toBe("auth_missing");
    expect(calls).toHaveLength(0);
  });

  it("refuses a member with no open room socket", async () => {
    expect((await createRoomAs(user(0).sk, ROOM, SECRET)).status).toBe(200);
    const calls = await fakeSfu(ROOM);
    const reply = await reqAs(user(1).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "v=0\r\n" });
    expect(reply.status).toBe(403);
    expect(reply.body.error.code).toBe("forbidden");
    expect(calls).toHaveLength(0);
  });
});

describe("WP15 SFU proxy provider surface", () => {
  it("creates a session with an honest User-Agent, bearer secret, no redirects and a narrow body", async () => {
    const sockets = await roomWith(0);
    const calls = await fakeSfu(ROOM, () => ok({ sessionId: "sess-a", sessionDescription: { type: "answer", sdp: "answer" } }));
    const reply = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "v=0\r\noffer" });
    expect(reply.status).toBe(200);
    expect(reply.body).toMatchObject({ v: 1, sessionId: "sess-a", sessionDescription: { type: "answer", sdp: "answer" } });
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(`https://rtc.live.cloudflare.com/v1/apps/${SFU_APP_ID}/sessions/new`);
    expect(calls[0].method).toBe("POST");
    expect(calls[0].headers.authorization).toBe(`Bearer ${SFU_APP_SECRET}`);
    expect(calls[0].headers["user-agent"]).toMatch(/^RelayMailbox\/\d/);
    expect(calls[0].headers["user-agent"]).not.toMatch(/urllib|python|curl/i);
    expect(calls[0].redirect).toBe("manual");
    expect(JSON.parse(calls[0].body)).toEqual({ sessionDescription: { type: "offer", sdp: "v=0\r\noffer" } });
    sockets[0].socket.close();
  });

  it("publishes, subscribes, renegotiates and closes tracks through the caller's own session", async () => {
    const sockets = await roomWith(0, 1);
    let creates = 0;
    const calls = await fakeSfu(ROOM, (call) => {
      if (call.url.endsWith("/sessions/new")) {
        creates++;
        return ok({ sessionId: creates === 1 ? "sess-a" : "sess-b", sessionDescription: { type: "answer", sdp: "a" } });
      }
      if (call.url.endsWith("/tracks/new")) {
        const local = call.body.includes('"local"');
        return ok({ tracks: [{ mid: local ? "0" : "1", trackName: "mic" }], sessionDescription: { type: "answer", sdp: "a2" } });
      }
      if (call.url.endsWith("/renegotiate")) return ok({ sessionDescription: { type: "answer", sdp: "a3" } });
      if (call.url.endsWith("/tracks/close")) return ok({ tracks: [{ mid: "0" }] });
      return new Response("unexpected", { status: 404 });
    });

    expect((await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "v=0\r\na" })).status).toBe(200);
    expect((await reqAs(user(1).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "v=0\r\nb" })).status).toBe(200);

    const local = await reqAs(user(0).sk, "POST", sfu(ROOM, "tracks-new"), {
      v: 1,
      sessionId: "sess-a",
      sessionDescription: { type: "offer", sdp: "offer-a" },
      tracks: [{ location: "local", mid: "0", trackName: "mic" }],
    });
    expect(local.status).toBe(200);
    expect(local.body.tracks).toEqual([{ mid: "0", trackName: "mic" }]);
    expect(calls.at(-1)!.url).toBe(`https://rtc.live.cloudflare.com/v1/apps/${SFU_APP_ID}/sessions/sess-a/tracks/new`);
    expect(JSON.parse(calls.at(-1)!.body)).toEqual({
      tracks: [{ location: "local", mid: "0", trackName: "mic" }],
      sessionDescription: { type: "offer", sdp: "offer-a" },
    });

    const remote = await reqAs(user(0).sk, "POST", sfu(ROOM, "tracks-new"), {
      v: 1,
      sessionId: "sess-a",
      tracks: [{ location: "remote", sessionId: "sess-b", trackName: "mic" }],
    });
    expect(remote.status).toBe(200);
    expect(JSON.parse(calls.at(-1)!.body)).toEqual({ tracks: [{ location: "remote", sessionId: "sess-b", trackName: "mic" }] });

    const renegotiate = await reqAs(user(0).sk, "PUT", sfu(ROOM, "renegotiate"), { v: 1, answer: "answer-sdp" });
    expect(renegotiate.status).toBe(200);
    expect(calls.at(-1)!.method).toBe("PUT");
    expect(calls.at(-1)!.url).toBe(`https://rtc.live.cloudflare.com/v1/apps/${SFU_APP_ID}/sessions/sess-a/renegotiate`);
    expect(JSON.parse(calls.at(-1)!.body)).toEqual({ sessionDescription: { type: "answer", sdp: "answer-sdp" } });

    const close = await reqAs(user(0).sk, "PUT", sfu(ROOM, "tracks-close"), { v: 1, mids: ["0"] });
    expect(close.status).toBe(200);
    expect(calls.at(-1)!.method).toBe("PUT");
    expect(calls.at(-1)!.url).toBe(`https://rtc.live.cloudflare.com/v1/apps/${SFU_APP_ID}/sessions/sess-a/tracks/close`);
    expect(JSON.parse(calls.at(-1)!.body)).toEqual({ tracks: [{ mid: "0" }], force: true });

    sockets.forEach((s) => s.socket.close());
  });

  it("refuses another participant's session id and an unknown source session", async () => {
    const sockets = await roomWith(0, 1);
    let creates = 0;
    await fakeSfu(ROOM, (call) => {
      if (call.url.endsWith("/sessions/new")) {
        creates++;
        return ok({ sessionId: creates === 1 ? "sess-a" : "sess-b", sessionDescription: { type: "answer", sdp: "a" } });
      }
      return ok({ tracks: [{ mid: "1", trackName: "mic" }] });
    });
    expect((await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" })).status).toBe(200);
    expect((await reqAs(user(1).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "b" })).status).toBe(200);

    const foreign = await reqAs(user(0).sk, "POST", sfu(ROOM, "tracks-new"), {
      v: 1,
      sessionId: "sess-b",
      tracks: [{ location: "local", mid: "0", trackName: "mic" }],
    });
    expect(foreign.status).toBe(403);
    expect(foreign.body.error.code).toBe("forbidden");

    const unknown = await reqAs(user(0).sk, "POST", sfu(ROOM, "tracks-new"), {
      v: 1,
      sessionId: "sess-a",
      tracks: [{ location: "remote", sessionId: "sess-unknown", trackName: "mic" }],
    });
    expect(unknown.status).toBe(403);
    sockets.forEach((s) => s.socket.close());
  });
});

describe("WP15 SFU proxy upstream failures and bounds", () => {
  it("maps a provider error, a redirect and an oversized response to 502 without leaking the secret", async () => {
    const sockets = await roomWith(0);
    const logged: string[] = [];
    for (const method of ["error", "warn", "log", "info"] as const) {
      vi.spyOn(console, method).mockImplementation((...args: unknown[]) => void logged.push(JSON.stringify(args)));
    }

    await fakeSfu(ROOM, () => new Response("denied", { status: 500 }));
    const failed = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" });
    expect(failed.status).toBe(502);
    expect(failed.body.error.code).toBe("upstream_failed");

    const redirectCalls = await fakeSfu(ROOM, () => new Response("moved", { status: 302, headers: { location: "https://evil.example" } }));
    const redirected = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" });
    expect(redirected.status).toBe(502);
    expect(redirectCalls[0].redirect).toBe("manual");

    await setLimit(ROOM, "maxSfuResponseBytes", 100);
    await fakeSfu(ROOM, () => ok({ sessionId: "sess-a", padding: "x".repeat(500) }));
    const oversize = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" });
    expect(oversize.status).toBe(502);

    const all = `${JSON.stringify(failed)} ${JSON.stringify(redirected)} ${JSON.stringify(oversize)} ${logged.join("\n")}`;
    expect(all).not.toContain(SFU_APP_SECRET);
    expect(all).not.toContain(SFU_APP_ID);
    sockets[0].socket.close();
  });

  it("gives up on a provider that never answers", async () => {
    const sockets = await roomWith(0);
    await setLimit(ROOM, "sfuTimeoutMs", 25);
    await fakeSfu(ROOM, () => new Promise<Response>(() => {}));
    const started = Date.now();
    const reply = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" });
    expect(reply.status).toBe(502);
    expect(reply.body.error.code).toBe("upstream_failed");
    expect(Date.now() - started).toBeLessThan(2000);
    sockets[0].socket.close();
  });

  it("rejects malformed bodies, unbounded bodies and unknown operations", async () => {
    const sockets = await roomWith(0);
    const calls = await fakeSfu(ROOM);
    for (const bad of [{ v: 1, offer: 123 }, { v: 2 }, {}]) {
      const reply = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), bad);
      expect(reply.status).toBe(400);
      expect(reply.body.error.code).toBe("bad_request");
    }
    // A valid session is needed before tracks-new validates its body.
    expect((await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" })).status).toBe(200);
    const badTracks = await reqAs(user(0).sk, "POST", sfu(ROOM, "tracks-new"), { v: 1, sessionId: "sess-a", tracks: "no" });
    expect(badTracks.status).toBe(400);
    await setLimit(ROOM, "maxSfuRequestBytes", 64);
    const huge = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "x".repeat(200) });
    expect(huge.status).toBe(413);
    const unknown = await reqAs(user(0).sk, "POST", sfu(ROOM, "delete"), { v: 1 });
    expect(unknown.status).toBe(404);
    const arbitrary = await reqAs(user(0).sk, "POST", sfu(ROOM, "https://evil.example/x"), { v: 1 });
    expect(arbitrary.status).toBe(404);
    expect(calls.filter((c) => c.url.includes("/tracks/new"))).toHaveLength(0);
    sockets[0].socket.close();
  });

  it("rate-limits provider operations per identity", async () => {
    const sockets = await roomWith(0);
    let n = 0;
    await fakeSfu(ROOM, () => ok({ sessionId: `sess-${n++}`, sessionDescription: { type: "answer", sdp: "a" } }));
    await setLimit(ROOM, "callSfuPerMinute", 2);
    expect((await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" })).status).toBe(200);
    expect((await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" })).status).toBe(200);
    const limited = await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" });
    expect(limited.status).toBe(429);
    expect(limited.body.error.code).toBe("rate_limited");
    sockets[0].socket.close();
  });

  it("serialises mutations for one session", async () => {
    const sockets = await roomWith(0);
    await fakeSfu(ROOM, () => ok({ sessionId: "sess-a", sessionDescription: { type: "answer", sdp: "a" } }));
    expect((await reqAs(user(0).sk, "POST", sfu(ROOM, "sessions-new"), { v: 1, offer: "a" })).status).toBe(200);

    let active = 0;
    let maxActive = 0;
    await fakeSfu(ROOM, async (call) => {
      if (call.url.endsWith("/sessions/new")) return ok({ sessionId: "sess-a", sessionDescription: { type: "answer", sdp: "a" } });
      active++;
      maxActive = Math.max(maxActive, active);
      await new Promise((resolve) => setTimeout(resolve, 25));
      active--;
      return ok({ tracks: [{ mid: "0", trackName: "mic" }] });
    });
    const body = { v: 1, sessionId: "sess-a", tracks: [{ location: "local", mid: "0", trackName: "mic" }] };
    const [a, b] = await Promise.all([
      reqAs(user(0).sk, "POST", sfu(ROOM, "tracks-new"), body),
      reqAs(user(0).sk, "POST", sfu(ROOM, "tracks-new"), body),
    ]);
    expect(a.status).toBe(200);
    expect(b.status).toBe(409);
    expect(maxActive).toBe(1);
    sockets[0].socket.close();
  });
});
