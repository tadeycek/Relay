import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { openDm, wrapDm } from "../client/mailbox-client";
import type { Mailbox } from "../src/mailbox";
import { client, connect, mailboxStub, newId, sendDm, signedRequest, user } from "./helpers";

describe("end-to-end encrypted delivery", () => {
  it("delivers a real NIP-17 wrap that only the recipient can open", async () => {
    const { result, id } = await sendDm(0, 1, "first message");
    expect(result).toMatchObject({ status: "stored", to: user(1).pk, id });

    const inbox = await client(1).inbox();
    expect(inbox.status).toBe(200);
    expect(inbox.body.messages).toHaveLength(1);
    const [message] = inbox.body.messages;
    expect(message).toMatchObject({ seq: result.seq, from: user(0).pk, id });
    expect(openDm(user(1), message.envelope)).toEqual({ sender: user(0).pk, content: "first message" });

    // Anyone else holding the bytes learns nothing and can't authenticate them.
    expect(() => openDm(user(2), message.envelope)).toThrow();
    expect((await client(0).inbox()).body.messages).toHaveLength(0);
    expect((await client(2).inbox()).body.messages).toHaveLength(0);
  });

  it("fans one send out to nine recipients, each seeing only its own copy", async () => {
    const recipients = [1, 2, 3, 4, 5, 6, 7, 8, 9];
    const deliveries = recipients.map((r) => ({ to: user(r).pk, id: newId(), envelope: wrapDm(user(0), user(r).pk, "group hello") }));
    const reply = await client(0).send(deliveries);
    expect(reply.status).toBe(200);
    expect(reply.body.results.map((r: any) => r.status)).toEqual(recipients.map(() => "stored"));

    for (const r of recipients) {
      const { messages } = (await client(r).inbox()).body;
      expect(messages).toHaveLength(1);
      expect(messages[0].id).toBe(deliveries[r - 1].id);
      expect(openDm(user(r), messages[0].envelope)).toEqual({ sender: user(0).pk, content: "group hello" });
    }
    expect((await client(10).inbox()).body.messages).toHaveLength(0);
  });
});

describe("idempotent sends", () => {
  it("treats a retry with the same id and bytes as the same delivery", async () => {
    const first = await sendDm(0, 1, "once");
    // The sender never saw the first response (lost connection) and retries the exact same packet.
    const retry = await client(0).send([{ to: user(1).pk, id: first.id, envelope: first.envelope }]);
    expect(retry.body.results[0]).toEqual({ to: user(1).pk, id: first.id, status: "duplicate", seq: first.result.seq });
    expect((await client(1).inbox()).body.messages).toHaveLength(1);
  });

  it("refuses new bytes under an id that was already used", async () => {
    const first = await sendDm(0, 1, "original");
    const conflict = await client(0).send([{ to: user(1).pk, id: first.id, envelope: wrapDm(user(0), user(1).pk, "changed") }]);
    expect(conflict.body.results[0]).toMatchObject({ status: "rejected", error: { code: "id_conflict", retryable: false } });
    const { messages } = (await client(1).inbox()).body;
    expect(messages).toHaveLength(1);
    expect(openDm(user(1), messages[0].envelope).content).toBe("original");
  });

  it("scopes ids to sender and recipient", async () => {
    const id = newId();
    expect((await sendDm(0, 1, "a", id)).result.status).toBe("stored");
    expect((await sendDm(0, 2, "b", id)).result.status).toBe("stored");
    expect((await sendDm(3, 1, "c", id)).result.status).toBe("stored");
    expect((await client(1).inbox()).body.messages).toHaveLength(2);
  });

  it("stores a retry that arrives after the receipt as new, since nothing about the original is kept", async () => {
    const first = await sendDm(0, 1, "late retry");
    await client(1).ack(first.result.seq);
    const retry = await client(0).send([{ to: user(1).pk, id: first.id, envelope: first.envelope }]);
    expect(retry.body.results[0].status).toBe("stored");
    expect(retry.body.results[0].seq).toBeGreaterThan(first.result.seq);
    // Same delivery id and bytes: the app recognises the payload id and doesn't show it twice.
    const [again] = (await client(1).inbox()).body.messages;
    expect(again).toMatchObject({ id: first.id, envelope: first.envelope });
  });
});

