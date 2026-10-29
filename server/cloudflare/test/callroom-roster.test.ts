import { runInDurableObject } from "cloudflare:test";
import { schnorr } from "@noble/curves/secp256k1.js";
import { beforeEach, describe, expect, it } from "vitest";
import { authHeader } from "../client/mailbox-client";
import type { CallRoom } from "../src/callroom";
import { attestationCanonical, rosterHashOf } from "../src/callroom";
import { sha256Hex } from "../src/protocol";
import { BASE, TestSocket, client, mailboxStub, user, worker } from "./helpers";
import { callroomStub, createRoomAs, fakeSfu, inCallRoom, joinAs, joinSecret, joinSocket, reqAs, roomId } from "./callroom-helpers";
import { OUTSIDER } from "./identities";

/**
 * G5 server blockers for group calls (WP15 follow-up): frozen-roster binding, one live room per group
 * revision, removal revocation and capability rotation. Every test uses a fake provider fetch; nothing here
 * reaches Cloudflare.
 */

let nextRoom = 5000;
let nextGroup = 7000;
let ROOM = "";
let ROOM2 = "";
let ROOM3 = "";
let GROUP = "";
const SECRET = joinSecret(0x51);
const SECRET2 = joinSecret(0x52);
const SECRET3 = joinSecret(0x53);

const uuid = (n: number): string => `00000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`;
/** Sorted, deduped pubkeys of the given test users. */
const set = (...users: number[]): string[] => [...new Set(users.map((i) => user(i).pk))].sort();
const hex64 = (c: string): string => c.repeat(64);

beforeEach(() => {
  ROOM = roomId(nextRoom++);
  ROOM2 = roomId(nextRoom++);
  ROOM3 = roomId(nextRoom++);
  GROUP = uuid(nextGroup++);
});

interface Binding {
  group?: string;
  revision?: number;
  members?: string[];
  rosterHash?: string;
}

async function createBound(sk: Uint8Array, room: string, secret: string, binding: Binding, device?: string) {
  const members = binding.members ?? set(0, 1);
  const group = binding.group ?? GROUP;
  const revision = binding.revision ?? 1;
  const rosterHash = binding.rosterHash ?? (await sha256Hex(`${group}:${revision}:${[].concat(members as any).join(",")}`));
  const secretHash = await sha256Hex(secret);
  const path = device === undefined ? "/v1/call/rooms" : `/v1/call/rooms?device=${device}`;
  return reqAs(sk, "POST", path, { v: 1, room, secretHash, group, revision, rosterHash, members });
}

const count = (room: string, table: string): Promise<number> =>
  inCallRoom(room, (_: CallRoom, state) => Number(state.storage.sql.exec<{ n: number }>(`SELECT COUNT(*) AS n FROM ${table}`).one().n));

const dump = (room: string): Promise<Record<string, any[]>> =>
  inCallRoom(room, (_: CallRoom, state) => {
    const out: Record<string, any[]> = {};
    const tables = state.storage.sql.exec<{ name: string }>("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE '_cf_%'").toArray();
    for (const t of tables) out[t.name] = state.storage.sql.exec(`SELECT * FROM ${t.name}`).toArray();
    return out;
  });

const slots = (): Promise<any[]> =>
  runInDurableObject(mailboxStub(), (_: unknown, state: DurableObjectState) => state.storage.sql.exec("SELECT * FROM call_slots").toArray());

/** Slots of one group. The Mailbox object is shared by every test, so assertions are scoped to a group. */
const groupSlots = async (group = GROUP): Promise<any[]> => {
  const key = await sha256Hex(`relay-call-group-v1\n${group}`);
  return (await slots()).filter((s) => s.group_key === key);
};
const slotCount = async (): Promise<number> => (await slots()).length;

const post = (sk: Uint8Array, room: string, op: string, body: unknown, device?: string) =>
  reqAs(sk, "POST", `/v1/call/rooms/${room}/${op}${device ? `?device=${device}` : ""}`, body);

const SFU_OK = (call: { url: string }) => {
  const json = (v: unknown) => new Response(JSON.stringify(v), { status: 200, headers: { "content-type": "application/json" } });
  if (call.url.endsWith("/sessions/new")) return json({ sessionId: "sess-a" });
  if (call.url.endsWith("/tracks/new")) return json({ tracks: [{ mid: "0", trackName: "mic" }] });
  if (call.url.endsWith("/tracks/close")) return json({ tracks: [{ mid: "0" }] });
  return json({});
};

// ---- (1) frozen-roster binding ---------------------------------------------------------------

