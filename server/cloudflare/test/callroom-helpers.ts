import { env, runInDurableObject } from "cloudflare:test";
import { authHeader } from "../client/mailbox-client";
import type { CallRoom } from "../src/callroom";
import { BASE, TestSocket, worker } from "./helpers";

/** Deterministic 32-hex room ids and 64-hex join secrets. Test constants only. */
export const roomId = (n: number): string => n.toString(16).padStart(32, "0");
export const joinSecret = (n: number): string => n.toString(16).padStart(64, "0");

export const callroomStub = (room: string) => env.CALLROOM.get(env.CALLROOM.idFromName(room));

/** Fake SFU credentials. They exist only in these tests; nothing here is a real key. */
export const SFU_APP_ID = "0123456789abcdef0123456789abcdef";
export const SFU_APP_SECRET = "sfu-app-secret-SECRET-do-not-leak-0123456789";

export interface SfuCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string;
  redirect: string;
}

export type SfuHandler = (call: SfuCall, request: Request) => Response | Promise<Response>;

/** Configures the room with fake SFU credentials and replaces its upstream fetch. Returns the recorded calls. */
export async function fakeSfu(room: string, handler?: SfuHandler): Promise<SfuCall[]> {
  const calls: SfuCall[] = [];
  const respond: SfuHandler = handler ?? (() => new Response(JSON.stringify({ sessionId: "sess-a", sessionDescription: { type: "answer", sdp: "answer" } }), { status: 201, headers: { "content-type": "application/json" } }));
  await runInDurableObject(callroomStub(room), async (instance: CallRoom) => {
    (instance as any).sfuConfig = () => ({ appId: SFU_APP_ID, secret: SFU_APP_SECRET });
    (instance as any).sfuFetch = async (request: Request) => {
      const headers: Record<string, string> = {};
      request.headers.forEach((value, key) => (headers[key] = value));
      const call: SfuCall = { url: request.url, method: request.method, headers, body: await request.text(), redirect: request.redirect };
      calls.push(call);
      return respond(call, request);
    };
  });
  return calls;
}

export interface Reply {
  status: number;
  body: any;
  headers: Headers;
}

export const withDevice = (path: string, device?: string): string =>
  device === undefined ? path : `${path}${path.includes("?") ? "&" : "?"}device=${device}`;

/** A signed request to an arbitrary path; [sk] is the NIP-98 signer. */
export async function reqAs(sk: Uint8Array, method: string, path: string, body?: unknown): Promise<Reply> {
  const url = BASE + path;
  const text = body === undefined ? undefined : JSON.stringify(body);
  const headers: Record<string, string> = { Authorization: await authHeader(sk, method, url, text) };
  if (text !== undefined) headers["content-type"] = "application/json";
  const response = await worker(new Request(url, { method, headers, body: text }));
  const raw = await response.text();
  let parsed: any = raw;
  try {
    parsed = raw ? JSON.parse(raw) : null;
  } catch {
    // keep text
  }
  return { status: response.status, body: parsed, headers: response.headers };
}

/** Creates a room as [sk] acting as [device]; the raw secret is hashed here exactly as a client would. */
export async function createRoomAs(
  sk: Uint8Array,
  room: string,
  secret: string,
  device?: string,
): Promise<Reply> {
  const secretHash = await sha256Hex(secret);
  return reqAs(sk, "POST", withDevice("/v1/call/rooms", device), { v: 1, room, secretHash });
}

/** Opens the room socket. Returns the raw upgrade response so refusal tests can read the error. */
export async function joinAs(
  sk: Uint8Array,
  room: string,
  secret: string,
  device?: string,
  pid?: string,
): Promise<Response> {
  const params = new URLSearchParams({ secret });
  if (device !== undefined) params.set("device", device);
  if (pid !== undefined) params.set("pid", pid);
  const url = `${BASE}/v1/call/rooms/${room}/ws?${params.toString()}`;
  const auth = await authHeader(sk, "GET", url);
  return worker(new Request(url, { headers: { Upgrade: "websocket", Authorization: auth } }));
}

/** Joins and returns the accepted socket plus the first welcome frame. */
export async function joinSocket(
  sk: Uint8Array,
  room: string,
  secret: string,
  device?: string,
  pid?: string,
): Promise<{ socket: TestSocket; welcome: any; headers: Headers }> {
  const reply = await joinAs(sk, room, secret, device, pid);
  if (reply.status !== 101 || !reply.webSocket) {
    throw new Error(`join refused: ${reply.status} ${await reply.text()}`);
  }
  const socket = new TestSocket(reply.webSocket);
  const welcome = await socket.next();
  if (welcome?.type !== "welcome") throw new Error(`expected welcome, got ${JSON.stringify(welcome)}`);
  return { socket, welcome, headers: reply.headers };
}

/** Runs a callback in the live CallRoom object. */
export function inCallRoom<T>(room: string, work: (instance: CallRoom, state: DurableObjectState) => T | Promise<T>): Promise<T> {
  return runInDurableObject(callroomStub(room), work);
}

/** Reads a row count from the EXPOSE_METRICS header. */
export const sqlRows = (headers: Headers): { read: number; written: number } => {
  const m = /read=(\d+);written=(\d+)/.exec(headers.get("x-relay-sql-rows") ?? "");
  if (!m) throw new Error("no x-relay-sql-rows header: EXPOSE_METRICS must be 1");
  return { read: Number(m[1]), written: Number(m[2]) };
};

async function sha256Hex(data: string): Promise<string> {
  const bytes = new TextEncoder().encode(data);
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
