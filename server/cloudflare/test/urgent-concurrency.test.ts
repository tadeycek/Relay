import { describe, expect, it } from "vitest";
import { wrapDm } from "../client/mailbox-client";
import { client, newId, user } from "./helpers";
import { dev, registerDevice } from "./device-helpers";

/**
 * Review finding (WP11 B1): the urgent budget was checked before `sendWithMedia` awaited its blob
 * `attachCheck` RPC, and only spent after the message was stored. Concurrent urgent sends that carry
 * media therefore all passed the gate before any of them recorded a spend — a push-wake flood that the
 * budgets exist precisely to prevent.
 *
 * The budget must be checked and spent in the same synchronous section that stores the message.
 */

const blob = (bytes: number, seed = 1): Uint8Array => {
  const out = new Uint8Array(bytes);
  for (let i = 0; i < bytes; i++) out[i] = (i * 31 + seed) & 0xff;
  return out;
};

const urgentSend = (media: string[], to = 1, from = 0) =>
  client(from).request("POST", "/v1/send", {
    v: 1,
    deliveries: [{ to: user(to).pk, id: newId(), envelope: wrapDm(user(from), user(to).pk, "ring"), urgent: true }],
    media,
  });

describe("WP11 urgent budget under concurrency", () => {
  it("concurrent urgent media sends cannot exceed the per-recipient budget", async () => {
    await registerDevice(1, dev(1));
    const put = await client(0).putMedia(blob(2000), [user(1).pk]);
    expect(put.status).toBe(200);

    // Ten simultaneous urgent sends of the same blob. Only the first may pass: the per-recipient
    // budget is one urgent wake per 3 s.
    const replies = await Promise.all(Array.from({ length: 10 }, () => urgentSend([put.hash])));
    const stored = replies.filter((r) => r.status === 200).length;
    expect(stored, "only one concurrent urgent send may be stored; the rest must be rate limited").toBe(1);
    expect(replies.filter((r) => r.status === 429).length).toBe(9);
  });

  it("a non-urgent send with a media reference is unaffected by the urgent budget", async () => {
    await registerDevice(1, dev(1));
    const put = await client(0).putMedia(blob(2000, 2), [user(1).pk]);
    const first = await urgentSend([put.hash]);
    expect(first.status).toBe(200);
    // Not urgent: no budget is spent and none is required.
    const plain = await client(0).request("POST", "/v1/send", {
      v: 1,
      deliveries: [{ to: user(1).pk, id: newId(), envelope: wrapDm(user(0), user(1).pk, "hi") }],
      media: [put.hash],
    });
    expect(plain.status).toBe(200);
  });
});