describe("G5 frozen roster binding at create", () => {
  it("stores only hashes and the member set, never the group id, name or avatar", async () => {
    const reply = await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2), revision: 3 });
    expect(reply.status).toBe(200);
    // G5 signed roster: an unsigned (legacy) create reports verified:false; see callroom-attestation.test.ts.
    expect(reply.body).toEqual({ v: 1, created: true, rostered: true, verified: false });
    expect(reply.headers.get("x-relay-call-rostered")).toBe("1");

    const all = await dump(ROOM);
    const text = JSON.stringify(all);
    expect(text).not.toContain(GROUP);
    expect(text).not.toContain(SECRET);
    expect(all.roster_members.map((r) => r.pubkey).sort()).toEqual(set(0, 1, 2));
    expect(all.room_roster).toHaveLength(1);
    expect(all.room_roster[0].revision).toBe(3);
    // G5 signed roster adds only `verified`; the signature, owner key and group id are never stored.
    expect(Object.keys(all.room_roster[0]).sort()).toEqual(["group_key", "id", "revision", "roster_hash", "verified"].sort());
    expect(all.room_roster[0].verified).toBe(0);
    for (const rows of Object.values(all)) for (const row of rows) expect(Object.keys(row).join()).not.toMatch(/name|avatar|label/i);

    const mailbox = JSON.stringify(await slots());
    expect(mailbox).not.toContain(GROUP);
    expect(mailbox).not.toContain(user(1).pk);
  });

  it("exposes revision and roster hash only for rostered rooms and marks legacy rooms unrostered", async () => {
    const rosterHash = hex64("a");
    expect((await createBound(user(0).sk, ROOM, SECRET, { rosterHash })).status).toBe(200);
    const bound = await client(1).request("GET", `/v1/call/rooms/${ROOM}`);
    expect(bound.status).toBe(200);
    expect(bound.body).toMatchObject({ v: 1, room: ROOM, rostered: true, revision: 1, rosterHash });
    expect(bound.body.members).toBeUndefined();

    const legacy = await createRoomAs(user(0).sk, ROOM2, SECRET2);
    expect(legacy.status).toBe(200);
    expect(legacy.body).toEqual({ v: 1, created: true });
    expect(legacy.headers.get("x-relay-call-rostered")).toBe("0");
    expect(await count(ROOM2, "room_roster")).toBe(0);
    const unbound = await client(1).request("GET", `/v1/call/rooms/${ROOM2}`);
    expect(unbound.body).toEqual({ v: 1, room: ROOM2, participants: 0, createdAt: expect.any(Number) });
    expect(unbound.headers.get("x-relay-call-rostered")).toBe("0");
  });

  it("requires the creator to be in the frozen set and to be a server-allowed identity", async () => {
    const absent = await createBound(user(0).sk, ROOM, SECRET, { members: set(1, 2) });
    expect(absent.status).toBe(403);
    expect(absent.body.error.code).toBe("not_in_roster");
    expect(await count(ROOM, "room")).toBe(0);
    expect(await groupSlots()).toHaveLength(0);

    // A valid roster does not make a non-member a creator.
    const outsider = await createBound(user(OUTSIDER).sk, ROOM2, SECRET2, { members: [user(OUTSIDER).pk, user(0).pk].sort() });
    expect(outsider.status).toBe(403);
    expect(outsider.body.error.code).toBe("not_allowed");
    expect(await groupSlots()).toHaveLength(0);
  });

  it("rejects malformed, unsorted, duplicated, oversized or partial roster bindings without storing anything", async () => {
    const good = set(0, 1);
    const sixtyFive = Array.from({ length: 65 }, (_, i) => i.toString(16).padStart(64, "0"));
    const sixtyFour = [user(0).pk, ...Array.from({ length: 63 }, (_, i) => (i + 1).toString(16).padStart(64, "0"))].sort();
    const cases: Array<[string, Binding & Record<string, unknown>]> = [
      ["unsorted", { members: [...good].reverse() }],
      ["duplicated", { members: [good[0], good[0], good[1]].sort() }],
      ["uppercase", { members: good.map((m) => m.toUpperCase()) }],
      ["short member", { members: [...good, "ab"].sort() }],
      ["non-string member", { members: [...good, 7 as any] }],
      ["empty", { members: [] }],
      ["65 members", { members: [user(0).pk, ...sixtyFive].sort() }],
      ["not an array", { members: "x" as any }],
      ["bad group", { group: "not-a-uuid" }],
      ["uppercase group", { group: GROUP.toUpperCase().replace(/^0/, "0") + "A" }],
      ["revision 0", { revision: 0 }],
      ["revision float", { revision: 1.5 }],
      ["revision string", { revision: "1" as any }],
      ["revision unsafe", { revision: 2 ** 60 }],
      ["bad hash", { rosterHash: "zz" }],
    ];
    for (const [label, binding] of cases) {
      const reply = await createBound(user(0).sk, roomId(nextRoom++), SECRET, binding as Binding);
      expect(reply.status, label).toBe(400);
      expect(reply.body.error.code, label).toBe("bad_request");
    }
    // Partial binding: group present but no members.
    const secretHash = await sha256Hex(SECRET);
    const partial = await reqAs(user(0).sk, "POST", "/v1/call/rooms", { v: 1, room: ROOM, secretHash, group: GROUP, revision: 1 });
    expect(partial.status).toBe(400);
    expect(await count(ROOM, "room")).toBe(0);
    expect(await groupSlots()).toHaveLength(0);

    // Exactly 64 members is the accepted maximum.
    const edge = await createBound(user(0).sk, ROOM3, SECRET3, { members: sixtyFour });
    expect(edge.status).toBe(200);
  });

  it("is idempotent for the same binding and refuses the same room id with another binding", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    expect(await count(ROOM, "room")).toBe(1);
    const otherRev = await createBound(user(0).sk, ROOM, SECRET, { revision: 2 });
    expect(otherRev.status).toBe(409);
    expect(otherRev.body.error.code).toBe("room_exists");
    const otherMembers = await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) });
    expect(otherMembers.status).toBe(409);
    // A legacy create with the same hash must not strip the roster from a bound room.
    const legacy = await createRoomAs(user(0).sk, ROOM, SECRET);
    expect(legacy.status).toBe(409);
    expect(await count(ROOM, "roster_members")).toBe(2);
  });
});

describe("G5 unrostered (legacy) creates and the pilot flag", () => {
  it("accepts a legacy create by default and lets any server member join it (old clients keep working)", async () => {
    expect((await createRoomAs(user(0).sk, ROOM, SECRET)).status).toBe(200);
    const joined = await joinSocket(user(5).sk, ROOM, SECRET);
    expect(joined.welcome.type).toBe("welcome");
    joined.socket.close();
  });

  it("refuses a legacy create with 400 roster_required when the pilot flag forbids it", async () => {
    await inCallRoom(ROOM, (instance: CallRoom) => {
      (instance as any).allowUnrostered = false;
    });
    const reply = await createRoomAs(user(0).sk, ROOM, SECRET);
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("roster_required");
    expect(await count(ROOM, "room")).toBe(0);
    // A bound create is still fine under the strict flag.
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
  });
});

describe("G5 joins are checked against the frozen member set", () => {
  it("lets a rostered member in, refuses a server member that is not on the roster even with the right secret", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    const member = await joinSocket(user(1).sk, ROOM, SECRET);
    expect(member.welcome.type).toBe("welcome");
    const refused = await joinAs(user(2).sk, ROOM, SECRET);
    expect(refused.status).toBe(403);
    expect(((await refused.json()) as any).error.code).toBe("not_in_roster");
    expect(await count(ROOM, "participants")).toBe(1);
    // Wrong secret still wins the 403 forbidden for a roster member.
    const wrong = await joinAs(user(1).sk, ROOM, joinSecret(0x99));
    expect(((await wrong.json()) as any).error.code).toBe("forbidden");
    member.socket.close();
  });

  it("refuses a pid reconnect by an identity that is no longer on the roster", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    const first = await joinSocket(user(1).sk, ROOM, SECRET);
    const pid = first.welcome.you;
    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] })).status).toBe(200);
    await first.socket.closed;
    const back = await joinAs(user(1).sk, ROOM, SECRET, undefined, pid);
    expect(back.status).toBe(403);
    expect(((await back.json()) as any).error.code).toBe("not_in_roster");
  });

  it("applies the roster check to the existence query: a non-member of the roster sees 404", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    expect((await client(1).request("GET", `/v1/call/rooms/${ROOM}`)).status).toBe(200);
    const stranger = await client(2).request("GET", `/v1/call/rooms/${ROOM}`);
    expect(stranger.status).toBe(404);
  });

  it("keeps the 17-participant cap for a rostered room", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    await inCallRoom(ROOM, (instance: CallRoom) => {
      (instance as any).limits.maxCallParticipants = 2;
    });
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    const b = await joinSocket(user(1).sk, ROOM, SECRET);
    const refused = await joinAs(user(2).sk, ROOM, SECRET);
    expect(refused.status).toBe(409);
    expect(((await refused.json()) as any).error.code).toBe("room_full");
    a.socket.close();
    b.socket.close();
    // The shipped default is the product cap.
    expect(await inCallRoom(ROOM, (instance: CallRoom) => (instance as any).limits.maxCallParticipants)).toBe(2);
    expect(await inCallRoom(ROOM2, (instance: CallRoom) => (instance as any).limits.maxCallParticipants)).toBe(17);
  });
});

// ---- (2) atomic create-if-absent -------------------------------------------------------------

