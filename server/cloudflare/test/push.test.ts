import { env, evictDurableObject, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { wrapDm } from "../client/mailbox-client";
import type { Mailbox } from "../src/mailbox";
import { client, connect, mailboxStub, newId, sendDm, user } from "./helpers";

interface Captured { url: string; body: string; auth: string | null }

/** Replaces the mailbox's push fetch with a recorder that answers like Google would. */
async function fakeGoogle(fcmStatus = 200, fcmBody = "{}"): Promise<Captured[]> {
  const captured: Captured[] = [];
  await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
    (instance as any).pushFetch = async (request: Request) => {
      captured.push({ url: request.url, body: new TextDecoder().decode(await request.arrayBuffer()), auth: request.headers.get("Authorization") });
      if (request.url.startsWith("https://oauth2.googleapis.com/")) {
        return new Response(JSON.stringify({ access_token: "test-access-token", expires_in: 3600 }), { headers: { "content-type": "application/json" } });
      }
      return new Response(fcmBody, { status: fcmStatus });
    };
  });
  return captured;
}

const sends = (captured: Captured[]) => captured.filter((c) => c.url.includes("fcm.googleapis.com"));

async function settle(predicate: () => boolean, ms = 2000) {
  const deadline = Date.now() + ms;
  while (!predicate() && Date.now() < deadline) await new Promise((r) => setTimeout(r, 20));
}

async function registerToken(i: number, token: string) {
  return client(i).request("POST", "/v1/push-token", { v: 1, token });
}

