import { runDurableObjectAlarm, runInDurableObject } from "cloudflare:test";
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { authHeader, sha256Hex } from "../client/mailbox-client";
import type { MediaBlob } from "../src/media";
import { OUTSIDER } from "./identities";
import { BASE, client, user, worker } from "./helpers";

/** Stand-in for an encrypted file: the server never looks inside. */
function blob(bytes: number, seed = 1): Uint8Array {
  const out = new Uint8Array(bytes);
  for (let i = 0; i < bytes; i++) out[i] = (i * 31 + seed) & 0xff;
  return out;
}

const stub = (hash: string) => env.MEDIA.get(env.MEDIA.idFromName(hash));

async function stored(hash: string) {
  return runInDurableObject(stub(hash), async (_: MediaBlob, state) => ({
    chunks: state.storage.sql.exec("SELECT COUNT(*) AS n FROM chunks").one().n as number,
    recipients: state.storage.sql.exec("SELECT COUNT(*) AS n FROM recipients").one().n as number,
    alarm: await state.storage.getAlarm(),
  }));
}

describe("media store", () => {
  it("stores an encrypted file in chunks and returns it intact to a named recipient", async () => {
    const file = blob(40_000); // three 16 KiB chunks in the test config
    const put = await client(0).putMedia(file, [user(1).pk]);
    expect(put.status).toBe(200);
    expect(put.body).toEqual({ v: 1, stored: true, duplicate: false });
    expect(await stored(put.hash)).toMatchObject({ chunks: 3, recipients: 1 });
    expect((await stored(put.hash)).alarm).not.toBeNull();

    const got = await client(1).getMedia(put.hash);
    expect(got.status).toBe(200);
    expect(await sha256Hex(got.bytes!)).toBe(put.hash);
    expect(got.bytes!.length).toBe(file.length);
  });

  it("lets only the named recipients download, and tells nobody else it exists", async () => {
    const put = await client(0).putMedia(blob(1000), [user(1).pk]);
    expect((await client(2).getMedia(put.hash)).status).toBe(404);
    // The uploader already has the file; the server doesn't hand it back.
    expect((await client(0).getMedia(put.hash)).status).toBe(404);
    expect((await client(OUTSIDER).getMedia(put.hash)).status).toBe(404);
    const anonymous = await worker(new Request(`${BASE}/v1/media/${put.hash}`));
    expect(anonymous.status).toBe(401);
  });

  it("deletes the file once every recipient has confirmed it", async () => {
    const put = await client(0).putMedia(blob(2000), [user(1).pk, user(2).pk]);
    expect((await client(1).confirmMedia(put.hash)).body).toMatchObject({ remaining: 1 });
    expect((await client(2).getMedia(put.hash)).status).toBe(200);
    expect((await client(2).confirmMedia(put.hash)).body).toMatchObject({ remaining: 0 });
    expect(await stored(put.hash)).toMatchObject({ chunks: 0, recipients: 0, alarm: null });
    expect((await client(2).getMedia(put.hash)).status).toBe(404);
  });

  it("deletes a file nobody collected when its lifetime ends", async () => {
    const put = await client(0).putMedia(blob(3000), [user(1).pk]);
    expect(await runDurableObjectAlarm(stub(put.hash))).toBe(true);
    expect(await stored(put.hash)).toMatchObject({ chunks: 0, recipients: 0 });
    expect((await client(1).getMedia(put.hash)).status).toBe(404);
  });

  it("treats a repeated upload as the same file and only adds recipients", async () => {
    const file = blob(5000);
    await client(0).putMedia(file, [user(1).pk]);
    const again = await client(0).putMedia(file, [user(1).pk, user(3).pk]);
    expect(again.body).toEqual({ v: 1, stored: true, duplicate: true });
    expect(await stored(again.hash)).toMatchObject({ chunks: 1, recipients: 2 });
    expect((await client(3).getMedia(again.hash)).status).toBe(200);
  });

  it("refuses bytes that don't match their address", async () => {
    const file = blob(1000);
    const wrongHash = await sha256Hex(blob(1000, 2));
    const url = `${BASE}/v1/media/${wrongHash}`;
    const res = await worker(new Request(url, {
      method: "PUT", body: file,
      headers: { Authorization: await authHeader(user(0).sk, "PUT", url, file), "x-relay-recipients": user(1).pk },
    }));
    expect(res.status).toBe(400);
  });

  it.each([
    ["no recipients", [] as string[], 400],
    ["too many recipients", [1, 2, 3, 4, 5].map((i) => user(i).pk), 400],
    ["a recipient who isn't on the pilot", ["ab".repeat(32)], 400],
  ])("refuses an upload with %s", async (_, recipients, status) => {
    expect((await client(0).putMedia(blob(500), recipients)).status).toBe(status);
  });

  it("refuses a file over the size limit before it reaches storage", async () => {
    const put = await client(0).putMedia(blob(65_537), [user(1).pk]);
    expect(put.status).toBe(413);
  });
});