describe("G5 one live room per (group, revision)", () => {
  it("two concurrent creates return the same room id: one 200, one 409 naming the winner without its secret", async () => {
    const [a, b] = await Promise.all([
      createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) }),
      createBound(user(1).sk, ROOM2, SECRET2, { members: set(0, 1) }),
    ]);
    const ok = [a, b].filter((r) => r.status === 200);
    const lost = [a, b].filter((r) => r.status === 409);
    expect(ok).toHaveLength(1);
    expect(lost).toHaveLength(1);
    const winnerRoom = a.status === 200 ? ROOM : ROOM2;
    const loserRoom = a.status === 200 ? ROOM2 : ROOM;
    expect(lost[0].body.error.code).toBe("group_room_exists");
    expect(lost[0].body.room).toBe(winnerRoom);
    const text = JSON.stringify(lost[0].body);
    expect(text).not.toContain(await sha256Hex(a.status === 200 ? SECRET : SECRET2));
    expect(text).not.toContain(SECRET);
    expect(text).not.toContain(SECRET2);
    expect(await count(loserRoom, "room")).toBe(0);
    expect(await count(loserRoom, "roster_members")).toBe(0);
    const stored = (await slots()).filter((s) => s.room === winnerRoom || s.room === loserRoom);
    expect(stored).toHaveLength(1);
    expect(stored[0].room).toBe(winnerRoom);
  });

  it("many concurrent starters still produce a single live room", async () => {
    const rooms = [0, 1, 2, 3, 4].map(() => roomId(nextRoom++));
    const replies = await Promise.all(rooms.map((room, i) => createBound(user(i).sk, room, joinSecret(0x60 + i), { members: set(0, 1, 2, 3, 4) })));
    expect(replies.filter((r) => r.status === 200)).toHaveLength(1);
    const winner = rooms[replies.findIndex((r) => r.status === 200)];
    for (const r of replies.filter((x) => x.status === 409)) expect(r.body.room).toBe(winner);
  });

  it("an idempotent retry of the winner is not a conflict", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    expect(await groupSlots()).toHaveLength(1);
  });

  it("a newer revision supersedes the old room only when its creator is in the newer frozen set", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { revision: 1, members: set(0, 1) })).status).toBe(200);
    const old = await joinSocket(user(1).sk, ROOM, SECRET);

    const excluded = await createBound(user(1).sk, ROOM2, SECRET2, { revision: 2, members: set(0, 2) });
    expect(excluded.status).toBe(403);
    expect(excluded.body.error.code).toBe("not_in_roster");
    expect((await client(1).request("GET", `/v1/call/rooms/${ROOM}`)).status).toBe(200);

    const newer = await createBound(user(1).sk, ROOM2, SECRET2, { revision: 2, members: set(0, 1, 2) });
    expect(newer.status).toBe(200);
    expect(await old.socket.next()).toMatchObject({ type: "end", reason: "superseded" });
    await old.socket.closed;
    expect((await client(1).request("GET", `/v1/call/rooms/${ROOM}`)).status).toBe(404);
    expect((await client(1).request("GET", `/v1/call/rooms/${ROOM2}`)).status).toBe(200);
    const mine = (await slots()).filter((s) => [ROOM, ROOM2].includes(s.room));
    expect(mine.map((s) => s.room)).toEqual([ROOM2]);
  });

  it("refuses a create for an older revision than the live one", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { revision: 5 })).status).toBe(200);
    const stale = await createBound(user(0).sk, ROOM2, SECRET2, { revision: 4 });
    expect(stale.status).toBe(409);
    expect(stale.body.error.code).toBe("stale_revision");
    expect(stale.body.room).toBeUndefined();
    expect(await count(ROOM2, "room")).toBe(0);
  });

  it("releases the slot when the creator ends the room", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    expect((await createBound(user(1).sk, ROOM2, SECRET2, {})).status).toBe(409);
    expect((await post(user(0).sk, ROOM, "end", undefined)).status).toBe(200);
    expect((await slots()).some((s) => s.room === ROOM)).toBe(false);
    expect((await createBound(user(1).sk, ROOM2, SECRET2, {})).status).toBe(200);
  });

  it("releases the slot when the last participant leaves", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    a.socket.send({ type: "leave" });
    await a.socket.closed;
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect((await createBound(user(1).sk, ROOM2, SECRET2, {})).status).toBe(200);
  });

  it("takes the slot over from a winner whose room no longer exists (a lost release never wedges a group)", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    // Simulate a crash between teardown and release: the room is gone but the slot remains.
    await inCallRoom(ROOM, async (instance: CallRoom, state) => {
      (instance as any).releaseSlot = async () => undefined;
      await state.storage.deleteAll();
      (instance as any).migrate();
    });
    expect((await slots()).some((s) => s.room === ROOM)).toBe(true);
    await ageSlot(ROOM, 60_000); // B3: takeover needs the slot older than the 30 s grace
    const next = await createBound(user(1).sk, ROOM2, SECRET2, {});
    expect(next.status).toBe(200);
    expect((await slots()).filter((s) => [ROOM, ROOM2].includes(s.room)).map((s) => s.room)).toEqual([ROOM2]);
  });

  it("keeps different groups and different revisions independent", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    expect((await createBound(user(0).sk, ROOM2, SECRET2, { group: uuid(nextGroup++) })).status).toBe(200);
  });
});

// ---- (3) removal revocation and capability rotation -----------------------------------------

