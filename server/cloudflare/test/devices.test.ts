import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { Mailbox } from "../src/mailbox";
import { client, mailboxStub, sendDm } from "./helpers";
import {
  DORMANT_MS, ackAs, ageDevice, connectAs, cursorState, deviceRow, dev, inboxAs, listDevices, pendingSeqs,
  registerDevice, removeDevice, tableCount, upgradeAs,
} from "./device-helpers";

const seqs = (reply: { body: any }): number[] => reply.body.messages.map((m: any) => m.seq);
const written = (headers: Headers) => Number(/written=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "")?.[1]);

/** User 1 is the recipient everywhere below; user 0 sends. Registers [count] desktops (dev(1)…). */
async function linked(count = 1): Promise<void> {
  for (let n = 1; n <= count; n++) expect((await registerDevice(1, dev(n))).status).toBe(200);
}

describe("WP04-AC1 legacy clients (no `device` parameter) behave exactly as before", () => {
  it("acks delete at once, replies keep their old shape, and no device state is created", async () => {
    const a = await sendDm(0, 1, "one");
    const inbox = await inboxAs(1);
    expect(Object.keys(inbox.body).sort()).toEqual(["cursor", "messages", "more", "v"]);
    const ack = await ackAs(1, undefined, a.result.seq);
    expect(ack.body).toEqual({ v: 1, acked: 1, through: a.result.seq });
    expect(await pendingSeqs(1)).toEqual([]);
    expect(await tableCount("devices")).toBe(0);
    expect(await tableCount("cursor_state")).toBe(0);
  });

  it("treats `device=primary` on an identity without devices like no parameter", async () => {
    const a = await sendDm(0, 1, "one");
    expect(seqs(await inboxAs(1, "primary"))).toEqual([a.result.seq]);
    expect((await ackAs(1, "primary", a.result.seq)).body.acked).toBe(1);
    expect(await tableCount("devices")).toBe(0);
  });

  it("sends the ready frame with exactly its old fields", async () => {
    const { socket, ready } = await connectAs(1);
    expect(Object.keys(ready).sort()).toEqual(["expiresAt", "instance", "type", "v"]);
    socket.close();
    // A linked identity's ready frame is also unchanged unless there is a gap to report.
    await registerDevice(1, dev(1));
    const linkedReady = await connectAs(1, dev(1));
    expect(Object.keys(linkedReady.ready).sort()).toEqual(["expiresAt", "instance", "type", "v"]);
    linkedReady.socket.close();
  });

  it("writes no more rows for a receipt than before", async () => {
    const a = await sendDm(0, 1, "one");
    expect(written((await ackAs(1, undefined, a.result.seq)).headers)).toBeLessThanOrEqual(5);
  });

  it("refuses a device it has never heard of, and malformed device ids", async () => {
    const r = await inboxAs(1, dev(9));
    expect(r.status).toBe(403);
    expect(r.body.error).toEqual({ code: "unknown_device", retryable: false });
    expect((await inboxAs(1, "NOT-A-DEVICE")).status).toBe(400);
    expect((await inboxAs(1, dev(10).toUpperCase())).status).toBe(400);
    expect((await ackAs(1, "zz", 1)).status).toBe(400);
  });
});

