import { evictDurableObject, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { openDm } from "../client/mailbox-client";
import type { Mailbox } from "../src/mailbox";
import { client, connect, mailboxStub, sendDm, user } from "./helpers";

/** Makes the live mailbox instance throw on SQL matching [pattern] until the returned function is called. */
async function failQueries(pattern: RegExp): Promise<() => Promise<void>> {
  await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
    const target = instance as any;
    const real = target.q;
    target.q = function (query: string, ...params: unknown[]) {
      if (pattern.test(query)) throw new Error("injected SQL failure");
      return real.call(this, query, ...params);
    };
    target.__restore = () => (target.q = real);
  });
  return () => runInDurableObject(mailboxStub(), async (instance: Mailbox) => (instance as any).__restore());
}

async function pendingBytes(recipient?: string): Promise<number> {
  return runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
    const rows = recipient === undefined
      ? state.storage.sql.exec("SELECT COALESCE(SUM(bytes), 0) AS bytes FROM pending_bytes").toArray()
      : state.storage.sql.exec("SELECT bytes FROM pending_bytes WHERE scope = ?", recipient).toArray();
    return (rows[0]?.bytes as number) ?? 0;
  });
}

describe("storage failures", () => {
  it("rolls back a half-done store and reports it as retryable", async () => {
    await sendDm(0, 1, "warm-up"); // make sure the object and its counters exist
    const before = { recipient: await pendingBytes(user(1).pk), all: await pendingBytes() };
    const restore = await failQueries(/INSERT INTO pending_bytes/);
    // The delivery row is inserted, then the counter update throws inside the same transaction.
    const failed = await sendDm(0, 1, "will fail");
    await restore();
    expect(failed.result).toMatchObject({ status: "rejected", error: { code: "storage_error", retryable: true } });
    expect(await pendingBytes(user(1).pk)).toBe(before.recipient);
    expect(await pendingBytes()).toBe(before.all);
    expect((await client(1).inbox()).body.messages).toHaveLength(1);

    // Nothing of the failed attempt survived, so the retry is a fresh store, not a "duplicate".
    const retry = await client(0).send([{ to: user(1).pk, id: failed.id, envelope: failed.envelope }]);
    expect(retry.body.results[0].status).toBe("stored");
    expect((await client(1).inbox()).body.messages).toHaveLength(2);
  });

  it("answers a failed catch-up read with a retryable error, never an empty inbox", async () => {
    await sendDm(0, 1, "queued");
    const socket = await connect(1);
    const restore = await failQueries(/FROM deliveries WHERE recipient/);
    const http = await client(1).inbox();
    socket.send({ type: "sync", after: 0, ref: "r" });
    const frame = await socket.next();
    await restore();
    expect(http.status).toBe(503);
    expect(http.body).toEqual({ v: 1, error: { code: "storage_error", retryable: true } });
    expect(frame).toEqual({ type: "error", ref: "r", error: { code: "storage_error", retryable: true } });
    expect((await client(1).inbox()).body.messages).toHaveLength(1);
    socket.close();
  });

  it("fails an acknowledgement atomically", async () => {
    const { result } = await sendDm(0, 1, "keep me");
    // The delivery rows are updated first, then the per-recipient counter update throws.
    const restore = await failQueries(/UPDATE pending_bytes/);
    const ack = await client(1).ack(result.seq);
    await restore();
    expect(ack.status).toBe(503);
    expect((await client(1).inbox()).body.messages.map((m: any) => m.seq)).toEqual([result.seq]);
  });
});

describe("eviction and hibernation (local simulation, not a live Cloudflare eviction)", () => {
  it("keeps accepted messages across an eviction", async () => {
    const { result } = await sendDm(0, 1, "durable");
    await evictDurableObject(mailboxStub());
    const { messages } = (await client(1).inbox()).body;
    expect(messages.map((m: any) => m.seq)).toEqual([result.seq]);
    expect(openDm(user(1), messages[0].envelope).content).toBe("durable");
  });

  it("serves a hibernated socket from its tag and attachment alone", async () => {
    const socket = await connect(1);
    const firstInstance = (socket as any).instance;
    await evictDurableObject(mailboxStub()); // sockets hibernate; all in-memory state is gone

    const { reply, result } = await sendDm(0, 1, "wake up");
    expect(reply.headers.get("x-relay-instance")).not.toBe(firstInstance);
    const frame = await socket.next();
    expect(frame).toMatchObject({ type: "message", seq: result.seq, from: user(0).pk });

    socket.send({ type: "ack", through: result.seq, ref: "h" });
    expect(await socket.next()).toMatchObject({ type: "acked", ref: "h", acked: 1 });
    socket.close();
  });

  it("lets a client whose socket was dropped catch up after reconnecting", async () => {
    const socket = await connect(1);
    await evictDurableObject(mailboxStub(), { webSockets: "close" });
    await socket.closed;
    const { result } = await sendDm(0, 1, "while away");
    const again = await connect(1);
    again.send({ type: "sync", after: 0 });
    const batch = await again.next();
    expect(batch.messages.map((m: any) => m.seq)).toEqual([result.seq]);
    again.close();
  });
});