describe("G5 roster removal revokes room access", () => {
  it("closes the removed identity's sockets with 4009, closes its provider track, and rejects its rejoin", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    const calls = await fakeSfu(ROOM, SFU_OK);
    const stay = await joinSocket(user(2).sk, ROOM, SECRET);
    const gone = await joinSocket(user(1).sk, ROOM, SECRET);
    await stay.socket.next(); // user(1) joined
    expect((await reqAs(user(1).sk, "POST", `/v1/call/rooms/${ROOM}/sfu/sessions-new`, { v: 1 })).status).toBe(200);
    expect((await reqAs(user(1).sk, "POST", `/v1/call/rooms/${ROOM}/sfu/tracks-new`, { v: 1, sessionId: "sess-a", tracks: [{ location: "local", mid: "0", trackName: "mic" }] })).status).toBe(200);
    await stay.socket.next(); // joined with track

    const removed = await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] });
    expect(removed.status).toBe(200);
    expect(removed.body).toEqual({ v: 1, removed: 1 });

    const closed = await gone.socket.closed;
    expect(closed.code).toBe(4009);
    await new Promise((resolve) => setTimeout(resolve, 150));
    expect(calls.some((c) => c.url.endsWith("/sessions/sess-a/tracks/close"))).toBe(true);
    expect(await count(ROOM, "pending_track_close")).toBe(0);

    const members = (await dump(ROOM)).roster_members.map((r) => r.pubkey).sort();
    expect(members).toEqual(set(0, 2));
    const row = (await dump(ROOM)).participants.find((p) => p.pubkey === user(1).pk);
    expect(row.left_at).toBeGreaterThan(0);
    expect(await stay.socket.next()).toMatchObject({ type: "left", pubkey: user(1).pk });

    const rejoin = await joinAs(user(1).sk, ROOM, SECRET);
    expect(rejoin.status).toBe(403);
    expect(((await rejoin.json()) as any).error.code).toBe("not_in_roster");
    const sfu = await reqAs(user(1).sk, "POST", `/v1/call/rooms/${ROOM}/sfu/sessions-new`, { v: 1 });
    expect(sfu.status).toBe(403);
    // The remaining participant is untouched.
    stay.socket.send({ type: "state", muted: true });
    expect(await stay.socket.silent(100)).toBe(true);
    expect(await count(ROOM, "participants")).toBe(2);
    stay.socket.close();
  });

  it("closes every device socket of the removed identity", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    const a = await joinSocket(user(1).sk, ROOM, SECRET);
    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] })).status).toBe(200);
    expect((await a.socket.closed).code).toBe(4009);
  });

  it("a live socket and the provider surface die on the next use if the roster no longer lists the identity", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    await fakeSfu(ROOM, SFU_OK);
    const a = await joinSocket(user(1).sk, ROOM, SECRET);
    expect((await reqAs(user(1).sk, "POST", `/v1/call/rooms/${ROOM}/sfu/sessions-new`, { v: 1 })).status).toBe(200);
    // Simulate a missed direct close: the member row disappears while the participant row and socket remain.
    await inCallRoom(ROOM, (instance: CallRoom, state) => {
      state.storage.sql.exec("DELETE FROM roster_members WHERE pubkey = ?", user(1).pk);
      (instance as any).invalidateRoster(); // the direct SQL edit bypasses the removal path that invalidates the cache
    });
    const sfu = await reqAs(user(1).sk, "POST", `/v1/call/rooms/${ROOM}/sfu/sessions-new`, { v: 1 });
    expect(sfu.status).toBe(403);
    a.socket.send({ type: "state", muted: true });
    expect((await a.socket.closed).code).toBe(4009);
  });

  it("is creator-only, removal-only and validated", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    const other = await post(user(1).sk, ROOM, "roster", { v: 1, remove: [user(2).pk] });
    expect(other.status).toBe(403);
    expect(other.body.error.code).toBe("forbidden");

    const bad: Array<[string, unknown]> = [
      ["creator removal", { v: 1, remove: [user(0).pk] }],
      ["not a member", { v: 1, remove: [user(5).pk] }],
      ["empty", { v: 1, remove: [] }],
      ["unsorted", { v: 1, remove: [...set(1, 2)].reverse() }],
      ["dup", { v: 1, remove: [user(1).pk, user(1).pk] }],
      ["format", { v: 1, remove: ["XYZ"] }],
      ["not array", { v: 1, remove: user(1).pk }],
      ["wrong version", { v: 2, remove: [user(1).pk] }],
      ["bad secretHash", { v: 1, remove: [user(1).pk], secretHash: "zz" }],
    ];
    for (const [label, body] of bad) {
      const reply = await post(user(0).sk, ROOM, "roster", body);
      expect(reply.status, label).toBe(400);
    }
    expect(await count(ROOM, "roster_members")).toBe(3);
    // Unrostered rooms have nothing to update.
    expect((await createRoomAs(user(0).sk, ROOM2, SECRET2)).status).toBe(200);
    expect((await post(user(0).sk, ROOM2, "roster", { v: 1, remove: [user(1).pk] })).status).toBe(409);
    expect((await post(user(0).sk, roomId(nextRoom++), "roster", { v: 1, remove: [user(1).pk] })).status).toBe(404);
  });

  it("can rotate the join secret in the same call so a removed member's copy of the secret is dead", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    const next = await sha256Hex(SECRET2);
    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk], secretHash: next })).status).toBe(200);
    expect((await joinAs(user(2).sk, ROOM, SECRET)).status).toBe(403);
    const ok = await joinSocket(user(2).sk, ROOM, SECRET2);
    ok.socket.close();
  });
});

describe("G5 capability rotation", () => {
  it("replaces the join secret hash: existing sockets stay, new joins need the new secret", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    const existing = await joinSocket(user(1).sk, ROOM, SECRET);
    const reply = await post(user(0).sk, ROOM, "rotate", { v: 1, secretHash: await sha256Hex(SECRET2) });
    expect(reply.status).toBe(200);
    expect(reply.body).toEqual({ v: 1, rotated: true });

    expect(await existing.socket.silent(100)).toBe(true);
    existing.socket.send({ type: "state", muted: true });
    expect(await existing.socket.silent(100)).toBe(true);

    const old = await joinAs(user(2).sk, ROOM, SECRET);
    expect(old.status).toBe(403);
    expect(((await old.json()) as any).error.code).toBe("forbidden");
    const fresh = await joinSocket(user(2).sk, ROOM, SECRET2);
    expect(fresh.welcome.participants).toHaveLength(1);
    const stored = JSON.stringify(await dump(ROOM));
    expect(stored).not.toContain(await sha256Hex(SECRET));
    expect(stored).not.toContain(SECRET2);
    existing.socket.close();
    fresh.socket.close();
  });

  it("is creator-only, validated, and also works on unrostered rooms", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    const hash = await sha256Hex(SECRET2);
    expect((await post(user(1).sk, ROOM, "rotate", { v: 1, secretHash: hash })).status).toBe(403);
    expect((await post(user(0).sk, ROOM, "rotate", { v: 1, secretHash: "short" })).status).toBe(400);
    expect((await post(user(0).sk, ROOM, "rotate", { v: 1 })).status).toBe(400);
    expect((await post(user(0).sk, roomId(nextRoom++), "rotate", { v: 1, secretHash: hash })).status).toBe(404);
    expect((await joinAs(user(1).sk, ROOM, SECRET)).status).toBe(101);

    expect((await createRoomAs(user(0).sk, ROOM2, SECRET)).status).toBe(200);
    expect((await post(user(0).sk, ROOM2, "rotate", { v: 1, secretHash: hash })).status).toBe(200);
    expect((await joinAs(user(1).sk, ROOM2, SECRET)).status).toBe(403);
  });

  it("refuses a rotation replay", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    const url = `${BASE}/v1/call/rooms/${ROOM}/rotate`;
    const body = JSON.stringify({ v: 1, secretHash: await sha256Hex(SECRET2) });
    const auth = await authHeader(user(0).sk, "POST", url, body);
    const make = () => worker(new Request(url, { method: "POST", headers: { Authorization: auth, "content-type": "application/json" }, body }));
    expect((await make()).status).toBe(200);
    expect((await make()).status).toBe(401);
  });
});

// ---- (4) malformed input never crashes the room -----------------------------------------------

