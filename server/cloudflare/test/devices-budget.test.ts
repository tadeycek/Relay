import { runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { Mailbox } from "../src/mailbox";
import { client, mailboxStub, sendDm, user } from "./helpers";
import { ackAs, ageDevice, confirmMediaAs, dev, inboxAs, registerDevice, removeDevice } from "./device-helpers";

/**
 * WP04-AC5: rows read/written per operation, from the x-relay-sql-rows header (EXPOSE_METRICS=1). The test
 * pool sets it. Results are printed as one `WP04-ROWS` JSON line that linux-port/evidence/WP04.md quotes.
 * The assertions are ceilings, not the measurements: the evidence projects daily rows from the printed values.
 */
const rows = (headers: Headers) => {
  const m = /read=(\d+);written=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "");
  if (!m) throw new Error("no x-relay-sql-rows header: EXPOSE_METRICS must be 1");
  return { read: Number(m[1]), written: Number(m[2]) };
};
const mailboxRows = () => runInDurableObject(mailboxStub(), async (instance: Mailbox) => ({ ...(instance as any).rows as { read: number; written: number } }));
const diff = (a: { read: number; written: number }, b: { read: number; written: number }) => ({ read: b.read - a.read, written: b.written - a.written });

describe("WP04-AC5 rows per operation", () => {
  it("measures delivery, ack with 1 and 2 devices, registration, last_seen refresh and media confirm", async ({ annotate }) => {
    const m: Record<string, { read: number; written: number }> = {};

    // Legacy identity (user 2): no device table rows at all.
    const legacySend = await sendDm(0, 2, "legacy");
    m.delivery_legacy = rows(legacySend.reply.headers);
    m.ack_legacy = rows((await ackAs(2, undefined, legacySend.result.seq)).headers);

    // First registration creates the primary row, the cursor_state row and the device row.
    m.register_first_device = rows((await registerDevice(1, dev(1))).headers);
    // Remove it again: user 1 now has only the primary device row.
    m.remove_device = rows((await removeDevice(1, dev(1))).headers);

    const single = await sendDm(0, 1, "one device");
    m.delivery_linked = rows(single.reply.headers);
    m.ack_1_device = rows((await ackAs(1, "primary", single.result.seq)).headers);

    m.register_second_device = rows((await registerDevice(1, dev(2))).headers);

    const both = await sendDm(0, 1, "two devices");
    m.ack_2_devices_first = rows((await ackAs(1, "primary", both.result.seq)).headers); // nothing deleted yet
    m.ack_2_devices_final = rows((await ackAs(1, dev(2), both.result.seq)).headers); // deletes the message

    // last_seen refresh: a request more than an hour after the previous one writes the device row once.
    await ageDevice(1, dev(2), 61 * 60 * 1000);
    m.inbox_with_last_seen_refresh = rows((await inboxAs(1, dev(2), 0)).headers);
    m.inbox_no_refresh = rows((await inboxAs(1, dev(2), 0)).headers);

    // Media for a 2-device recipient: both Mailbox-side rows (RPC) and media-object rows.
    const put = await client(0).putMedia(new Uint8Array(2000).fill(7), [user(1).pk]);
    const before = await mailboxRows();
    const first = await confirmMediaAs(1, put.hash, "primary");
    const afterFirst = await mailboxRows();
    const second = await confirmMediaAs(1, put.hash, dev(2));
    const afterSecond = await mailboxRows();
    m.media_put_2_devices = rows(put.headers);
    m.media_confirm_2_devices_first = rows(first.headers); // media object rows; no RPC (primary row only is waiting? no: dev(2) waits)
    m.media_confirm_2_devices_second = rows(second.headers);
    m.media_confirm_mailbox_rpc_first = diff(before, afterFirst);
    m.media_confirm_mailbox_rpc_second = diff(afterFirst, afterSecond);

    // Legacy media for comparison: one recipient, one confirm.
    const legacyPut = await client(0).putMedia(new Uint8Array(2000).fill(9), [user(3).pk]);
    m.media_confirm_legacy = rows((await client(3).confirmMedia(legacyPut.hash)).headers);

    console.log("WP04-ROWS " + JSON.stringify(m));
    await annotate("WP04-ROWS " + JSON.stringify(m));
    // Ceilings (not budgets): a regression that doubles a cost fails here.
    expect(m.ack_legacy.written).toBeLessThanOrEqual(5);
    expect(m.ack_1_device.written).toBeLessThanOrEqual(6);
    expect(m.ack_2_devices_final.written).toBeLessThanOrEqual(8);
    expect(m.ack_2_devices_first.written).toBeLessThanOrEqual(3);
    expect(m.register_second_device.written).toBeLessThanOrEqual(3);
    expect(m.inbox_with_last_seen_refresh.written).toBe(2);
    expect(m.inbox_no_refresh.written).toBe(1);
    expect(m.delivery_linked.written).toBe(m.delivery_legacy.written);
  });

  it("keeps a legacy identity's rows identical whether or not other identities have devices", async () => {
    await sendDm(0, 2, "warm-up");
    const before = rows((await sendDm(0, 2, "a")).reply.headers);
    await registerDevice(1, dev(1));
    const after = rows((await sendDm(0, 2, "b")).reply.headers);
    expect(after).toEqual(before);
  });
});