describe("WP04-AC2 one queue, deleted after every active device acked", () => {
  it("keeps a message until both devices ack, whichever acks first", async () => {
    await linked();
    const a = await sendDm(0, 1, "one");
    await ackAs(1, "primary", a.result.seq);
    expect(await pendingSeqs(1)).toEqual([a.result.seq]);
    expect((await ackAs(1, dev(1), a.result.seq)).status).toBe(200);
    expect(await pendingSeqs(1)).toEqual([]);

    const b = await sendDm(0, 1, "two");
    await ackAs(1, dev(1), b.result.seq);
    expect(await pendingSeqs(1)).toEqual([b.result.seq]);
    await ackAs(1, "primary", b.result.seq);
    expect(await pendingSeqs(1)).toEqual([]);
  });

  it("deletes only up to the slower device's cursor", async () => {
    await linked();
    const [a, b, c] = [await sendDm(0, 1, "1"), await sendDm(0, 1, "2"), await sendDm(0, 1, "3")];
    await ackAs(1, "primary", c.result.seq);
    await ackAs(1, dev(1), b.result.seq);
    expect(await pendingSeqs(1)).toEqual([c.result.seq]);
    expect(a.result.seq).toBeLessThan(b.result.seq);
  });

  it("registers a device at the queue head so it never gets history, and later messages reach it", async () => {
    const old = await sendDm(0, 1, "before link");
    const reg = await registerDevice(1, dev(1));
    expect(reg.body.v).toBe(1);
    expect(reg.body.head).toBeGreaterThanOrEqual(old.result.seq);
    const fresh = await sendDm(0, 1, "after link");
    expect(fresh.result.seq).toBeGreaterThan(reg.body.head);
    expect(seqs(await inboxAs(1, dev(1), reg.body.head))).toEqual([fresh.result.seq]);
    // The pre-link message only waits for the primary, as before linking.
    await ackAs(1, "primary", old.result.seq);
    expect(await pendingSeqs(1)).toEqual([fresh.result.seq]);
  });

  it("no longer waits for a device that was silent for more than 72 hours", async () => {
    await linked();
    const a = await sendDm(0, 1, "one");
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    await ackAs(1, "primary", a.result.seq);
    expect(await pendingSeqs(1)).toEqual([]);
  });

  it("still waits for a device that was silent for less than 72 hours", async () => {
    await linked();
    const a = await sendDm(0, 1, "one");
    await ageDevice(1, dev(1), DORMANT_MS - 3_600_000);
    await ackAs(1, "primary", a.result.seq);
    expect(await pendingSeqs(1)).toEqual([a.result.seq]);
  });

  it("flags a gap when a dormant device returns and keeps flagging it until acked after being shown", async () => {
    await linked();
    const lost = await sendDm(0, 1, "deleted while desktop was away");
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    await ackAs(1, "primary", lost.result.seq);
    const kept = await sendDm(0, 1, "still pending");

    const first = await inboxAs(1, dev(1), 0);
    expect(first.body.gap).toBe(true);
    expect(first.body.oldest).toBe(kept.result.seq);
    expect(seqs(first)).toEqual([kept.result.seq]);
    // Sticky: asking again shows it again.
    expect((await inboxAs(1, dev(1), 0)).body.gap).toBe(true);
    // The primary never sees a gap.
    expect((await inboxAs(1, "primary", 0)).body.gap).toBeUndefined();

    await ackAs(1, dev(1), kept.result.seq);
    expect((await inboxAs(1, dev(1), 0)).body.gap).toBeUndefined();
  });

  it("evaluates dormancy before refreshing last_seen", async () => {
    await linked();
    const lost = await sendDm(0, 1, "gone");
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    await ackAs(1, "primary", lost.result.seq);
    const before = (await deviceRow(1, dev(1))).last_seen;
    const reply = await inboxAs(1, dev(1), 0);
    expect(reply.body.gap).toBe(true); // a refresh-first implementation would have reported no gap
    expect((await deviceRow(1, dev(1))).last_seen).toBeGreaterThan(before);
  });

  it("does not report a gap for a dormant device that missed nothing", async () => {
    await linked();
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    const a = await sendDm(0, 1, "still pending");
    const reply = await inboxAs(1, dev(1), 0);
    expect(reply.body.gap).toBeUndefined();
    expect(seqs(reply)).toEqual([a.result.seq]);
  });

  it("never silently clears a gap that an early ack would have covered", async () => {
    await linked();
    const lost = await sendDm(0, 1, "gone");
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    await ackAs(1, "primary", lost.result.seq);
    const kept = await sendDm(0, 1, "pending");
    // The returning device acks before it ever asked for the inbox.
    const ack = await ackAs(1, dev(1), kept.result.seq);
    expect(ack.body.gap).toBe(true);
    const inbox = await inboxAs(1, dev(1), 0);
    expect(inbox.body.gap).toBe(true);
    await ackAs(1, dev(1), kept.result.seq);
    expect((await inboxAs(1, dev(1), 0)).body.gap).toBeUndefined();
  });

  it("does not let a returning device lower the deletion floor", async () => {
    await linked();
    const lost = await sendDm(0, 1, "gone");
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    await ackAs(1, "primary", lost.result.seq);
    const floor = (await cursorState(1)).floor;
    expect(floor).toBe(lost.result.seq);

    await inboxAs(1, dev(1), 0); // reactivates
    expect((await deviceRow(1, dev(1))).acked).toBeGreaterThanOrEqual(floor);
    await ackAs(1, dev(1), 0); // a stale, lower receipt
    expect((await cursorState(1)).floor).toBe(floor);
    expect((await deviceRow(1, dev(1))).acked).toBeGreaterThanOrEqual(floor);

    // Reactivated: it holds the queue again.
    const next = await sendDm(0, 1, "next");
    await ackAs(1, "primary", next.result.seq);
    expect(await pendingSeqs(1)).toEqual([next.result.seq]);
    await ackAs(1, dev(1), next.result.seq);
    expect(await pendingSeqs(1)).toEqual([]);
  });

  it("flags a gap when messages expired unacked while the device was away", async () => {
    await linked();
    const old = await sendDm(0, 1, "will expire");
    await runInDurableObject(mailboxStub(), async (instance: Mailbox) => {
      const target = instance as any;
      target.limits = { ...target.limits, pendingLifetimeMs: 1 };
      target.lastPruneAt = 0;
    });
    await new Promise((r) => setTimeout(r, 5));
    await sendDm(2, 3, "triggers cleanup");
    expect(await pendingSeqs(1)).toEqual([]);
    expect((await cursorState(1)).floor).toBeGreaterThanOrEqual(old.result.seq);
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    expect((await inboxAs(1, dev(1), 0)).body.gap).toBe(true);
  });

  it("shows an active device an expiry gap until it sees and acknowledges the loss", async () => {
    await linked();
    const old = await sendDm(0, 1, "expires without either receipt");
    await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
      state.storage.sql.exec("UPDATE deliveries SET stored_at = 0 WHERE seq = ?", old.result.seq);
    });
    await runInDurableObject(mailboxStub(), async (instance: Mailbox) => { (instance as any).lastPruneAt = 0; });
    const fresh = await sendDm(2, 3, "trigger prune");
    expect(fresh.result.status).toBe("stored");
    expect(await pendingSeqs(1)).toEqual([]);
    const early = await ackAs(1, dev(1), old.result.seq);
    expect(early.body.gap).toBe(true);
    const shown = await inboxAs(1, dev(1));
    expect(shown.body.gap).toBe(true);
    expect((await inboxAs(1, dev(1))).body.gap).toBe(true);
    await ackAs(1, dev(1), old.result.seq);
    expect((await inboxAs(1, dev(1))).body.gap).toBeUndefined();
  });

  it("ignores a receipt that names a seq beyond anything stored instead of poisoning the cursor", async () => {
    await linked();
    const a = await sendDm(0, 1, "one");
    await ackAs(1, dev(1), Number.MAX_SAFE_INTEGER);
    await ackAs(1, "primary", a.result.seq);
    expect(await pendingSeqs(1)).toEqual([]);
    const later = await sendDm(0, 1, "two");
    await ackAs(1, "primary", later.result.seq);
    expect(await pendingSeqs(1)).toEqual([later.result.seq]);
  });

  it("serialises concurrent acks from both devices", async () => {
    await linked();
    const sent = [];
    for (let n = 0; n < 5; n++) sent.push(await sendDm(0, 1, `m${n}`));
    const top = sent.at(-1)!.result.seq;
    const replies = await Promise.all([ackAs(1, "primary", top), ackAs(1, dev(1), sent[2].result.seq), ackAs(1, dev(1), top)]);
    expect(replies.map((r) => r.status)).toEqual([200, 200, 200]);
    expect(await pendingSeqs(1)).toEqual([]);
    expect((await cursorState(1)).floor).toBe(top);
  });

  it("writes last_seen at most once an hour per device", async () => {
    await linked();
    await inboxAs(1, dev(1), 0);
    const second = await inboxAs(1, dev(1), 0);
    expect(written(second.headers)).toBe(1); // only this request's auth record
  });

  it("also gives the socket protocol per-device cursors and the gap flag", async () => {
    await linked();
    const lost = await sendDm(0, 1, "gone");
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    await ackAs(1, "primary", lost.result.seq);
    const kept = await sendDm(0, 1, "pending");

    const { socket, ready } = await connectAs(1, dev(1));
    expect(ready.gap).toBe(true);
    expect(ready.oldest).toBe(kept.result.seq);
    socket.send({ type: "sync", after: 0, ref: "s" });
    const batch = await socket.next();
    expect(batch).toMatchObject({ type: "batch", ref: "s", gap: true });
    socket.send({ type: "ack", through: kept.result.seq, ref: "a" });
    expect(await socket.next()).toMatchObject({ type: "acked", ref: "a", acked: 0 });
    expect(await pendingSeqs(1)).toEqual([kept.result.seq]); // primary has not acked yet
    await ackAs(1, "primary", kept.result.seq);
    expect(await pendingSeqs(1)).toEqual([]);
    socket.close();
  });
});