describe("G5 malformed input never crashes the room", () => {
  it("survives garbage frames and keeps serving valid ones", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    const b = await joinSocket(user(1).sk, ROOM, SECRET);
    await a.socket.next();
    const frames: Array<string | ArrayBuffer> = [
      "", "null", "[]", "7", '"x"', "{", '{"type":5}', '{"type":"state"}', '{"type":"state","muted":"yes"}',
      '{"type":"state","muted":true,"deafened":1}', '{"type":"__proto__"}', '{"type":"heartbeat","extra":{"a":[1,2,3]}}',
      "{".repeat(5000), JSON.stringify({ type: "state", muted: true, pad: "x".repeat(60_000) }),
      new Uint8Array([0xff, 0xfe, 0x00, 0x01]).buffer,
    ];
    for (const frame of frames) b.socket.ws.send(frame as any);
    await new Promise((resolve) => setTimeout(resolve, 300));
    b.socket.send({ type: "state", muted: true, deafened: false });
    const seen: any[] = [];
    for (let i = 0; i < 40; i++) {
      if (await a.socket.silent(60)) break;
      seen.push(await a.socket.next());
    }
    expect(seen.some((f) => f.type === "state" && f.pid === b.welcome.you && f.muted === true)).toBe(true);
    expect(await count(ROOM, "participants")).toBe(2);
    const late = await joinSocket(user(0).sk, ROOM, SECRET);
    expect(late.welcome.type).toBe("welcome");
    a.socket.close();
    b.socket.close();
    late.socket.close();
  });

  it("answers malformed provider requests with a 4xx and leaves the directory unchanged", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    const calls = await fakeSfu(ROOM, SFU_OK);
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    expect((await reqAs(user(0).sk, "POST", `/v1/call/rooms/${ROOM}/sfu/sessions-new`, { v: 1 })).status).toBe(200);
    const before = JSON.stringify((await dump(ROOM)).participants);
    const path = (op: string) => `/v1/call/rooms/${ROOM}/sfu/${op}`;
    const cases: Array<[string, string, any]> = [
      ["tracks-new", "bad mid", { v: 1, sessionId: "sess-a", tracks: [{ location: "local", mid: "../x", trackName: "mic" }] }],
      ["tracks-new", "bad session", { v: 1, sessionId: "bad id!", tracks: [{ location: "local", mid: "0", trackName: "mic" }] }],
      ["tracks-new", "tracks not array", { v: 1, sessionId: "sess-a", tracks: "mic" }],
      ["tracks-new", "65 tracks", { v: 1, sessionId: "sess-a", tracks: Array.from({ length: 65 }, () => ({ location: "remote", sessionId: "s", trackName: "mic" })) }],
      ["tracks-new", "track null", { v: 1, sessionId: "sess-a", tracks: [null] }],
      ["tracks-new", "foreign remote", { v: 1, sessionId: "sess-a", tracks: [{ location: "remote", sessionId: "someone-else", trackName: "mic" }] }],
      ["tracks-close", "mid format", { v: 1, sessionId: "sess-a", mids: ["a/b"] }],
      ["tracks-close", "foreign session", { v: 1, sessionId: "not-mine", mids: ["0"] }],
      ["renegotiate", "answer type", { v: 1, answer: 5 }],
      ["renegotiate", "foreign session", { v: 1, sessionId: "not-mine", answer: "x" }],
      ["nope", "unknown op", { v: 1 }],
    ];
    for (const [op, label, body] of cases) {
      const reply = await reqAs(user(0).sk, "POST", path(op), body);
      expect(reply.status, `${op} ${label}`).toBeGreaterThanOrEqual(400);
      expect(reply.status, `${op} ${label}`).toBeLessThan(500);
    }
    expect(JSON.stringify((await dump(ROOM)).participants)).toBe(before);
    expect(calls.filter((c) => !c.url.endsWith("/sessions/new"))).toHaveLength(0);
    expect((await client(0).request("GET", `/v1/call/rooms/${ROOM}`)).status).toBe(200);
    a.socket.close();
  });

  it("answers malformed roster, rotate and create bodies with 400 and no state change", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    const before = JSON.stringify(await dump(ROOM));
    const slotsBefore = await slotCount();
    const url = (op: string) => `${BASE}/v1/call/rooms/${ROOM}/${op}`;
    for (const op of ["roster", "rotate"]) {
      for (const raw of ["", "null", "[]", "{", "7", '{"v":1}', '{"v":1,"remove":null,"secretHash":null}']) {
        const auth = await authHeader(user(0).sk, "POST", url(op), raw);
        const reply = await worker(new Request(url(op), { method: "POST", headers: { Authorization: auth, "content-type": "application/json" }, body: raw }));
        expect(reply.status, `${op} ${raw}`).toBe(400);
      }
    }
    const createUrl = `${BASE}/v1/call/rooms`;
    for (const raw of ["null", "[]", "{", '{"v":1,"room":5}', `{"v":1,"room":"${ROOM2}","secretHash":"${hex64("a")}","members":[1,2]}`]) {
      const auth = await authHeader(user(0).sk, "POST", createUrl, raw);
      const reply = await worker(new Request(createUrl, { method: "POST", headers: { Authorization: auth, "content-type": "application/json" }, body: raw }));
      expect(reply.status, raw).toBe(400);
    }
    // The room object is unchanged apart from the replay table.
    const after = await dump(ROOM);
    delete after.auth_seen;
    const prior = JSON.parse(before);
    delete prior.auth_seen;
    expect(JSON.stringify(after)).toBe(JSON.stringify(prior));
    expect(await slotCount()).toBe(slotsBefore);
  });
});

// ---- third-context review fixes (B1-B4 and cheap items) --------------------------------------

const setMailbox = (name: string, value: number): Promise<number> =>
  (runInDurableObject as any)(mailboxStub(), (instance: any) => {
    const old: number = instance.limits[name];
    instance.limits[name] = value;
    return old;
  });
const setRoom = (room: string, name: string, value: number): Promise<void> =>
  inCallRoom(room, (instance: CallRoom) => {
    (instance as any).limits[name] = value;
  });
const clearSlots = (): Promise<void> =>
  runInDurableObject(mailboxStub(), (_: unknown, state: DurableObjectState) => {
    state.storage.sql.exec("DELETE FROM call_slots");
  });
const ageSlot = (room: string, ms: number): Promise<void> =>
  runInDurableObject(mailboxStub(), (_: unknown, state: DurableObjectState) => {
    state.storage.sql.exec("UPDATE call_slots SET claimed_at = claimed_at - ? WHERE room = ?", ms, room);
  });
const ageRoom = (room: string, ms: number): Promise<void> =>
  inCallRoom(room, (_: CallRoom, state) => {
    state.storage.sql.exec("UPDATE room SET created_at = created_at - ?", ms);
  });
const sleep = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

