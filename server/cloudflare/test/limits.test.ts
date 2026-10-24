import { describe, expect, it } from "vitest";
import { DEFAULT_LIMITS, limitsFrom } from "../src/protocol";
import { client, newId, sendDm, signedRequest, user } from "./helpers";

const KIB = 1024;
/** Opaque filler: the mailbox never parses envelopes, so size tests needn't build real wraps. */
const blob = (bytes: number) => "x".repeat(bytes);

async function sendBlob(from: number, to: number, bytes: number) {
  const reply = await client(from).send([{ to: user(to).pk, id: newId(), envelope: blob(bytes) }]);
  return reply.body.results[0];
}

describe("limits", () => {
  it("defaults to the pilot budgets when no overrides are set", () => {
    expect(limitsFrom({})).toEqual(DEFAULT_LIMITS);
    expect(DEFAULT_LIMITS.maxEnvelopeBytes).toBe(128 * KIB);
    expect(DEFAULT_LIMITS.maxPendingBytesPerRecipient).toBe(32 * KIB * KIB);
    expect(DEFAULT_LIMITS.maxPendingBytesTotal).toBe(256 * KIB * KIB);
    // WP05: a 128 KiB sealed bundle, a 10-minute slot, a 60 s lease and 10 proof guesses a minute.
    expect(DEFAULT_LIMITS.maxLinkBytes).toBe(128 * KIB);
    expect(DEFAULT_LIMITS.linkTtlMs).toBe(10 * 60 * 1000);
    expect(DEFAULT_LIMITS.linkLeaseMs).toBe(60 * 1000);
    expect(DEFAULT_LIMITS.linkAttemptsPerMinute).toBe(10);
    // 12 KiB decoded, so its base64 form stays inside the existing 16 KiB envelope bound.
    expect(DEFAULT_LIMITS.maxSyncBodyBytes * 4 / 3).toBeLessThanOrEqual(16 * KIB);
    expect(DEFAULT_LIMITS.maxSyncParts).toBe(8);
    expect(DEFAULT_LIMITS.maxSyncAssemblies).toBe(4);
  });

  it("ignores malformed overrides", () => {
    expect(limitsFrom({ MAX_ENVELOPE_BYTES: "lots", MAX_PAGE_BYTES: "-5" }).maxEnvelopeBytes).toBe(DEFAULT_LIMITS.maxEnvelopeBytes);
  });

  it("refuses an envelope over the size cap without retry", async () => {
    expect(await sendBlob(0, 1, 8 * KIB + 1)).toMatchObject({ status: "rejected", error: { code: "envelope_too_large", retryable: false } });
    expect((await sendBlob(0, 1, 8 * KIB)).status).toBe("stored");
  });

  it("refuses more deliveries than one request may carry", async () => {
    const deliveries = Array.from({ length: 17 }, () => ({ to: user(1).pk, id: newId(), envelope: "e" }));
    const reply = await client(0).send(deliveries);
    expect(reply.status).toBe(400);
    expect(reply.body).toMatchObject({ error: { code: "too_many_deliveries" } });
  });

  it("refuses recipients that aren't on the allowlist", async () => {
    const reply = await client(0).send([{ to: "ab".repeat(32), id: newId(), envelope: "e" }]);
    expect(reply.body.results[0]).toMatchObject({ status: "rejected", error: { code: "unknown_recipient", retryable: false } });
  });

  it.each([
    ["not JSON", "nope"],
    ["wrong version", JSON.stringify({ v: 2, deliveries: [] })],
    ["no deliveries", JSON.stringify({ v: 1, deliveries: [] })],
    ["bad recipient", JSON.stringify({ v: 1, deliveries: [{ to: "XYZ", id: "a", envelope: "e" }] })],
    ["bad id", JSON.stringify({ v: 1, deliveries: [{ to: "ab".repeat(32), id: "has space", envelope: "e" }] })],
    ["empty envelope", JSON.stringify({ v: 1, deliveries: [{ to: "ab".repeat(32), id: "a", envelope: "" }] })],
    ["repeated delivery", JSON.stringify({ v: 1, deliveries: [{ to: "ab".repeat(32), id: "a", envelope: "e" }, { to: "ab".repeat(32), id: "a", envelope: "e" }] })],
  ])("rejects a malformed send (%s)", async (_, body) => {
    const res = await signedRequest(0, "POST", "/v1/send", body);
    expect(res.status).toBe(400);
  });

  it("fills a recipient to its cap, keeps what was accepted, and frees space on receipt", async () => {
    const stored: number[] = [];
    for (let i = 0; i < 6; i++) {
      const r = await sendBlob(0, 1, 4000);
      expect(r.status).toBe("stored");
      stored.push(r.seq);
    }
    // 24,000 of 24,576 bytes pending: one more 4,000-byte envelope doesn't fit.
    expect(await sendBlob(0, 1, 4000)).toMatchObject({ status: "rejected", error: { code: "recipient_full", retryable: true } });
    // Other recipients are unaffected.
    expect((await sendBlob(0, 2, 4000)).status).toBe("stored");

    const pending = (await client(1).inbox(0, 20)).body;
    const all = [...pending.messages];
    if (pending.more) all.push(...(await client(1).inbox(pending.cursor, 20)).body.messages);
    expect(all.map((m: any) => m.seq)).toEqual(stored);
    expect(all.every((m: any) => m.envelope === blob(4000))).toBe(true);

    await client(1).ack(stored[1]);
    expect((await sendBlob(0, 1, 4000)).status).toBe("stored");
  });

  it("enforces the mailbox-wide cap across recipients", async () => {
    for (const to of [1, 2]) for (let i = 0; i < 6; i++) expect((await sendBlob(0, to, 4000)).status).toBe("stored");
    // 48,000 of 49,152 bytes pending in total.
    expect(await sendBlob(0, 3, 4000)).toMatchObject({ status: "rejected", error: { code: "mailbox_full", retryable: true } });
    const { cursor } = (await client(1).inbox(0, 20)).body;
    await client(1).ack(cursor);
    expect((await sendBlob(0, 3, 4000)).status).toBe("stored");
  });

  it("still delivers small real messages alongside", async () => {
    expect((await sendDm(0, 4, "fits")).result.status).toBe("stored");
  });
});