describe("WP04-AC3 device management is primary-only", () => {
  it("registers, lists and removes devices as the primary", async () => {
    const reg = await registerDevice(1, dev(1), "Ubuntu desktop");
    expect(reg).toMatchObject({ status: 200, body: { v: 1 } });
    expect(typeof reg.body.head).toBe("number");
    const list = await listDevices(1);
    expect(list.status).toBe(200);
    expect(list.body.devices.map((d: any) => d.device).sort()).toEqual(["primary", dev(1)].sort());
    const desktop = list.body.devices.find((d: any) => d.device === dev(1));
    expect(desktop).toMatchObject({ label: "Ubuntu desktop", active: true });
    expect(Object.keys(desktop).sort()).toEqual(["active", "added_at", "device", "label", "last_seen"]);
    expect((await removeDevice(1, dev(1))).status).toBe(200);
    expect((await listDevices(1)).body.devices.map((d: any) => d.device)).toEqual(["primary"]);
  });

  it("refuses a non-primary device with 403 not_primary for every management call", async () => {
    await linked(2);
    for (const reply of [
      await registerDevice(1, dev(3), "x", dev(1)),
      await listDevices(1, dev(1)),
      await removeDevice(1, dev(2), dev(1)),
    ]) {
      expect(reply.status).toBe(403);
      expect(reply.body.error).toEqual({ code: "not_primary", retryable: false });
    }
    expect((await listDevices(1)).body.devices).toHaveLength(3);
  });

  it("refuses an unknown acting device and an unknown removal target", async () => {
    await linked();
    const acting = await registerDevice(1, dev(5), "x", dev(9));
    expect(acting.status).toBe(403);
    expect(acting.body.error.code).toBe("unknown_device");
    expect((await removeDevice(1, dev(9))).status).toBe(404);
    expect((await removeDevice(1, "primary")).status).toBe(400);
  });

  it("refuses unknown devices on inbox, ack and websocket once an identity has devices", async () => {
    await linked();
    expect((await inboxAs(1, dev(7))).body.error.code).toBe("unknown_device");
    expect((await ackAs(1, dev(7), 1)).status).toBe(403);
    expect((await upgradeAs(1, dev(7))).status).toBe(403);
  });

  it("enforces the device cap (3 besides the primary) and keeps re-registration idempotent", async () => {
    await linked(3);
    const fourth = await registerDevice(1, dev(4));
    expect(fourth.status).toBe(409);
    expect(fourth.body.error).toEqual({ code: "device_limit", retryable: false });
    expect((await listDevices(1)).body.devices).toHaveLength(4);

    // Registering an existing device again changes only its label and never moves its cursor.
    const a = await sendDm(0, 1, "pending");
    const again = await registerDevice(1, dev(1), "renamed");
    expect(again.status).toBe(200);
    expect((await deviceRow(1, dev(1))).label).toBe("renamed");
    expect((await deviceRow(1, dev(1))).acked).toBeLessThan(a.result.seq);
  });

  it("rejects malformed registrations", async () => {
    for (const body of [
      { v: 1, device: "primary", label: "x" },
      { v: 1, device: "ABCDEF".padEnd(32, "0"), label: "x" },
      { v: 1, device: "abc", label: "x" },
      { v: 1, device: dev(1), label: "x".repeat(65) },
      { v: 2, device: dev(1), label: "x" },
    ]) {
      const reply = await client(1).request("POST", "/v1/devices", body);
      expect(reply.status, JSON.stringify(body)).toBe(400);
    }
    expect(await tableCount("devices")).toBe(0);
  });

  it("only lets members manage devices", async () => {
    const reply = await client(11).request("POST", "/v1/devices", { v: 1, device: dev(1), label: "x" });
    expect(reply.status).toBe(403);
  });

  it("creates the primary row at the current acknowledged position on first registration", async () => {
    const acked = await sendDm(0, 1, "acked earlier");
    await ackAs(1, undefined, acked.result.seq);
    const waiting = await sendDm(0, 1, "waiting");
    await registerDevice(1, dev(1));
    expect((await deviceRow(1, "primary")).acked).toBe(waiting.result.seq - 1);
    expect((await cursorState(1)).floor).toBe(waiting.result.seq - 1);
  });

  it("deletes what only a removed device was holding, mid-flight", async () => {
    await linked();
    const a = await sendDm(0, 1, "held by the desktop");
    await ackAs(1, "primary", a.result.seq);
    expect(await pendingSeqs(1)).toEqual([a.result.seq]);
    expect((await removeDevice(1, dev(1))).status).toBe(200);
    expect(await pendingSeqs(1)).toEqual([]);
    expect((await inboxAs(1, dev(1))).status).toBe(403);
    expect((await ackAs(1, dev(1), a.result.seq)).status).toBe(403);
  });

  it("closes the removed device's sockets with 4009 and leaves the others open", async () => {
    await linked(2);
    const { socket: removed } = await connectAs(1, dev(1));
    const { socket: other } = await connectAs(1, dev(2));
    const { socket: phone } = await connectAs(1);
    expect((await removeDevice(1, dev(1))).status).toBe(200);
    expect(await removed.closed).toMatchObject({ code: 4009, reason: "unlinked" });
    const a = await sendDm(0, 1, "live");
    expect((await other.next()).seq).toBe(a.result.seq);
    expect((await phone.next()).seq).toBe(a.result.seq);
    other.close();
    phone.close();
  });
});