describe("B1 superseding is limited to the old room's frozen roster and a sane revision", () => {
  it("refuses a supersede by an enrolled user who is not in the old roster, and the old room stays live", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { revision: 1, members: set(0, 1) })).status).toBe(200);
    const old = await joinSocket(user(1).sk, ROOM, SECRET);
    const outsider = await createBound(user(2).sk, ROOM2, SECRET2, { revision: 2, members: set(2) });
    expect(outsider.status).toBe(403);
    expect(outsider.body.error.code).toBe("not_in_roster");
    expect(await old.socket.silent(150)).toBe(true);
    expect((await client(1).request("GET", `/v1/call/rooms/${ROOM}`)).status).toBe(200);
    expect((await groupSlots()).map((s) => s.room)).toEqual([ROOM]);
    expect(await count(ROOM2, "room")).toBe(0);
    old.socket.close();
  });

  it("still lets a non-creator member of the old roster supersede", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { revision: 1, members: set(0, 1) })).status).toBe(200);
    expect((await createBound(user(1).sk, ROOM2, SECRET2, { revision: 2, members: set(1, 2) })).status).toBe(200);
    expect((await groupSlots()).map((s) => s.room)).toEqual([ROOM2]);
  });

  it("rejects revisions above 2^31-1 (a 2^53-1 revision cannot end a call or wedge a group)", async () => {
    for (const revision of [2 ** 31, Number.MAX_SAFE_INTEGER]) {
      const reply = await createBound(user(0).sk, ROOM, SECRET, { revision });
      expect(reply.status, String(revision)).toBe(400);
    }
    expect(await groupSlots()).toHaveLength(0);
    expect((await createBound(user(0).sk, ROOM, SECRET, { revision: 2 ** 31 - 1 })).status).toBe(200);
  });
});

describe("B2 per-identity call slot cap", () => {
  it("one identity cannot fill the global cap: another identity can still create", async () => {
    await clearSlots();
    const oldGlobal = await setMailbox("maxCallSlots", 5);
    try {
      const rooms: string[] = [];
      for (let i = 0; i < 4; i++) {
        const room = roomId(nextRoom++);
        rooms.push(room);
        expect((await createBound(user(3).sk, room, joinSecret(0x70 + i), { group: uuid(nextGroup++), members: set(3, 4) })).status).toBe(200);
        (await joinSocket(user(3).sk, room, joinSecret(0x70 + i))).socket; // joined: not evictable
      }
      const fifth = await createBound(user(3).sk, roomId(nextRoom++), joinSecret(0x7f), { group: uuid(nextGroup++), members: set(3, 4) });
      expect(fifth.status).toBe(429);
      const other = await createBound(user(4).sk, roomId(nextRoom++), joinSecret(0x7e), { group: uuid(nextGroup++), members: set(3, 4) });
      expect(other.status).toBe(200);
      expect((await slots()).filter((s) => s.creator === user(3).pk)).toHaveLength(4);
    } finally {
      await setMailbox("maxCallSlots", oldGlobal);
    }
  });

  it("evicts the creator's own oldest UNJOINED slot first, never a joined one", async () => {
    await clearSlots();
    const rooms = [0, 1, 2, 3].map(() => roomId(nextRoom++));
    for (const [i, room] of rooms.entries()) {
      expect((await createBound(user(3).sk, room, joinSecret(0x80 + i), { group: uuid(nextGroup++), members: set(3, 4) })).status).toBe(200);
      await ageSlot(room, 1000 * (10 - i)); // rooms[0] is the oldest
    }
    const joined = await joinSocket(user(3).sk, rooms[0], joinSecret(0x80)); // oldest is joined: skipped
    const fifth = roomId(nextRoom++);
    expect((await createBound(user(3).sk, fifth, joinSecret(0x8f), { group: uuid(nextGroup++), members: set(3, 4) })).status).toBe(200);
    const mine = (await slots()).filter((s) => s.creator === user(3).pk).map((s) => s.room).sort();
    expect(mine).toEqual([rooms[0], rooms[2], rooms[3], fifth].sort()); // rooms[1] (oldest unjoined) evicted
    expect(await count(rooms[1], "room")).toBe(0);
    joined.socket.close();
  });
});

describe("B3 room liveness and slot takeover", () => {
  it("a room whose participants dropped inside the reconnect grace is still live after the unjoined TTL", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    await setRoom(ROOM, "callReconnectGraceMs", 600_000);
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    a.socket.close();
    await sleep(300);
    expect(await count(ROOM, "participants")).toBe(1);
    await ageRoom(ROOM, 13 * 60 * 1000);
    await ageSlot(ROOM, 60_000);
    const second = await createBound(user(1).sk, ROOM2, SECRET2, {});
    expect(second.status).toBe(409);
    expect(second.body.error.code).toBe("group_room_exists");
    expect(second.body.room).toBe(ROOM);
    expect((await groupSlots()).map((s) => s.room)).toEqual([ROOM]);
  });

  it("roomLive is true while a participant is inside the grace window and false once everything is stale", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    await setRoom(ROOM, "callReconnectGraceMs", 600_000);
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    a.socket.close();
    await sleep(300);
    await ageRoom(ROOM, 13 * 60 * 1000);
    expect(await inCallRoom(ROOM, (i: CallRoom) => i.roomLive())).toBe(true);
    await inCallRoom(ROOM, (_: CallRoom, state) => {
      state.storage.sql.exec("UPDATE participants SET last_seen = last_seen - 700000");
    });
    expect(await inCallRoom(ROOM, (i: CallRoom) => i.roomLive())).toBe(false);
  });

  it("a participant reconnecting after the unjoined TTL but inside the grace is not torn down", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    await setRoom(ROOM, "callReconnectGraceMs", 600_000);
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    a.socket.close();
    await sleep(300);
    await ageRoom(ROOM, 13 * 60 * 1000);
    const back = await joinAs(user(0).sk, ROOM, SECRET, undefined, a.welcome.you);
    expect(back.status).toBe(101);
    new TestSocket(back.webSocket!).close();
  });

  it("a concurrent loser calling roomLive in the claim-to-insert gap does not take the slot over", async () => {
    // The winner has claimed the slot but has not inserted its room row yet: roomLive() reads "no room".
    const key = await sha256Hex(`relay-call-group-v1\n${GROUP}`);
    const claim = await (runInDurableObject as any)(mailboxStub(), (instance: any) => instance.callSlotClaim(key, 1, ROOM, user(0).pk));
    expect(claim.ok).toBe(true);
    expect(await inCallRoom(ROOM, (i: CallRoom) => i.roomLive())).toBe(false); // the gap
    const loser = await createBound(user(1).sk, ROOM2, SECRET2, {});
    expect(loser.status).not.toBe(200);
    expect((await groupSlots()).map((s) => s.room)).toEqual([ROOM]);
    expect(await count(ROOM2, "room")).toBe(0);
  });
});

// A fake provider with distinct sessions and per-call hooks.
function sfuFor(opts: { closeHook?: (url: string, body: any) => Promise<Response | void> } = {}) {
  let session = 0;
  const json = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status, headers: { "content-type": "application/json" } });
  return async (call: { url: string; body: string }) => {
    const body = call.body ? JSON.parse(call.body) : {};
    if (call.url.endsWith("/sessions/new")) return json({ sessionId: `sess-${++session}` });
    if (call.url.endsWith("/tracks/new")) {
      const remote = (body.tracks ?? []).some((t: any) => t.location === "remote");
      return json({ tracks: [{ mid: remote ? "7" : "0", trackName: "mic" }] });
    }
    if (call.url.endsWith("/tracks/close")) {
      const hooked = await opts.closeHook?.(call.url, body);
      if (hooked) return hooked;
      return json({ tracks: (body.tracks ?? []).map((t: any) => ({ mid: t.mid })) });
    }
    return json({});
  };
}
const sfuReq = (sk: Uint8Array, room: string, op: string, body: unknown) => reqAs(sk, "POST", `/v1/call/rooms/${room}/sfu/${op}`, body);

