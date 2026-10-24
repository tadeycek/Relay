import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { wrapDm } from "../client/mailbox-client";
import type { Mailbox } from "../src/mailbox";
import type { MediaBlob } from "../src/media";
import { client, mailboxStub, newId, user } from "./helpers";
import {
  DORMANT_MS, ageDevice, confirmMediaAs, dev, getMediaAs, inboxAs, mediaStub, pendingSeqs, registerDevice, removeDevice,
} from "./device-helpers";

/**
 * WP04-AC4: the upload -> enrol -> send race. The sender names the blobs it attaches in `media` (outside the
 * envelope, covered by the request signature); the server adds the devices that are active at the instant the
 * message is queued, for exactly the recipients that got a new copy.
 */
const blob = (bytes: number, seed = 1): Uint8Array => {
  const out = new Uint8Array(bytes);
  for (let i = 0; i < bytes; i++) out[i] = (i * 31 + seed) & 0xff;
  return out;
};
const HEX = (n: number) => n.toString(16).padStart(64, "0");

const delivery = (from: number, to: number, id = newId()) => ({ to: user(to).pk, id, envelope: wrapDm(user(from), user(to).pk, "photo") });
const sendTo = (from: number, tos: number[], media?: unknown) =>
  client(from).request("POST", "/v1/send", { v: 1, deliveries: tos.map((t) => delivery(from, t)), ...(media === undefined ? {} : { media }) });

const rows = (headers: Headers, name = "x-relay-sql-rows") => {
  const m = /read=(\d+);written=(\d+)/.exec(headers.get(name) ?? "");
  if (!m) throw new Error(`no ${name} header`);
  return { read: Number(m[1]), written: Number(m[2]) };
};

const link = async (i: number, ...ids: string[]) => {
  for (const id of ids) expect((await registerDevice(i, id)).status).toBe(200);
};

const pairs = (hash: string) => runInDurableObject(mediaStub(hash), async (_: MediaBlob, state) =>
  state.storage.sql.exec<{ pubkey: string; device: string; confirmed: number }>("SELECT pubkey, device, confirmed FROM recipients ORDER BY pubkey, device").toArray()
    .map((r) => `${r.pubkey.slice(0, 4)}/${r.device.slice(-2)}${r.confirmed ? "*" : ""}`));
const chunks = (hash: string) => runInDurableObject(mediaStub(hash), async (_: MediaBlob, state) => ({
  chunks: state.storage.sql.exec("SELECT COUNT(*) AS n FROM chunks").one().n as number,
  alarm: await state.storage.getAlarm(),
}));
const outbox = () => runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
  const has = state.storage.sql.exec("SELECT COUNT(*) AS n FROM sqlite_master WHERE name = 'media_grants'").one().n as number;
  return has ? (state.storage.sql.exec("SELECT COUNT(*) AS n FROM media_grants").one().n as number) : -1;
});
const pk = (i: number) => user(i).pk.slice(0, 4);
const short = (i: number, d: string) => `${pk(i)}/${d.slice(-2)}`;

