import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import type { MediaBlob } from "../src/media";
import { client, user } from "./helpers";
import {
  DORMANT_MS, ageDevice, confirmMediaAs, dev, getMediaAs, mediaStub, registerDevice, removeDevice,
} from "./device-helpers";

function blob(bytes: number, seed = 1): Uint8Array {
  const out = new Uint8Array(bytes);
  for (let i = 0; i < bytes; i++) out[i] = (i * 31 + seed) & 0xff;
  return out;
}

async function stored(hash: string) {
  return runInDurableObject(mediaStub(hash), async (_: MediaBlob, state) => ({
    chunks: state.storage.sql.exec("SELECT COUNT(*) AS n FROM chunks").one().n as number,
    recipients: state.storage.sql.exec("SELECT COUNT(*) AS n FROM recipients").one().n as number,
    alarm: await state.storage.getAlarm(),
  }));
}

const link = async (i: number, ...ids: string[]) => {
  for (const id of ids) expect((await registerDevice(i, id)).status).toBe(200);
};

describe("WP04-AC4 media is erased only after every active recipient device confirmed", () => {
  it("survives the first confirm and is erased after the second", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(2000), [user(1).pk]);
    expect(await stored(put.hash)).toMatchObject({ chunks: 1, recipients: 2 });

    expect((await getMediaAs(1, put.hash, "primary")).status).toBe(200);
    expect((await getMediaAs(1, put.hash, dev(1))).status).toBe(200);
    const first = await confirmMediaAs(1, put.hash, "primary");
    expect(first.body).toMatchObject({ remaining: 1 });
    expect(await stored(put.hash)).toMatchObject({ chunks: 1, recipients: 2 });
    expect((await getMediaAs(1, put.hash, dev(1))).status).toBe(200);

    const second = await confirmMediaAs(1, put.hash, dev(1));
    expect(second.body).toMatchObject({ remaining: 0 });
    expect(await stored(put.hash)).toMatchObject({ chunks: 0, recipients: 0, alarm: null });
    expect((await getMediaAs(1, put.hash, dev(1))).status).toBe(404);
  });

  it("works whichever device confirms first, and a repeated confirm changes nothing", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    expect((await confirmMediaAs(1, put.hash, dev(1))).body.remaining).toBe(1);
    expect((await confirmMediaAs(1, put.hash, dev(1))).body.remaining).toBe(1);
    expect((await confirmMediaAs(1, put.hash, "primary")).body.remaining).toBe(0);
    expect(await stored(put.hash)).toMatchObject({ chunks: 0 });
  });

  it("waits for every recipient pubkey as well as every device", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk, user(2).pk]);
    expect(await stored(put.hash)).toMatchObject({ recipients: 3 });
    await confirmMediaAs(1, put.hash, "primary");
    await confirmMediaAs(1, put.hash, dev(1));
    expect((await stored(put.hash)).chunks).toBe(1);
    expect((await client(2).confirmMedia(put.hash)).body).toMatchObject({ remaining: 0 });
    expect((await stored(put.hash)).chunks).toBe(0);
  });

  it("keeps the legacy recipient unchanged: no devices, no device parameter, one confirm", async () => {
    const put = await client(0).putMedia(blob(2000), [user(1).pk, user(2).pk]);
    expect(await stored(put.hash)).toMatchObject({ recipients: 2 });
    expect((await client(1).getMedia(put.hash)).status).toBe(200);
    expect((await client(1).confirmMedia(put.hash)).body).toMatchObject({ remaining: 1 });
    expect((await client(2).confirmMedia(put.hash)).body).toMatchObject({ remaining: 0 });
    expect((await stored(put.hash)).chunks).toBe(0);
  });

  it("lets a legacy phone (no device parameter) confirm the primary's share of a linked identity", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    expect((await client(1).confirmMedia(put.hash)).body).toMatchObject({ remaining: 1 });
    expect((await stored(put.hash)).chunks).toBe(1);
  });

  it("does not let a device that joined after the upload read or confirm it (snapshot at PUT)", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await link(1, dev(2));
    expect((await getMediaAs(1, put.hash, dev(2))).status).toBe(404);
    expect((await confirmMediaAs(1, put.hash, dev(2))).status).toBe(404);
    await confirmMediaAs(1, put.hash, "primary");
    await confirmMediaAs(1, put.hash, dev(1));
    expect((await stored(put.hash)).chunks).toBe(0);
  });

  it("refuses unknown devices on media with 403 unknown_device", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    expect((await getMediaAs(1, put.hash, dev(8))).status).toBe(403);
    expect((await confirmMediaAs(1, put.hash, dev(8))).body.error.code).toBe("unknown_device");
    expect((await getMediaAs(1, put.hash, "bogus")).status).toBe(400);
    expect((await stored(put.hash)).chunks).toBe(1);
  });

  it("stops waiting for a device that was removed mid-flight", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    expect((await removeDevice(1, dev(1))).status).toBe(200);
    expect((await getMediaAs(1, put.hash, dev(1))).status).toBe(403);
    expect((await confirmMediaAs(1, put.hash, "primary")).body).toMatchObject({ remaining: 0 });
    expect((await stored(put.hash)).chunks).toBe(0);
  });

  it("stops waiting for a device that went dormant after the upload", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    expect((await confirmMediaAs(1, put.hash, "primary")).body).toMatchObject({ remaining: 0 });
    expect((await stored(put.hash)).chunks).toBe(0);
  });

  it("schedules an earlier alarm when only a still-active device is left, then erases once it is dormant", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    const expiry = (await stored(put.hash)).alarm as number;
    expect((await confirmMediaAs(1, put.hash, "primary")).body).toMatchObject({ remaining: 1 });
    const early = (await stored(put.hash)).alarm as number;
    expect(early).toBeLessThan(expiry);
    expect(early).toBeGreaterThan(Date.now() + DORMANT_MS - 5 * 60_000);

    // The desktop is still within its 72 hours: the alarm re-arms instead of erasing.
    expect(await runDurableObjectAlarm(mediaStub(put.hash))).toBe(true);
    expect((await stored(put.hash)).chunks).toBe(1);
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    expect(await runDurableObjectAlarm(mediaStub(put.hash))).toBe(true);
    expect(await stored(put.hash)).toMatchObject({ chunks: 0, recipients: 0, alarm: null });
  });

  it("keeps the lifetime alarm after waiving a dormant desktop while another primary still owes the blob", async () => {
    await link(1, dev(1));
    const put = await client(0).putMedia(blob(1510), [user(1).pk, user(2).pk]);
    const lifetime = (await stored(put.hash)).alarm as number;
    expect((await confirmMediaAs(1, put.hash, "primary")).body.remaining).toBe(2);
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    expect(await runDurableObjectAlarm(mediaStub(put.hash))).toBe(true);
    expect(await stored(put.hash)).toMatchObject({ chunks: 1, recipients: 2, alarm: lifetime });
    await runInDurableObject(mediaStub(put.hash), async (_: MediaBlob, state) => {
      state.storage.sql.exec("UPDATE info SET stored_at = ?", Date.now() - 9 * 24 * 60 * 60 * 1000);
    });
    expect(await runDurableObjectAlarm(mediaStub(put.hash))).toBe(true);
    expect(await stored(put.hash)).toMatchObject({ chunks: 0, recipients: 0, alarm: null });
  });

  it("retains a concurrent repeat PUT for another recipient during final confirmation", async () => {
    const file = blob(1521);
    const put = await client(0).putMedia(file, [user(1).pk]);
    const [confirmation, retry] = await Promise.all([
      confirmMediaAs(1, put.hash), client(0).putMedia(file, [user(2).pk]),
    ]);
    expect(confirmation.status).toBe(200);
    expect(retry.status).toBe(200);
    expect((await getMediaAs(2, put.hash)).status).toBe(200);
  });

  it("retains a concurrent repeat PUT when a dormant device is being waived", async () => {
    await link(1, dev(1));
    const file = blob(1522);
    const put = await client(0).putMedia(file, [user(1).pk]);
    await ageDevice(1, dev(1), DORMANT_MS + 60_000);
    const [confirmation, retry] = await Promise.all([
      confirmMediaAs(1, put.hash, "primary"), client(0).putMedia(file, [user(2).pk]),
    ]);
    expect(confirmation.status).toBe(200);
    expect(retry.status).toBe(200);
    expect((await getMediaAs(2, put.hash)).status).toBe(200);
  });

  it("adds recipient devices when the same file is sent again", async () => {
    const file = blob(3000);
    await client(0).putMedia(file, [user(1).pk]);
    await link(1, dev(1));
    const again = await client(0).putMedia(file, [user(1).pk]);
    expect(again.body).toEqual({ v: 1, stored: true, duplicate: true });
    expect((await stored(again.hash)).recipients).toBe(2);
    expect((await getMediaAs(1, again.hash, dev(1))).status).toBe(200);
  });

  it("lets the uploader's own other device fetch the blob when the uploader names itself", async () => {
    await link(0, dev(1));
    const put = await client(0).putMedia(blob(1200), [user(0).pk]);
    expect(await stored(put.hash)).toMatchObject({ recipients: 2 });
    expect((await getMediaAs(0, put.hash, dev(1))).status).toBe(200);
    await confirmMediaAs(0, put.hash, "primary");
    expect((await stored(put.hash)).chunks).toBe(1);
    await confirmMediaAs(0, put.hash, dev(1));
    expect((await stored(put.hash)).chunks).toBe(0);
  });

  it("migrates a media object stored with the v1 recipients layout", async () => {
    const put = await client(0).putMedia(blob(1500), [user(1).pk]);
    await runInDurableObject(mediaStub(put.hash), async (_: MediaBlob, state) => {
      const sql = state.storage.sql;
      state.storage.transactionSync(() => {
        sql.exec("DROP TABLE recipients");
        sql.exec("CREATE TABLE recipients (pubkey TEXT PRIMARY KEY, confirmed INTEGER NOT NULL DEFAULT 0) WITHOUT ROWID");
        sql.exec("INSERT INTO recipients (pubkey) VALUES (?)", user(1).pk);
        sql.exec("UPDATE meta SET value = '1' WHERE key = 'schema'");
      });
    });
    const { evictDurableObject } = await import("cloudflare:test");
    await evictDurableObject(mediaStub(put.hash));
    expect((await client(1).getMedia(put.hash)).status).toBe(200);
    expect((await client(1).confirmMedia(put.hash)).body).toMatchObject({ remaining: 0 });
  });
});