describe("B4 removal closes the removed participant's pulled provider tracks", () => {
  it("closes the pulled mid on the removed session, not only its published one", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    const calls = await fakeSfu(ROOM, sfuFor());
    const pub = await joinSocket(user(2).sk, ROOM, SECRET);
    const sub = await joinSocket(user(1).sk, ROOM, SECRET);
    expect((await sfuReq(user(2).sk, ROOM, "sessions-new", { v: 1 })).body.sessionId).toBe("sess-1");
    expect((await sfuReq(user(2).sk, ROOM, "tracks-new", { v: 1, tracks: [{ location: "local", mid: "0", trackName: "mic" }] })).status).toBe(200);
    expect((await sfuReq(user(1).sk, ROOM, "sessions-new", { v: 1 })).body.sessionId).toBe("sess-2");
    expect((await sfuReq(user(1).sk, ROOM, "tracks-new", { v: 1, tracks: [{ location: "remote", sessionId: "sess-1", trackName: "mic" }] })).status).toBe(200);

    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] })).status).toBe(200);
    const closes = calls.filter((c) => c.url.endsWith("/sessions/sess-2/tracks/close")).map((c) => JSON.parse(c.body));
    expect(closes.some((b) => b.tracks.some((t: any) => t.mid === "7"))).toBe(true);
    expect(calls.filter((c) => c.url.endsWith("/sessions/sess-1/tracks/close"))).toHaveLength(0); // the publisher is untouched
    expect(await count(ROOM, "pending_track_close")).toBe(0);
    pub.socket.close();
    sub.socket.close();
  });

  it("keeps the pulled-track close durable: a failed close is retried by the alarm", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    let fail = true;
    const calls = await fakeSfu(ROOM, sfuFor({ closeHook: async () => (fail ? new Response("{}", { status: 500 }) : undefined) }));
    await joinSocket(user(2).sk, ROOM, SECRET);
    await joinSocket(user(1).sk, ROOM, SECRET);
    await sfuReq(user(2).sk, ROOM, "sessions-new", { v: 1 });
    await sfuReq(user(2).sk, ROOM, "tracks-new", { v: 1, tracks: [{ location: "local", mid: "0", trackName: "mic" }] });
    await sfuReq(user(1).sk, ROOM, "sessions-new", { v: 1 });
    await sfuReq(user(1).sk, ROOM, "tracks-new", { v: 1, tracks: [{ location: "remote", sessionId: "sess-1", trackName: "mic" }] });
    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] })).status).toBe(200);
    expect(await count(ROOM, "pending_track_close")).toBeGreaterThan(0);
    fail = false;
    const before = calls.length;
    await inCallRoom(ROOM, (i: CallRoom) => (i as any).alarm());
    expect(calls.slice(before).some((c) => c.url.endsWith("/sessions/sess-2/tracks/close") && c.body.includes('"7"'))).toBe(true);
    expect(await count(ROOM, "pending_track_close")).toBe(0);
  });
});

describe("removal cheap fixes", () => {
  it("is idempotent: removing an already-removed identity is a no-op success", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] })).body).toEqual({ v: 1, removed: 1 });
    const again = await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] });
    expect(again.status).toBe(200);
    expect(again.body).toEqual({ v: 1, removed: 0 });
    // A never-member is still refused.
    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(5).pk] })).status).toBe(400);
  });

  it("closes every removed identity's sockets before awaiting any provider cleanup", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    await fakeSfu(ROOM, sfuFor({ closeHook: async () => { await gate; } }));
    const sockets = [];
    for (const u of [1, 2]) {
      sockets.push((await joinSocket(user(u).sk, ROOM, SECRET)).socket);
      await sfuReq(user(u).sk, ROOM, "sessions-new", { v: 1 });
      await sfuReq(user(u).sk, ROOM, "tracks-new", { v: 1, tracks: [{ location: "local", mid: "0", trackName: "mic" }] });
    }
    const removal = post(user(0).sk, ROOM, "roster", { v: 1, remove: set(1, 2) });
    const timeout = Symbol("timeout");
    const results = await Promise.all(sockets.map((s) => Promise.race([s.closed, sleep(700).then(() => timeout)])));
    release();
    expect((await removal).status).toBe(200);
    for (const r of results) expect(r, "socket closed while provider cleanup was still pending").not.toBe(timeout);
  });
});

describe("teardown and slot upkeep", () => {
  it("a teardown deferred by pending provider closes still deletes roster rows so a same-id create cannot 500", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1) })).status).toBe(200);
    await fakeSfu(ROOM, sfuFor({ closeHook: async () => new Response("{}", { status: 500 }) }));
    await joinSocket(user(0).sk, ROOM, SECRET);
    // A provider close that failed earlier and is still owed (durable retry row).
    await inCallRoom(ROOM, (_: CallRoom, state) => {
      state.storage.sql.exec("INSERT INTO pending_track_close (session_id, mid, pid) VALUES ('sess-9', '0', 'ffffffffffffffff')");
    });
    expect((await post(user(0).sk, ROOM, "end", undefined)).status).toBe(200);
    expect(await count(ROOM, "pending_track_close")).toBeGreaterThan(0); // deferred branch
    expect(await count(ROOM, "room_roster")).toBe(0);
    expect(await count(ROOM, "roster_members")).toBe(0);
    const again = await createBound(user(1).sk, ROOM, SECRET2, { group: uuid(nextGroup++), members: set(0, 1) });
    expect(again.status).toBe(200);
  });

  it("the alarm refreshes the slot of a live call so it outlives the 24 h TTL", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, {})).status).toBe(200);
    const a = await joinSocket(user(0).sk, ROOM, SECRET);
    await ageSlot(ROOM, 23 * 3600 * 1000);
    const old = (await groupSlots())[0].claimed_at;
    await inCallRoom(ROOM, (i: CallRoom) => (i as any).alarm());
    const fresh = (await groupSlots())[0].claimed_at;
    expect(fresh - old).toBeGreaterThan(22 * 3600 * 1000);
    // and a rival create after the original TTL would have passed still conflicts
    await ageSlot(ROOM, 2 * 3600 * 1000 - 1000);
    expect((await createBound(user(1).sk, ROOM2, SECRET2, {})).status).toBe(409);
    a.socket.close();
  });
});

describe("roster membership cache", () => {
  it("does not hit roster tables on every frame, and a removal invalidates it immediately", async () => {
    expect((await createBound(user(0).sk, ROOM, SECRET, { members: set(0, 1, 2) })).status).toBe(200);
    await fakeSfu(ROOM, sfuFor());
    const a = await joinSocket(user(1).sk, ROOM, SECRET);
    await sfuReq(user(1).sk, ROOM, "sessions-new", { v: 1 }); // warms the cache
    const log: string[] = [];
    await inCallRoom(ROOM, (instance: CallRoom) => {
      const real = (instance as any).sql;
      (instance as any).realSql = real;
      (instance as any).sql = { exec: (q: string, ...p: any[]) => { log.push(q); return real.exec(q, ...p); } };
    });
    for (let i = 0; i < 3; i++) expect((await sfuReq(user(1).sk, ROOM, "sessions-new", { v: 1 })).status).toBe(200);
    a.socket.send({ type: "state", muted: true });
    await sleep(100);
    expect(log.filter((q) => q.includes("roster"))).toHaveLength(0);
    expect((await post(user(0).sk, ROOM, "roster", { v: 1, remove: [user(1).pk] })).status).toBe(200);
    expect((await sfuReq(user(1).sk, ROOM, "sessions-new", { v: 1 })).status).toBe(403);
    await inCallRoom(ROOM, (instance: CallRoom) => { (instance as any).sql = (instance as any).realSql; });
  });
});