describe("catch-up and receipts", () => {
  async function fill(to: number, count: number) {
    const seqs: number[] = [];
    for (let i = 0; i < count; i++) seqs.push((await sendDm(0, to, `m${i}`)).result.seq);
    return seqs;
  }

  it("pages in sequence order with a stable cursor", async () => {
    const seqs = await fill(1, 12);
    const seen: number[] = [];
    let after = 0;
    let pages = 0;
    for (;;) {
      const page = (await client(1).inbox(after, 5)).body;
      pages++;
      seen.push(...page.messages.map((m: any) => m.seq));
      after = page.cursor;
      if (!page.more) break;
    }
    expect(seen).toEqual(seqs);
    expect(pages).toBe(3);
  });

  it("bounds a page by bytes as well as by count", async () => {
    await fill(1, 20);
    const page = (await client(1).inbox(0, 20)).body;
    const bytes = page.messages.reduce((sum: number, m: any) => sum + new TextEncoder().encode(m.envelope).byteLength, 0);
    expect(page.messages.length).toBeLessThan(20);
    expect(bytes).toBeLessThanOrEqual(12288);
    expect(page.more).toBe(true);
  });

  it("drops payloads only after a cumulative receipt", async () => {
    const seqs = await fill(1, 6);
    const ack = await client(1).ack(seqs[2]);
    expect(ack.body).toEqual({ v: 1, acked: 3, through: seqs[2] });
    const rest = (await client(1).inbox()).body.messages.map((m: any) => m.seq);
    expect(rest).toEqual(seqs.slice(3));
  });

  it("replays everything after the last receipt when an ACK was lost", async () => {
    const seqs = await fill(1, 4);
    const before = (await client(1).inbox()).body.messages;
    // The phone stored the messages but crashed before acknowledging: it reconnects from its old cursor.
    const again = (await client(1).inbox()).body.messages;
    expect(again).toEqual(before);
    await client(1).ack(seqs.at(-1)!);
    expect((await client(1).inbox()).body.messages).toHaveLength(0);
  });

  it("lets a recipient acknowledge only its own queue", async () => {
    const forOne = await sendDm(0, 1, "for one");
    await sendDm(0, 2, "for two");
    // An absurd cursor from user 2 must not touch user 1's mailbox.
    const ack = await client(2).ack(Number.MAX_SAFE_INTEGER);
    expect(ack.body.acked).toBe(1);
    const { messages } = (await client(1).inbox()).body;
    expect(messages.map((m: any) => m.seq)).toEqual([forOne.result.seq]);
  });

  it("offers no way to read someone else's mailbox", async () => {
    await sendDm(0, 1, "private");
    const res = await signedRequest(2, "GET", `/v1/inbox?after=0&recipient=${user(1).pk}`);
    expect(res.status).toBe(400);
    expect((await client(2).inbox()).body.messages).toHaveLength(0);
  });

  it("reports SQL row usage per request", async () => {
    const { reply } = await sendDm(0, 1, "metered");
    expect(reply.headers.get("x-relay-sql-rows")).toMatch(/^read=\d+;written=\d+$/);
  });
});

describe("live sockets", () => {
  it("pushes a stored message to the recipient's socket, and only theirs", async () => {
    const one = await connect(1);
    const two = await connect(2);
    const { result, id } = await sendDm(0, 1, "live");
    const frame = await one.next();
    expect(frame).toMatchObject({ type: "message", seq: result.seq, from: user(0).pk, id });
    expect(openDm(user(1), frame.envelope).content).toBe("live");
    expect(await two.silent()).toBe(true);
    one.close();
    two.close();
  });

  it("catches up and acknowledges over the socket", async () => {
    const first = await sendDm(0, 1, "a");
    const second = await sendDm(0, 1, "b");
    const socket = await connect(1);
    socket.send({ type: "sync", after: 0, ref: "s1" });
    const batch = await socket.next();
    expect(batch).toMatchObject({ type: "batch", ref: "s1", more: false, cursor: second.result.seq });
    expect(batch.messages.map((m: any) => m.seq)).toEqual([first.result.seq, second.result.seq]);
    socket.send({ type: "ack", through: batch.cursor, ref: "a1" });
    expect(await socket.next()).toEqual({ type: "acked", ref: "a1", acked: 2, through: second.result.seq });
    expect((await client(1).inbox()).body.messages).toHaveLength(0);
    socket.close();
  });

  it("answers keepalive pings without involving the mailbox", async () => {
    const socket = await connect(1);
    socket.send("ping");
    expect(await socket.next()).toBe("pong");
    socket.close();
  });

  it("rejects malformed frames", async () => {
    const socket = await connect(1);
    socket.send("{not json");
    expect(await socket.next()).toMatchObject({ type: "error", error: { code: "bad_request" } });
    socket.send({ type: "ack", through: -1 });
    expect(await socket.next()).toMatchObject({ type: "error", error: { code: "bad_request" } });
    socket.close();
  });

  it("closes an expired session instead of pushing to it, keeping the message queued", async () => {
    const socket = await connect(1);
    await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
      for (const ws of state.getWebSockets(user(1).pk)) {
        ws.serializeAttachment({ ...ws.deserializeAttachment(), expiresAt: Date.now() - 1 });
      }
    });
    const { result } = await sendDm(0, 1, "after expiry");
    expect(await socket.closed).toEqual({ code: 4001, reason: "auth_expired" });
    expect((await client(1).inbox()).body.messages.map((m: any) => m.seq)).toEqual([result.seq]);
  });

  it("keeps at most four sockets per identity, closing the oldest", async () => {
    const sockets = [];
    for (let i = 0; i < 5; i++) sockets.push(await connect(1));
    expect(await sockets[0].closed).toEqual({ code: 4008, reason: "replaced" });
    for (const s of sockets.slice(1)) s.close();
  });
});