describe("wake-up pushes", () => {
  it("wakes a recipient with no open connection, without content or sender", async () => {
    const captured = await fakeGoogle();
    expect((await registerToken(1, "device-token-1")).body).toEqual({ v: 1, push: true });
    await sendDm(0, 1, "secret text");
    await settle(() => sends(captured).length > 0);

    const [send] = sends(captured);
    expect(send.url).toBe("https://fcm.googleapis.com/v1/projects/relay-test/messages:send");
    expect(send.auth).toBe("Bearer test-access-token");
    const message = JSON.parse(send.body).message;
    expect(message).toEqual({ token: "device-token-1", data: { type: "wake" }, android: { priority: "high", ttl: "86400s", collapse_key: "relay-wake" } });
    expect(send.body).not.toContain("secret");
    expect(send.body).not.toContain(user(0).pk);
  });

  it("signs the Google token request with the service account's key", async () => {
    const captured = await fakeGoogle();
    await registerToken(1, "device-token-1");
    await sendDm(0, 1, "hello");
    await settle(() => captured.length > 0);
    const assertion = new URLSearchParams(captured[0].body).get("assertion")!;
    const [header, claims, signature] = assertion.split(".");
    const decode = (s: string) => Uint8Array.from(atob(s.replace(/-/g, "+").replace(/_/g, "/")), (c) => c.charCodeAt(0));
    const pem = (env as any).TEST_FCM_PUBLIC_KEY as string;
    const der = decode(pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "").replace(/\+/g, "-").replace(/\//g, "_"));
    const key = await crypto.subtle.importKey("spki", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    expect(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, decode(signature), new TextEncoder().encode(`${header}.${claims}`))).toBe(true);
    expect(JSON.parse(new TextDecoder().decode(decode(claims)))).toMatchObject({
      iss: "push@relay-test.iam.gserviceaccount.com",
      scope: "https://www.googleapis.com/auth/firebase.messaging",
      aud: "https://oauth2.googleapis.com/token",
    });
  });

  it("doesn't push to someone whose app is connected", async () => {
    const captured = await fakeGoogle();
    await registerToken(1, "device-token-1");
    const socket = await connect(1);
    await sendDm(0, 1, "live");
    expect((await socket.next()).type).toBe("message");
    await new Promise((r) => setTimeout(r, 200));
    expect(sends(captured)).toHaveLength(0);
    socket.close();
  });

  it("sends one wake-up for several messages in a row", async () => {
    const captured = await fakeGoogle();
    await registerToken(1, "device-token-1");
    for (let i = 0; i < 3; i++) await sendDm(0, 1, `m${i}`);
    await client(0).send([{ to: user(1).pk, id: newId(), envelope: wrapDm(user(0), user(1).pk, "x") }]);
    await settle(() => sends(captured).length > 0);
    await new Promise((r) => setTimeout(r, 200));
    expect(sends(captured)).toHaveLength(1);
  });

  it("keeps the wake-up throttle across eviction and token refresh", async () => {
    const first = await fakeGoogle();
    await registerToken(1, "device-token-1");
    await sendDm(0, 1, "before eviction");
    await settle(() => sends(first).length === 1);
    await evictDurableObject(mailboxStub());
    const second = await fakeGoogle();
    await registerToken(1, "device-token-1");
    await sendDm(0, 1, "after eviction");
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect(second).toHaveLength(0);
    expect((await client(1).inbox()).body.messages).toHaveLength(2);
  });

  it("upgrades the original v6 token layout without losing tokens or pending messages", async () => {
    await sendDm(0, 1, "before migration");
    await registerToken(1, "existing-device-token");
    await runInDurableObject(mailboxStub(), async (_instance: Mailbox, state) => {
      state.storage.sql.exec("ALTER TABLE push_tokens DROP COLUMN last_wake_at");
      state.storage.sql.exec("UPDATE meta SET value = '6' WHERE key = 'schema'");
    });
    await evictDurableObject(mailboxStub());
    const captured = await fakeGoogle();
    expect((await registerToken(1, "existing-device-token")).status).toBe(200);
    await sendDm(0, 1, "after migration");
    await settle(() => sends(captured).length === 1);
    expect(JSON.parse(sends(captured)[0].body).message.token).toBe("existing-device-token");
    expect((await client(1).inbox()).body.messages).toHaveLength(2);
  });

  it("also upgrades a v6 database that already has the throttle column", async () => {
    await registerToken(1, "existing-device-token");
    await runInDurableObject(mailboxStub(), async (_instance: Mailbox, state) => {
      state.storage.sql.exec("UPDATE meta SET value = '6' WHERE key = 'schema'");
    });
    await evictDurableObject(mailboxStub());
    const captured = await fakeGoogle();
    await sendDm(0, 1, "already migrated column");
    await settle(() => sends(captured).length === 1);
    expect(sends(captured)).toHaveLength(1);
  });

  it("wakes again after the throttle expires", async () => {
    const captured = await fakeGoogle();
    await registerToken(1, "device-token-1");
    await sendDm(0, 1, "first");
    await settle(() => sends(captured).length === 1);
    await runInDurableObject(mailboxStub(), async (_instance: Mailbox, state) => {
      state.storage.sql.exec("UPDATE push_tokens SET last_wake_at = ?", Date.now() - 20_001);
    });
    await sendDm(0, 1, "later");
    await settle(() => sends(captured).length === 2);
    expect(sends(captured)).toHaveLength(2);
    expect(captured.filter((request) => request.url.includes("oauth2.googleapis.com"))).toHaveLength(1);
  });

  it("falls back to push when a socket send fails", async () => {
    const captured = await fakeGoogle();
    await registerToken(1, "device-token-1");
    const socket = await connect(1);
    await runInDurableObject(mailboxStub(), async (_instance: Mailbox, state) => {
      state.getWebSockets(user(1).pk)[0].send = () => { throw new Error("socket closing"); };
    });
    await sendDm(0, 1, "while closing");
    await settle(() => sends(captured).length > 0);
    expect(sends(captured)).toHaveLength(1);
    socket.close();
  });

  it("forgets a token Google says is no longer registered", async () => {
    const captured = await fakeGoogle(404, JSON.stringify({ error: { status: "NOT_FOUND", details: [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode: "UNREGISTERED" }] } }));
    await registerToken(1, "dead-token");
    await sendDm(0, 1, "hello");
    await settle(() => sends(captured).length > 0);
    await new Promise((r) => setTimeout(r, 100));
    const left = await runInDurableObject(mailboxStub(), async (_: Mailbox, state) =>
      state.storage.sql.exec("SELECT COUNT(*) AS n FROM push_tokens").one().n);
    expect(left).toBe(0);
  });

  it("stops pushing when the phone clears its token", async () => {
    const captured = await fakeGoogle();
    await registerToken(1, "device-token-1");
    expect((await registerToken(1, "")).body).toEqual({ v: 1, push: false });
    await sendDm(0, 1, "hello");
    await new Promise((r) => setTimeout(r, 200));
    expect(captured).toHaveLength(0);
  });

  it("keeps a valid token when the Firebase project returns a generic 404", async () => {
    const captured = await fakeGoogle(404, JSON.stringify({ error: { status: "NOT_FOUND" } }));
    await registerToken(1, "valid-token");
    await sendDm(0, 1, "hello");
    await settle(() => sends(captured).length > 0);
    await new Promise((resolve) => setTimeout(resolve, 100));
    const tokens = await runInDurableObject(mailboxStub(), async (_instance: Mailbox, state) =>
      state.storage.sql.exec("SELECT token FROM push_tokens").toArray());
    expect(tokens).toEqual([{ token: "valid-token" }]);
    expect((await client(1).inbox()).body.messages).toHaveLength(1);
  });

  it("still stores messages when Firebase credentials are absent", async () => {
    const captured = await fakeGoogle();
    await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
      (instance as any).push = null;
    });
    expect((await registerToken(1, "device-token-1")).body).toEqual({ v: 1, push: false });
    expect((await sendDm(0, 1, "without push")).result.status).toBe("stored");
    expect((await client(1).inbox()).body.messages).toHaveLength(1);
    expect(captured).toHaveLength(0);
  });

  it("only takes tokens from members, and refuses malformed ones", async () => {
    const { MailboxClient, newIdentity } = await import("../client/mailbox-client");
    const { BASE, worker } = await import("./helpers");
    const stranger = new MailboxClient(BASE, newIdentity(), worker);
    expect((await stranger.request("POST", "/v1/push-token", { v: 1, token: "t" })).status).toBe(403);
    expect((await registerToken(1, "has space")).status).toBe(400);
    expect((await client(1).request("POST", "/v1/push-token", { v: 2, token: "t" })).status).toBe(400);
  });
});