// ---- (G5b) the live revision advance: removal revokes, the call continues for everyone else -----------

function hexBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

interface AdvanceOptions {
  group?: string;
  revision: number;
  members: string[];
  owner?: string;
  ownerSk?: Uint8Array;
  room: string;
  secretHash: string;
  issuedAt?: number;
  expiresAt?: number;
}

/** A POST /roster advance body with a real BIP-340 owner signature over exactly this room/revision/member set. */
async function advanceBody(o: AdvanceOptions): Promise<Record<string, unknown>> {
  const now = Math.floor(Date.now() / 1000);
  const group = o.group ?? GROUP;
  const owner = o.owner ?? user(0).pk;
  const members = [...o.members].sort();
  const issuedAt = o.issuedAt ?? now;
  const expiresAt = o.expiresAt ?? now + 600;
  const rosterHash = await rosterHashOf(group, owner, o.revision, members);
  const canonical = attestationCanonical({ group, revision: o.revision, rosterHash, members, room: o.room, owner, issuedAt, expiresAt, signature: "" });
  const sig = schnorr.sign(hexBytes(await sha256Hex(canonical)), o.ownerSk ?? user(0).sk);
  const rosterSig = btoa(String.fromCharCode(...sig));
  return { v: 1, room: o.room, group, revision: o.revision, rosterHash, members, owner, rosterSig, issuedAt, expiresAt, secretHash: o.secretHash };
}

/** A verified (owner-signed) create, so the room starts bound to a proven roster. */
async function createSigned(sk: Uint8Array, room: string, secret: string, revision: number, members: string[], ownerSk: Uint8Array = user(0).sk) {
  const body = await advanceBody({ revision, members, room, secretHash: await sha256Hex(secret), ownerSk, owner: user(0).pk });
  return reqAs(sk, "POST", "/v1/call/rooms", body);
}

describe("G5b live roster advance keeps the call alive", () => {
  it("re-binds the room at the new revision, rotates the secret, closes the removed socket with 4009 and refuses its rejoin", async () => {
    expect((await createSigned(user(0).sk, ROOM, SECRET, 1, set(0, 1, 2))).status).toBe(200);
    const stay = await joinSocket(user(2).sk, ROOM, SECRET);
    const gone = await joinSocket(user(1).sk, ROOM, SECRET);
    await stay.socket.next(); // user(1) joined

    const body = await advanceBody({ revision: 2, members: set(0, 2), room: ROOM, secretHash: await sha256Hex(SECRET2) });
    const reply = await post(user(0).sk, ROOM, "roster", body);
    expect(reply.status).toBe(200);
    expect(reply.body).toMatchObject({ v: 1, revision: 2, removed: 1 });
    expect(reply.body.rosterHash).toBe(await rosterHashOf(GROUP, user(0).pk, 2, set(0, 2)));

    expect((await gone.socket.closed).code).toBe(4009);
    const all = await dump(ROOM);
    expect(all.roster_members.map((r) => r.pubkey).sort()).toEqual(set(0, 2));
    expect(all.room_roster[0].revision).toBe(2);
    expect(all.room_roster[0].roster_hash).toBe(await rosterHashOf(GROUP, user(0).pk, 2, set(0, 2)));

    // The removed identity cannot rejoin even with the new secret; the survivor still gets the call.
    const rejoin = await joinAs(user(1).sk, ROOM, SECRET2);
    expect(rejoin.status).toBe(403);
    expect(((await rejoin.json()) as any).error.code).toBe("not_in_roster");
    const stale = await joinAs(user(2).sk, ROOM, SECRET);
    expect(stale.status).toBe(403);
    // The survivor sees the removed peer leave (the call goes on) and its socket is never closed by the advance.
    expect(await stay.socket.next()).toMatchObject({ type: "left", pubkey: user(1).pk });
    stay.socket.send({ type: "state", muted: true });
    expect(await stay.socket.silent(100)).toBe(true);
    stay.socket.close();
    const fresh = await joinSocket(user(2).sk, ROOM, SECRET2);
    fresh.socket.close();
  });

  it("refuses a stale revision, an unsigned attempt and an ex-member's attempt", async () => {
    expect((await createSigned(user(0).sk, ROOM, SECRET, 3, set(0, 1, 2))).status).toBe(200);

    const stale = await advanceBody({ revision: 2, members: set(0, 1), room: ROOM, secretHash: await sha256Hex(SECRET2) });
    expect((await post(user(0).sk, ROOM, "roster", stale)).body.error.code).toBe("stale_revision");
    // same revision is not an advance either
    const same = await advanceBody({ revision: 3, members: set(0, 1), room: ROOM, secretHash: await sha256Hex(SECRET2) });
    expect((await post(user(0).sk, ROOM, "roster", same)).body.error.code).toBe("stale_revision");

    // No attestation on a verified room: signed_roster_required, nothing changes.
    const unsigned = { v: 1, group: GROUP, revision: 4, rosterHash: await rosterHashOf(GROUP, user(0).pk, 4, set(0, 1)), members: set(0, 1), secretHash: await sha256Hex(SECRET2) };
    const refused = await post(user(0).sk, ROOM, "roster", unsigned);
    expect(refused.status).toBe(409);
    expect(refused.body.error.code).toBe("signed_roster_required");

    // user(2) is removed in the new set: even relaying the owner's signature is not_in_roster.
    const byExMember = await advanceBody({ revision: 4, members: set(0, 1), room: ROOM, secretHash: await sha256Hex(SECRET2) });
    const ex = await post(user(2).sk, ROOM, "roster", byExMember);
    expect(ex.status).toBe(403);
    expect(ex.body.error.code).toBe("not_in_roster");

    const after = await dump(ROOM);
    expect(after.room_roster[0].revision).toBe(3);
    expect(after.roster_members.map((r) => r.pubkey).sort()).toEqual(set(0, 1, 2));
  });

  it("lets a member who stays relay the owner's signed advance", async () => {
    expect((await createSigned(user(0).sk, ROOM, SECRET, 1, set(0, 1, 2))).status).toBe(200);
    const body = await advanceBody({ revision: 2, members: set(0, 1), room: ROOM, secretHash: await sha256Hex(SECRET2) });
    const reply = await post(user(1).sk, ROOM, "roster", body);
    expect(reply.status).toBe(200);
    expect(reply.body).toMatchObject({ v: 1, revision: 2, removed: 1 });
  });
});
