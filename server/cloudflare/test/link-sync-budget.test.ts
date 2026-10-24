import { describe, expect, it } from "vitest";
import { authHeader, sha256Hex } from "../client/mailbox-client";
import { BASE, user, worker } from "./helpers";

/**
 * Row costs for the WP05 link slot and sync channel, from x-relay-sql-rows (EXPOSE_METRICS=1 is set by
 * the test pool). The printed `WP05-ROWS` line is what linux-port/evidence/WP05-link-slot.md quotes; the
 * assertions are ceilings, not the measurements.
 */
const rows = (headers: Headers) => {
  const m = /read=(\d+);written=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "");
  if (!m) throw new Error("no x-relay-sql-rows header: EXPOSE_METRICS must be 1");
  return { read: Number(m[1]), written: Number(m[2]) };
};

const b64 = (bytes: Uint8Array) => {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
};

const newId = () => [...crypto.getRandomValues(new Uint8Array(8))].map((b) => b.toString(16).padStart(2, "0")).join("");

async function signed(method: string, path: string, body?: string | Uint8Array, extra: Record<string, string> = {}) {
  const url = BASE + path;
  const headers: Record<string, string> = { Authorization: await authHeader(user(1).sk, method, url, body), ...extra };
  if (typeof body === "string") headers["content-type"] = "application/json";
  return worker(new Request(url, { method, headers, body: body as BodyInit | undefined }));
}

describe("WP05 link and sync row costs", () => {
  it("measures link PUT, GET and completion, and sync parts, completion and fetch", async ({ annotate }) => {
    const m: Record<string, { read: number; written: number }> = {};

    const code = [...crypto.getRandomValues(new Uint8Array(32))].map((b) => b.toString(16).padStart(2, "0")).join("");
    const slot = await sha256Hex(code);
    const bundle = new Uint8Array(4096).fill(1);
    m.link_put = rows((await signed("PUT", `/v1/link/${slot}`, bundle)).headers);
    m.link_get = rows((await worker(new Request(`${BASE}/v1/link/${slot}`, { headers: { "x-relay-link-proof": code } }))).headers);
    m.link_get_retry = rows((await worker(new Request(`${BASE}/v1/link/${slot}`, { headers: { "x-relay-link-proof": code } }))).headers);
    m.link_complete = rows((await worker(new Request(`${BASE}/v1/link/${slot}`, { method: "DELETE", headers: { "x-relay-link-proof": code } }))).headers);

    const body = new Uint8Array(8000).fill(2);
    const id = newId();
    const part = (index: number) => JSON.stringify({
      v: 1, id, rev: 1, part: index, parts: 2, total: body.byteLength,
      hash: "",
      expires: Date.now() + 60_000,
      data: b64(body.slice(index * 4000, (index + 1) * 4000)),
    });
    const hash = await sha256Hex(body);
    const full = (index: number) => part(index).replace('"hash":""', `"hash":"${hash}"`);

    m.sync_part_first = rows((await signed("POST", "/v1/sync", full(0))).headers);
    m.sync_part_completing = rows((await signed("POST", "/v1/sync", full(1))).headers);
    m.sync_fetch = rows((await signed("GET", "/v1/sync?after=0")).headers);
    m.sync_fetch_one = rows((await signed("GET", `/v1/sync/${id}`)).headers);
    m.sync_ack = rows((await signed("POST", "/v1/sync/ack", JSON.stringify({ v: 1, through: 1 }))).headers);

    console.log("WP05-ROWS " + JSON.stringify(m));
    await annotate("WP05-ROWS " + JSON.stringify(m));

    expect(m.link_put.written).toBeLessThanOrEqual(4);
    expect(m.link_get.written).toBeLessThanOrEqual(2);
    expect(m.link_complete.written).toBeLessThanOrEqual(2);
    expect(m.sync_part_first.written).toBeLessThanOrEqual(3);
    expect(m.sync_part_completing.written).toBeLessThanOrEqual(8);
    expect(m.sync_fetch.read).toBeLessThanOrEqual(8);
    expect(m.sync_ack.written).toBeLessThanOrEqual(2);
  });
});
