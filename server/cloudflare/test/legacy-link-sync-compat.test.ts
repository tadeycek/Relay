import { describe, expect, it } from "vitest";
import { runInDurableObject } from "cloudflare:test";
import { authHeader } from "../client/mailbox-client";
import { BASE, TestSocket, client, mailboxStub, newId, sendDm, user, worker } from "./helpers";
import type { Mailbox } from "../src/mailbox";

/**
 * LP-D14 / WP05 compatibility: a client that never links a device (no `device` parameter, no link, no
 * sync) must get byte-identical replies on send, inbox, ack, the socket and media, and must not gain
 * rows in any of the WP05 tables. The link and sync endpoints exist for clients that opt in.
 */

const tableCount = (table: string) =>
  runInDurableObject(mailboxStub(), async (_: Mailbox, state) => state.storage.sql.exec(`SELECT COUNT(*) AS n FROM ${table}`).one().n as number);

describe("legacy clients are untouched by the link slot and the sync channel", () => {
  it("returns the documented reply bodies for send, inbox and ack", async () => {
    const id = newId();
    const sent = await sendDm(0, 2, "legacy hello", id);
    expect(sent.reply.status).toBe(200);
    // Exactly the pre-WP05 fields, in the documented order, with no gap/oldest additions.
    expect(JSON.stringify(sent.reply.body)).toBe(JSON.stringify({
      v: 1,
      results: [{ to: user(2).pk, id, status: "stored", seq: sent.result.seq }],
    }));

    const inbox = await client(2).inbox(0);
    // storedAt is a clock value; everything else is compared byte for byte, in insertion order.
    const storedAt = inbox.body.messages[0].storedAt;
    expect(typeof storedAt).toBe("number");
    expect(JSON.stringify({ ...inbox.body, messages: inbox.body.messages.map((m: any) => ({ ...m, storedAt: 0 })) })).toBe(JSON.stringify({
      v: 1,
      messages: [{ seq: sent.result.seq, from: user(0).pk, id, envelope: sent.envelope, storedAt: 0 }],
      more: false,
      cursor: sent.result.seq,
    }));
    expect(Object.keys(inbox.body)).toEqual(["v", "messages", "more", "cursor"]);

    const ack = await client(2).ack(sent.result.seq);
    expect(JSON.stringify(ack.body)).toBe(JSON.stringify({ v: 1, acked: 1, through: sent.result.seq }));
  });

  it("keeps the socket ready frame and media replies unchanged", async () => {
    const reply = await worker(new Request(`${BASE}/v1/ws`, {
      headers: { Upgrade: "websocket", Authorization: await authHeader(user(2).sk, "GET", `${BASE.replace(/^http/, "ws")}/v1/ws`) },
    }));
    expect(reply.status).toBe(101);
    const socket = new TestSocket(reply.webSocket!);
    const ready = await socket.next();
    expect(Object.keys(ready)).toEqual(["type", "v", "instance", "expiresAt"]);
    expect(ready.type).toBe("ready");
    expect(ready.gap).toBeUndefined();
    socket.close();

    const put = await client(0).putMedia(new Uint8Array(3000).fill(9), [user(2).pk]);
    expect(JSON.stringify(put.body)).toBe(JSON.stringify({ v: 1, stored: true, duplicate: false }));
    const got = await client(2).getMedia(put.hash);
    expect(got.status).toBe(200);
    expect(got.bytes).toEqual(new Uint8Array(3000).fill(9));
    const confirm = await client(2).confirmMedia(put.hash);
    expect(Object.keys(confirm.body).sort()).toEqual(["hash", "remaining", "v"]);
  });

  it("writes no link, sync or device rows for identities that never opt in", async () => {
    await sendDm(0, 2, "a");
    await sendDm(0, 2, "b");
    await client(2).inbox(0);
    await client(2).ack(1);
    for (const table of ["link_slots", "sync_parts", "sync_events", "sync_cursors", "devices"]) {
      expect(await tableCount(table)).toBe(0);
    }
  });
});