describe("WP04-AC4 upload, enrol, send race", () => {
  it("reproduces the race: a device enrolled between PUT and the message can fetch the blob", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(2000), [user(1).pk]);
    expect(put.status).toBe(200);
    await link(1, dev(2)); // enrolled AFTER the upload

    const sent = await sendTo(0, [1], [put.hash]);
    expect(sent.status).toBe(200);
    expect(sent.body.results[0].status).toBe("stored");
    expect(sent.body.media).toEqual([{ hash: put.hash, status: "attached" }]);

    expect((await inboxAs(1, dev(2))).body.messages).toHaveLength(1); // it receives the ciphertext ...
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(200); // ... and can fetch what it refers to
    expect(await outbox()).toBe(0);
  });

  it("the blob stays until the late device confirmed too (lifetime and settle)", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    const lifetime = (await chunks(put.hash)).alarm as number;
    await link(1, dev(2));
    await sendTo(0, [1], [put.hash]);
    expect(await pairs(put.hash)).toEqual([short(1, "primary").replace(/\/.*/, "/ry"), short(1, dev(1)), short(1, dev(2))].sort());
    // Lifetime invariant: still armed, never later than the lifetime (settle may move it earlier for a dormancy check).
    const armed = (await chunks(put.hash)).alarm as number;
    expect(armed).not.toBeNull();
    expect(armed).toBeLessThanOrEqual(lifetime);
    for (const d of ["primary", dev(1)]) await confirmMediaAs(1, put.hash, d);
    expect((await chunks(put.hash)).chunks).toBe(1);
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(200);
    expect((await confirmMediaAs(1, put.hash, dev(2))).body).toMatchObject({ remaining: 0 });
    expect(await chunks(put.hash)).toEqual({ chunks: 0, alarm: null });
  });

  it("re-arms the early dormancy alarm for a late device, and the lifetime alarm survives", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await link(1, dev(2));
    await sendTo(0, [1], [put.hash]);
    await confirmMediaAs(1, put.hash, "primary");
    await confirmMediaAs(1, put.hash, dev(1));
    const early = (await chunks(put.hash)).alarm as number;
    expect(early).toBeGreaterThan(Date.now() + DORMANT_MS - 5 * 60_000); // waiting only on dev(2)
    await ageDevice(1, dev(2), DORMANT_MS + 60_000);
    expect(await runDurableObjectAlarm(mediaStub(put.hash))).toBe(true);
    expect(await chunks(put.hash)).toEqual({ chunks: 0, alarm: null });
  });

  it("legacy clients (no media field): byte-identical reply, no new rows, no table, no RPC metrics", async () => {
    const d = delivery(0, 1, "legacy-1");
    const body = { v: 1, deliveries: [d] };
    const plain = await client(0).request("POST", "/v1/send", body);
    const seq = plain.body.results[0].seq;
    expect(JSON.stringify(plain.body)).toBe(`{"v":1,"results":[{"to":"${d.to}","id":"legacy-1","status":"stored","seq":${seq}}]}`);
    expect(plain.headers.get("x-relay-media-rows")).toBeNull();
    expect(await outbox()).toBe(-1); // table never created

    const empty = await client(0).request("POST", "/v1/send", { ...body, media: [], deliveries: [delivery(0, 1, "legacy-2")] });
    expect(empty.status).toBe(200);
    expect(Object.keys(empty.body)).toEqual(["v", "results"]);
    expect(await outbox()).toBe(-1);

    // Same starting state for two recipients: a send with `media: []` costs exactly what a send without the field costs.
    for (const to of [2, 3]) await client(0).request("POST", "/v1/send", { v: 1, deliveries: [delivery(0, to, `warm-${to}`)] });
    const a = rows((await client(0).request("POST", "/v1/send", { v: 1, deliveries: [delivery(0, 2, "same-shape-a")] })).headers);
    const b = rows((await client(0).request("POST", "/v1/send", { v: 1, deliveries: [delivery(0, 3, "same-shape-b")], media: [] })).headers);
    expect(b).toEqual(a);
    expect(await outbox()).toBe(-1);
  });

  it("does not add a device revoked before the send", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await link(1, dev(2));
    expect((await removeDevice(1, dev(2))).status).toBe(200);
    const sent = await sendTo(0, [1], [put.hash]);
    expect(sent.body.media[0].status).toBe("attached");
    expect(await pairs(put.hash)).not.toContain(short(1, dev(2)));
    expect([401, 403]).toContain((await getMediaAs(1, put.hash, dev(2))).status);
  });

  it("does not add a dormant device", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await link(1, dev(2));
    await ageDevice(1, dev(2), DORMANT_MS + 60_000);
    await sendTo(0, [1], [put.hash]);
    expect(await pairs(put.hash)).not.toContain(short(1, dev(2)));
  });

  it("revoke after the send: the device was added, but can neither read nor hold the blob", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await link(1, dev(2));
    await sendTo(0, [1], [put.hash]);
    expect(await pairs(put.hash)).toContain(short(1, dev(2)));
    expect((await removeDevice(1, dev(2))).status).toBe(200);
    expect([401, 403]).toContain((await getMediaAs(1, put.hash, dev(2))).status);
    await confirmMediaAs(1, put.hash, "primary");
    expect((await confirmMediaAs(1, put.hash, dev(1))).body).toMatchObject({ remaining: 0 }); // revoked pair waived
    expect((await chunks(put.hash)).chunks).toBe(0);
  });

  it("revoke racing the send: whichever order wins, a revoked device never reads the blob", async () => {
    for (let round = 0; round < 3; round++) {
      await link(1, dev(1));
      const put = await client(0).putMedia(blob(1500, 10 + round), [user(1).pk]);
      await link(1, dev(2));
      const [sent, removed] = await Promise.all([sendTo(0, [1], [put.hash]), removeDevice(1, dev(2))]);
      expect(sent.status).toBe(200);
      expect(removed.status).toBe(200);
      expect(sent.body.media[0].status).toBe("attached");
      expect([401, 403]).toContain((await getMediaAs(1, put.hash, dev(2))).status);
      await removeDevice(1, dev(1));
    }
  });

  it("a device enrolled AFTER the message was queued gets no access and never receives the message", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await sendTo(0, [1], [put.hash]);
    const enrolled = await registerDevice(1, dev(3));
    expect(enrolled.status).toBe(200);
    // A new device starts at the head it was given; the message queued before that is not its message.
    expect((await inboxAs(1, dev(3), enrolled.body.head)).body.messages).toHaveLength(0);
    expect((await getMediaAs(1, put.hash, dev(3))).status).toBe(404);
    expect(await pairs(put.hash)).not.toContain(short(1, dev(3)));
  });

  it("refuses a blob uploaded by another identity: nothing is queued, nothing is granted", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await link(1, dev(2));
    const before = await pairs(put.hash);
    const stolen = await sendTo(2, [1], [put.hash]);
    expect(stolen.status).toBe(409);
    expect(stolen.body.error).toEqual({ code: "media_unavailable", retryable: false });
    expect(await pendingSeqs(1)).toEqual([]);
    expect(await pairs(put.hash)).toEqual(before);
    expect(await outbox()).toBe(-1);
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(404);
    // A second uploader of the same bytes does not become an owner either (its own PUT keeps its documented
    // "repeat upload adds recipients" effect; that is unchanged and needs possession of the bytes).
    expect((await client(2).putMedia(blob(1500), [user(3).pk])).body.duplicate).toBe(true);
    expect((await sendTo(2, [3], [put.hash])).status).toBe(409);
  });

  it("answers an unknown digest and a foreign digest identically (no existence oracle)", async () => {
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    const foreign = await sendTo(2, [1], [put.hash]);
    const unknown = await sendTo(2, [1], [HEX(7)]);
    expect([foreign.status, foreign.body]).toEqual([unknown.status, unknown.body]);
  });

  it("bounds the digest list at 8 and rejects malformed entries without queuing", async () => {
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    const ok = Array.from({ length: 8 }, (_, i) => (i === 0 ? put.hash : HEX(i)));
    expect((await sendTo(0, [1], ok)).status).toBe(409); // 8 is allowed by the parser; the unknown ones are refused
    const tooMany = await sendTo(0, [1], [...ok, HEX(99)]);
    expect([tooMany.status, tooMany.body.error.code]).toEqual([400, "too_many_media"]);
    const bad: unknown[] = [
      "not-an-array", null, [null], [123], [put.hash.toUpperCase()], [put.hash.slice(1)], [put.hash + "0"],
      [put.hash, put.hash], [{ hash: put.hash }], ["../" + put.hash.slice(3)], { 0: put.hash },
    ];
    for (const media of bad) {
      const r = await sendTo(0, [1], media);
      expect([r.status, r.body?.error?.code], JSON.stringify(media)).toEqual([400, "bad_request"]);
    }
    expect(await pendingSeqs(1)).toEqual([]);
    expect(await outbox()).toBe(-1);
  });

  it("refuses a blob that has expired, and queues nothing", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await runInDurableObject(mediaStub(put.hash), async (_: MediaBlob, state) => {
      state.storage.sql.exec("UPDATE info SET stored_at = ?", Date.now() - 9 * 24 * 60 * 60 * 1000);
    });
    await link(1, dev(2));
    const r = await sendTo(0, [1], [put.hash]);
    expect([r.status, r.body.error.code]).toEqual([409, "media_unavailable"]);
    expect(await pendingSeqs(1)).toEqual([]);
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(404);
  });

  it("a blob that vanishes between the check and the grant is reported 'gone' while the message stays queued", async () => {
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await runInDurableObject(mailboxStub(), async (mailbox: Mailbox) => {
      const real = (mailbox as any).blob.bind(mailbox);
      (mailbox as any).blob = (h: string) => {
        const stub = real(h);
        return { attachCheck: (u: string) => stub.attachCheck(u).then(async (ok: boolean) => { await confirmMediaAs(1, h); return ok; }), grant: (u: string, p: unknown) => stub.grant(u, p) };
      };
    });
    try {
      const r = await sendTo(0, [1], [put.hash]);
      expect(r.body.results[0].status).toBe("stored");
      expect(r.body.media).toEqual([{ hash: put.hash, status: "gone" }]);
      expect(await outbox()).toBe(0);
    } finally {
      await runInDurableObject(mailboxStub(), async (mailbox: Mailbox) => { delete (mailbox as any).blob; });
    }
  });

  it("group send: every receiving recipient's late devices are added; a recipient not on the blob is not", async () => {
    await link(1, dev(1));
    await link(2, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk, user(2).pk, user(3).pk]);
    await link(1, dev(2));
    await link(2, dev(2));
    await link(3, dev(2)); // user 3 is on the blob
    await link(4, dev(2)); // user 4 is NOT on the blob but gets the message
    const sent = await sendTo(0, [1, 2, 3, 4], [put.hash]);
    expect(sent.body.media).toEqual([{ hash: put.hash, status: "attached" }]);
    for (const i of [1, 2, 3]) expect((await getMediaAs(i, put.hash, dev(2))).status).toBe(200);
    expect((await getMediaAs(4, put.hash, dev(2))).status).toBe(404);
    expect((await getMediaAs(4, put.hash, "primary")).status).toBe(404);
    expect((await pairs(put.hash)).some((p) => p.startsWith(pk(4)))).toBe(false);
  });

  it("a recipient whose copy was not newly stored is not widened (duplicate retry, rejected delivery)", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    const d = delivery(0, 1, "retry-1");
    const first = await client(0).request("POST", "/v1/send", { v: 1, deliveries: [d], media: [put.hash] });
    expect(first.body.media[0].status).toBe("attached");
    await link(1, dev(2));
    const again = await client(0).request("POST", "/v1/send", { v: 1, deliveries: [d], media: [put.hash] });
    expect(again.body.results[0].status).toBe("duplicate");
    expect(again.body.media).toEqual([{ hash: put.hash, status: "unchanged" }]);
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(404); // dev(2) does not receive that message
    expect(await outbox()).toBe(0);

    const rejected = await client(0).request("POST", "/v1/send", { v: 1, deliveries: [{ ...delivery(0, 1), to: HEX(5) }], media: [put.hash] });
    expect(rejected.body.results[0].status).toBe("rejected");
    expect(rejected.body.media[0].status).toBe("unchanged");
  });

  it("queues exactly the devices active at queue time: not revoked, not dormant, not enrolled later (checked in the outbox)", async () => {
    await link(1, dev(1), dev(2), dev(3));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    expect((await removeDevice(1, dev(2))).status).toBe(200);
    await ageDevice(1, dev(3), DORMANT_MS + 60_000);
    await runInDurableObject(mailboxStub(), async (mailbox: Mailbox) => {
      const real = (mailbox as any).blob.bind(mailbox);
      (mailbox as any).blob = (h: string) => {
        const stub = real(h);
        return { attachCheck: (u: string) => stub.attachCheck(u), grant: () => Promise.reject(new Error("hold")) };
      };
    });
    const r = await sendTo(0, [1], [put.hash]);
    expect(r.body.media[0].status).toBe("pending");
    await link(1, dev(2)); // re-enrolled after the queue instant: must not appear in the earlier snapshot
    const queued = await runInDurableObject(mailboxStub(), async (mailbox: Mailbox, state) => {
      delete (mailbox as any).blob;
      return state.storage.sql.exec<{ pairs: string }>("SELECT pairs FROM media_grants").toArray().map((x) => (JSON.parse(x.pairs) as string[][]).map(([p, d]) => `${p.slice(0, 4)}/${d}`));
    });
    expect(queued).toHaveLength(1);
    expect(queued[0].slice().sort()).toEqual([`${pk(1)}/${dev(1)}`, `${pk(1)}/primary`].sort());
  });

  it("a grant that cannot reach its blob stays queued, the reply says pending, and the alarm applies it", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await link(1, dev(2));
    await runInDurableObject(mailboxStub(), async (mailbox: Mailbox) => {
      const real = (mailbox as any).blob.bind(mailbox);
      (mailbox as any).blob = (h: string) => {
        const stub = real(h);
        return { attachCheck: (u: string) => stub.attachCheck(u), grant: () => Promise.reject(new Error("blob unreachable")) };
      };
    });
    const r = await sendTo(0, [1], [put.hash]);
    expect(r.body.results[0].status).toBe("stored");
    expect(r.body.media).toEqual([{ hash: put.hash, status: "pending" }]);
    expect(await outbox()).toBe(1);
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(404);
    const alarm = await runInDurableObject(mailboxStub(), async (mailbox: Mailbox, state) => {
      delete (mailbox as any).blob; // back to the real binding
      return state.storage.getAlarm();
    });
    expect(alarm).not.toBeNull();
    expect(await runDurableObjectAlarm(mailboxStub())).toBe(true);
    expect(await outbox()).toBe(0);
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(200);
  });

  it("is race-safe against the last confirmation: either attached with a live blob, or gone with none", async () => {
    for (let round = 0; round < 4; round++) {
      await link(1, dev(1));
      const put = await client(0).putMedia(blob(1500, 40 + round), [user(1).pk]);
      await link(1, dev(2 + round));
      await confirmMediaAs(1, put.hash, "primary");
      const [sent, last] = await Promise.all([sendTo(0, [1], [put.hash]), confirmMediaAs(1, put.hash, dev(1))]);
      expect(last.status).toBe(200);
      const status = sent.status === 200 ? sent.body.media[0].status : sent.body.error.code;
      const state = await chunks(put.hash);
      if (status === "attached") {
        expect(state.chunks).toBe(1);
        expect((await getMediaAs(1, put.hash, dev(2 + round))).status).toBe(200);
        expect(state.alarm).not.toBeNull();
      } else {
        expect(["gone", "media_unavailable"]).toContain(status);
        expect(state).toEqual({ chunks: 0, alarm: null });
      }
      await reset1(round);
    }
  });

  it("is race-safe against a repeat PUT and the expiry alarm", async () => {
    await link(1, dev(1));
    const file = blob(1500, 77);
    const put = await client(0).putMedia(file, [user(1).pk]);
    await link(1, dev(2));
    const [sent, again, alarm] = await Promise.all([
      sendTo(0, [1], [put.hash]), client(0).putMedia(file, [user(1).pk, user(2).pk]), runDurableObjectAlarm(mediaStub(put.hash)),
    ]);
    expect([sent.status, again.status, alarm]).toEqual([200, 200, true]);
    // The alarm ran while the object still had its lifetime left and waiting devices: nothing was lost.
    expect((await chunks(put.hash)).chunks).toBe(1);
    expect(sent.body.media[0].status).toBe("attached");
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(200);
    expect((await getMediaAs(2, put.hash)).status).toBe(200);
  });

  it("measures rows: one blob and one recipient, and the worst bounded case", async ({ annotate }) => {
    const m: Record<string, { read: number; written: number }> = {};
    // One blob, one recipient with three linked devices, one of them enrolled after the upload.
    await link(5, dev(1), dev(2));
    const one = await client(0).putMedia(blob(1500, 90), [user(5).pk]);
    await link(5, dev(3));
    m.send_no_media = rows((await sendTo(0, [5])).headers);
    const sent = await sendTo(0, [5], [one.hash]);
    expect(sent.body.media[0].status).toBe("attached");
    m.send_1blob_1recipient_mailbox = rows(sent.headers);
    m.send_1blob_1recipient_blob = rows(sent.headers, "x-relay-media-rows");

    // Worst case under the test caps: 4 recipients (the blob recipient cap) x 3 late devices, 8 digests, one request.
    const recipients = [1, 2, 3, 4];
    const hashes: string[] = [];
    for (let k = 0; k < 8; k++) hashes.push((await client(0).putMedia(blob(1200 + k, 100 + k), recipients.map((i) => user(i).pk))).hash);
    for (const i of recipients) await link(i, dev(1), dev(2), dev(3));
    const worst = await sendTo(0, recipients, hashes);
    expect(worst.body.media.every((e: { status: string }) => e.status === "attached")).toBe(true);
    m.send_8blobs_4recipients_x3late_mailbox = rows(worst.headers);
    m.send_8blobs_4recipients_x3late_blob = rows(worst.headers, "x-relay-media-rows");
    for (const i of recipients) expect((await getMediaAs(i, hashes[7], dev(3))).status).toBe(200);
    console.log("WP04-MEDIA-RACE-ROWS " + JSON.stringify(m));
    await annotate("WP04-MEDIA-RACE-ROWS " + JSON.stringify(m));

    // Ceilings, not budgets. Mailbox: <= 8 outbox inserts and 8 deletes beyond the send. Blobs: <= 16 pair inserts each.
    expect(m.send_1blob_1recipient_mailbox.written - m.send_no_media.written).toBeLessThanOrEqual(5);
    expect(m.send_1blob_1recipient_blob.written).toBeLessThanOrEqual(3);
    expect(m.send_8blobs_4recipients_x3late_mailbox.written).toBeLessThanOrEqual(4 * 4 + 8 * 5 + 16);
    expect(m.send_8blobs_4recipients_x3late_blob.written).toBeLessThanOrEqual(8 * 16);
  });
});

async function reset1(round: number) {
  await removeDevice(1, dev(2 + round));
  await removeDevice(1, dev(1));
}
