import { env, runInDurableObject } from "cloudflare:test";
import { authHeader, sha256Hex, type Reply } from "../client/mailbox-client";
import type { Mailbox } from "../src/mailbox";
import { BASE, TestSocket, client, mailboxStub, user, worker } from "./helpers";

/** Deterministic lowercase 32-hex device ids. */
export const dev = (n: number): string => n.toString(16).padStart(32, "0");
export const PRIMARY = "primary";

const HOUR = 60 * 60 * 1000;
export const DORMANT_MS = 72 * HOUR;

const withDevice = (path: string, device?: string): string =>
  device === undefined ? path : `${path}${path.includes("?") ? "&" : "?"}device=${device}`;

/** Requests as user [i] acting as [device] (undefined = no `device` parameter at all, i.e. a legacy client). */
export const inboxAs = (i: number, device?: string, after = 0): Promise<Reply> =>
  client(i).request("GET", withDevice(`/v1/inbox?after=${after}`, device));
export const ackAs = (i: number, device: string | undefined, through: number): Promise<Reply> =>
  client(i).request("POST", withDevice("/v1/ack", device), { v: 1, through });
export const registerDevice = (i: number, id: string, label = "desktop", as?: string): Promise<Reply> =>
  client(i).request("POST", withDevice("/v1/devices", as), { v: 1, device: id, label });
export const listDevices = (i: number, as?: string): Promise<Reply> => client(i).request("GET", withDevice("/v1/devices", as));
export const removeDevice = (i: number, id: string, as?: string): Promise<Reply> =>
  client(i).request("DELETE", withDevice(`/v1/devices/${id}`, as));

export async function connectAs(i: number, device?: string): Promise<{ socket: TestSocket; ready: any }> {
  const reply = await upgradeAs(i, device);
  if (reply.status !== 101 || !reply.webSocket) throw new Error(`upgrade refused: ${reply.status} ${await reply.text()}`);
  const socket = new TestSocket(reply.webSocket);
  const ready = await socket.next();
  if (ready?.type !== "ready") throw new Error(`expected ready, got ${JSON.stringify(ready)}`);
  return { socket, ready };
}

export async function upgradeAs(i: number, device?: string): Promise<Response> {
  const url = withDevice(client(i).socketUrl(), device);
  const auth = await authHeader(user(i).sk, "GET", url);
  return worker(new Request(withDevice(`${BASE}/v1/ws`, device), { headers: { Upgrade: "websocket", Authorization: auth } }));
}

/** Media requests with an optional `device` parameter. */
export async function getMediaAs(i: number, hash: string, device?: string): Promise<{ status: number; body: any }> {
  const url = withDevice(`${BASE}/v1/media/${hash}`, device);
  const res = await worker(new Request(url, { headers: { Authorization: await authHeader(user(i).sk, "GET", url) } }));
  const type = res.headers.get("content-type") ?? "";
  return { status: res.status, body: type.includes("json") ? await res.json() : new Uint8Array(await res.arrayBuffer()) };
}
export const confirmMediaAs = (i: number, hash: string, device?: string): Promise<Reply> =>
  client(i).request("DELETE", withDevice(`/v1/media/${hash}`, device));

/** Makes [device] look as if it was last seen [ms] earlier. */
export async function ageDevice(i: number, device: string, ms: number): Promise<void> {
  await runInDurableObject(mailboxStub(), async (_: Mailbox, state) => {
    state.storage.sql.exec("UPDATE devices SET last_seen = last_seen - ? WHERE pubkey = ? AND device = ?", ms, user(i).pk, device);
  });
}

export async function deviceRow(i: number, device: string): Promise<any> {
  return runInDurableObject(mailboxStub(), async (_: Mailbox, state) =>
    state.storage.sql.exec("SELECT * FROM devices WHERE pubkey = ? AND device = ?", user(i).pk, device).toArray()[0]);
}

export async function pendingSeqs(i: number): Promise<number[]> {
  return runInDurableObject(mailboxStub(), async (_: Mailbox, state) =>
    state.storage.sql.exec<{ seq: number }>("SELECT seq FROM deliveries WHERE recipient = ? ORDER BY seq", user(i).pk).toArray().map((r) => r.seq));
}

export async function cursorState(i: number): Promise<any> {
  return runInDurableObject(mailboxStub(), async (_: Mailbox, state) =>
    state.storage.sql.exec("SELECT * FROM cursor_state WHERE pubkey = ?", user(i).pk).toArray()[0]);
}

export async function tableCount(table: string): Promise<number> {
  return runInDurableObject(mailboxStub(), async (_: Mailbox, state) => state.storage.sql.exec(`SELECT COUNT(*) AS n FROM ${table}`).one().n as number);
}

export const mediaStub = (hash: string) => env.MEDIA.get(env.MEDIA.idFromName(hash));
export { sha256Hex };
