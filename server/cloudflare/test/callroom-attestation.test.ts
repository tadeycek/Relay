import { runInDurableObject } from "cloudflare:test";
import { schnorr } from "@noble/curves/secp256k1.js";
import { beforeEach, describe, expect, it } from "vitest";
import type { CallRoom } from "../src/callroom";
import { attestationCanonical, rosterHashOf } from "../src/callroom";
import { sha256Hex } from "../src/protocol";
import { user } from "./helpers";
import { inCallRoom, joinSecret, joinSocket, reqAs, roomId } from "./callroom-helpers";

/**
 * G5: the owner-signed roster attestation on POST /v1/call/rooms. Every test uses the real Worker, the real
 * Mailbox object and real BIP-340 signatures; no provider and no network are involved.
 */

let nextRoom = 9000;
let nextGroup = 9500;
let GROUP = "";
let OWNER_SK: Uint8Array;
let OWNER: string;

beforeEach(() => {
  GROUP = `00000000-0000-4000-9000-${(nextGroup++).toString(16).padStart(12, "0")}`;
  OWNER_SK = user(0).sk;
  OWNER = user(0).pk;
});

const roomIdFor = () => roomId(nextRoom++);
const set = (...users: number[]): string[] => [...new Set(users.map((i) => user(i).pk))].sort();
const b64 = (bytes: Uint8Array): string => btoa(String.fromCharCode(...bytes));

interface AttestOptions {
  group?: string;
  revision?: number;
  members?: string[];
  room: string;
  owner?: string;
  ownerSk?: Uint8Array;
  issuedAt?: number;
  expiresAt?: number;
  rosterHash?: string;
  /** Sign over a different room than the body names (a deliberate mismatch). */
  signRoom?: string;
}

/** Signs the canonical attestation exactly as a client would, over the request's own room/group/revision. */
async function attest(o: AttestOptions): Promise<{ rosterHash: string; rosterSig: string; owner: string; issuedAt: number; expiresAt: number }> {
  const now = Math.floor(Date.now() / 1000);
  const group = o.group ?? GROUP;
  const revision = o.revision ?? 1;
  const members = o.members ?? set(0, 1);
  const owner = o.owner ?? OWNER;
  const issuedAt = o.issuedAt ?? now;
  const expiresAt = o.expiresAt ?? now + 600;
  const rosterHash = o.rosterHash ?? await rosterHashOf(group, owner, revision, members);
  const canonical = attestationCanonical({ group, revision, rosterHash, members, room: o.room, owner, issuedAt, expiresAt, signature: "" });
  const digest = await sha256Hex(canonical);
  const sig = schnorr.sign(hexBytes(digest), o.ownerSk ?? OWNER_SK);
  return { rosterHash, rosterSig: b64(sig), owner, issuedAt, expiresAt };
}

function hexBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/** A signed create body. [patch] may deliberately break the attestation against the body. */
async function signedCreate(
  signer: Uint8Array,
  room: string,
  secret: string,
  opts: AttestOptions & { patch?: (body: Record<string, any>) => void } = { room },
): Promise<{ status: number; body: any; headers: Headers }> {
  const members = opts.members ?? set(0, 1);
  const a = await attest({ ...opts, room: opts.signRoom ?? room, members });
  const body: Record<string, any> = {
    v: 1,
    room,
    secretHash: await sha256Hex(secret),
    group: opts.group ?? GROUP,
    revision: opts.revision ?? 1,
    rosterHash: opts.rosterHash ?? a.rosterHash,
    members,
    owner: a.owner,
    rosterSig: a.rosterSig,
    issuedAt: a.issuedAt,
    expiresAt: a.expiresAt,
  };
  opts.patch?.(body);
  return reqAs(signer, "POST", "/v1/call/rooms", body);
}

const verifiedFlag = (room: string): Promise<boolean> =>
  inCallRoom(room, (_: CallRoom, state) => state.storage.sql.exec<{ verified: number }>("SELECT verified FROM room_roster LIMIT 1").toArray()[0]?.verified === 1);

const roomExists = (room: string): Promise<boolean> =>
  inCallRoom(room, (_: CallRoom, state) => state.storage.sql.exec<{ n: number }>("SELECT COUNT(*) AS n FROM room").toArray()[0].n === 1);

describe("signed roster attestation", () => {
  it("pins the cross-language canonical bytes and roster hash", async () => {
    // Same constants as core's RosterAttestationTest, produced independently with @noble/curves.
    const group = "00000000-0000-4000-8000-000000000001";
    const owner = "66ba53f7e83dd43e33cb84f1d72f7eff553bb49824f1325c8326985dc92d6b38";
    const members = ["11".repeat(32), "22".repeat(32), "33".repeat(32), owner];
    const room = "aa".repeat(16);
    expect(await rosterHashOf(group, owner, 7, members)).toBe("89672e0fd4f86b7aa2fa615401b65860c0008ad29775d009a6788534597ca85d");
    const canonical = attestationCanonical({ group, revision: 7, rosterHash: "89672e0fd4f86b7aa2fa615401b65860c0008ad29775d009a6788534597ca85d", members, room, owner, issuedAt: 1_700_000_000, expiresAt: 1_700_000_900, signature: "" });
    expect(canonical).toBe("relay-call-roster-attestation-v1\n" +
      "v=1\n" +
      `groupId=${group}\n` +
      "revision=7\n" +
      "rosterHash=89672e0fd4f86b7aa2fa615401b65860c0008ad29775d009a6788534597ca85d\n" +
      `members=${members.join(",")}\n` +
      `room=${room}\n` +
      "issuedAt=1700000000\n" +
      "expiresAt=1700000900");
    const sig = "u8olEm/1d05p0KCQJKNKcpu0zeS1VTcnRo6hWTYVziOM3M54YSBMQwJYQd4LGBwbVfVzRGdKzk42AHge66byAw==";
    expect(schnorr.verify(atobBytes(sig), hexBytes(await sha256Hex(canonical)), hexBytes(owner))).toBe(true);
  });

  it("accepts a signed roster, marks it verified and stores no signature", async () => {
    const room = roomIdFor();
    const reply = await signedCreate(user(1).sk, room, joinSecret(0x61));
    expect(reply.status).toBe(200);
    expect(reply.body).toEqual({ v: 1, created: true, rostered: true, verified: true });
    expect(reply.headers.get("x-relay-call-rostered")).toBe("1");
    expect(await verifiedFlag(room)).toBe(true);
    const row = await inCallRoom(room, (_: CallRoom, state) => state.storage.sql.exec("SELECT * FROM room_roster").toArray()[0]);
    expect(Object.keys(row).sort()).toEqual(["group_key", "id", "revision", "roster_hash", "verified"]);
  });

  it("refuses a signature that does not cover the room, revision, expiry or member set in the body", async () => {
    const room = roomIdFor();
    const other = roomIdFor();
    // The signature is over [other], the body names [room].
    expect((await signedCreate(user(1).sk, room, joinSecret(0x62), { room, signRoom: other })).status).toBe(400);
    // The signature is over revision 1, the body claims 2.
    const rev = await signedCreate(user(1).sk, room, joinSecret(0x63), { room, revision: 1, patch: (b) => (b.revision = 2) });
    expect(rev.status).toBe(400);
    expect(rev.body.error.code).toBe("roster_unverified");
    // The body moves expiresAt after signing.
    const exp = await signedCreate(user(1).sk, room, joinSecret(0x64), { room, patch: (b) => (b.expiresAt = b.expiresAt + 1) });
    expect(exp.status).toBe(400);
    // The body adds a member the signature never covered.
    const add = await signedCreate(user(1).sk, room, joinSecret(0x65), { room, patch: (b) => (b.members = [...b.members, user(4).pk].sort()) });
    expect(add.status).toBe(400);
    expect(add.body.error.code).toBe("roster_unverified");
    expect(await roomExists(room)).toBe(false);
  });

  it("refuses stale, future and over-long attestations", async () => {
    const now = Math.floor(Date.now() / 1000);
    const room = roomIdFor();
    expect((await signedCreate(user(1).sk, room, joinSecret(0x66), { room, issuedAt: now - 3600, expiresAt: now - 1800 })).status).toBe(400);
    expect((await signedCreate(user(1).sk, room, joinSecret(0x67), { room, issuedAt: now + 3600, expiresAt: now + 4200 })).status).toBe(400);
    // Lifetime beyond the protocol maximum is refused even when everything else matches.
    const long = await signedCreate(user(1).sk, room, joinSecret(0x68), { room, issuedAt: now, expiresAt: now + 901 });
    expect(long.status).toBe(400);
  });

  it("refuses a rosterHash that does not hash the body's own roster", async () => {
    const room = roomIdFor();
    const members = set(0, 1);
    const bogus = "12".repeat(32);
    const a = await attest({ room, members, rosterHash: bogus });
    const reply = await reqAs(user(1).sk, "POST", "/v1/call/rooms", {
      v: 1, room, secretHash: await sha256Hex(joinSecret(0x69)), group: GROUP, revision: 1, rosterHash: bogus, members,
      owner: a.owner, rosterSig: a.rosterSig, issuedAt: a.issuedAt, expiresAt: a.expiresAt,
    });
    expect(reply.status).toBe(400);
    expect(reply.body.error.code).toBe("roster_unverified");
  });

  it("refuses malformed signatures and partial attestation fields", async () => {
    const room = roomIdFor();
    const bad = await signedCreate(user(1).sk, room, joinSecret(0x6a), { room, patch: (b) => (b.rosterSig = b.rosterSig.slice(0, 87) + "A=") });
    expect(bad.status).toBe(400);
    const partial = await signedCreate(user(1).sk, room, joinSecret(0x6b), { room, patch: (b) => delete b.rosterSig });
    expect(partial.status).toBe(400);
    expect(partial.body.error.code).toBe("bad_request");
    const noOwner = await signedCreate(user(1).sk, room, joinSecret(0x6c), { room, patch: (b) => (b.owner = "zz") });
    expect(noOwner.status).toBe(400);
  });

  it("an enrolled member cannot pin a group with their own key, nor supersede the signed room", async () => {
    const room = roomIdFor();
    expect((await signedCreate(user(1).sk, room, joinSecret(0x6d))).status).toBe(200);
    const room2 = roomIdFor();
    // user(2) signs revision 2 with their own key and a roster listing themselves as owner.
    const impostor = await signedCreate(user(2).sk, room2, joinSecret(0x6e), {
      room: room2, revision: 2, members: set(2, 3), owner: user(2).pk, ownerSk: user(2).sk,
    });
    expect(impostor.status).toBe(403);
    expect(impostor.body.error.code).toBe("not_owner");
    expect(await roomExists(room2)).toBe(false);
    expect(await roomExists(room)).toBe(true);
  });

  it("the pinned owner supersedes a live revision with a signed higher revision", async () => {
    const room = roomIdFor();
    const first = await signedCreate(user(1).sk, room, joinSecret(0x6f));
    expect(first.status).toBe(200);
    const { socket } = await joinSocket(user(1).sk, room, joinSecret(0x6f));
    const room2 = roomIdFor();
    const second = await signedCreate(user(2).sk, room2, joinSecret(0x70), { room: room2, revision: 2, members: set(0, 1, 2) });
    expect(second.status).toBe(200);
    const frame = await socket.next();
    expect(frame.type).toBe("end");
    expect(frame.reason).toBe("superseded");
    expect(await roomExists(room)).toBe(false);
    expect(await roomExists(room2)).toBe(true);
  });

  it("a legacy unsigned create still works but cannot supersede a signed room", async () => {
    const room = roomIdFor();
    expect((await signedCreate(user(1).sk, room, joinSecret(0x71))).status).toBe(200);
    const legacyRoom = roomIdFor();
    const legacy = await reqAs(user(1).sk, "POST", "/v1/call/rooms", {
      v: 1, room: legacyRoom, secretHash: await sha256Hex(joinSecret(0x72)), group: GROUP, revision: 2, rosterHash: await rosterHashOf(GROUP, OWNER, 1, set(0, 1)), members: set(0, 1),
    });
    expect(legacy.status).toBe(409);
    expect(legacy.body.error.code).toBe("signed_roster_required");
    expect(await roomExists(room)).toBe(true);
    expect(await roomExists(legacyRoom)).toBe(false);
  });

  it("a legacy unsigned create in a group with no signed room is accepted, unverified", async () => {
    const room = roomIdFor();
    const members = set(0, 1);
    const reply = await reqAs(user(1).sk, "POST", "/v1/call/rooms", {
      v: 1, room, secretHash: await sha256Hex(joinSecret(0x73)), group: GROUP, revision: 1, rosterHash: await rosterHashOf(GROUP, OWNER, 1, members), members,
    });
    expect(reply.status).toBe(200);
    expect(reply.body).toEqual({ v: 1, created: true, rostered: true, verified: false });
    expect(await verifiedFlag(room)).toBe(false);
    const live = await reqAs(user(1).sk, "GET", `/v1/call/rooms/${room}`);
    expect(live.body.verified).toBe(false);
  });

  it("reports verified on the live-state read and keeps retries idempotent only for the signed body", async () => {
    const room = roomIdFor();
    expect((await signedCreate(user(1).sk, room, joinSecret(0x74))).status).toBe(200);
    const retry = await signedCreate(user(1).sk, room, joinSecret(0x74));
    expect(retry.status).toBe(200);
    expect(retry.body.verified).toBe(true);
    const downgrade = await reqAs(user(1).sk, "POST", "/v1/call/rooms", {
      v: 1, room, secretHash: await sha256Hex(joinSecret(0x74)), group: GROUP, revision: 1, rosterHash: await rosterHashOf(GROUP, OWNER, 1, set(0, 1)), members: set(0, 1),
    });
    expect(downgrade.status).toBe(409);
    const live = await reqAs(user(1).sk, "GET", `/v1/call/rooms/${room}`);
    expect(live.body.verified).toBe(true);
  });

  it("pins the owner per group, so another group keeps its own owner", async () => {
    const roomA = roomIdFor();
    expect((await signedCreate(user(1).sk, roomA, joinSecret(0x75))).status).toBe(200);
    const groupB = `00000000-0000-4000-9000-${(nextGroup++).toString(16).padStart(12, "0")}`;
    const roomB = roomIdFor();
    const other = await signedCreate(user(2).sk, roomB, joinSecret(0x76), {
      room: roomB, group: groupB, members: set(2, 3), owner: user(2).pk, ownerSk: user(2).sk,
      rosterHash: await rosterHashOf(groupB, user(2).pk, 1, set(2, 3)),
    });
    expect(other.status).toBe(200);
    expect(await verifiedFlag(roomB)).toBe(true);
  });

  it("refuses a legacy create at the same revision as a signed room without touching it", async () => {
    const room = roomIdFor();
    expect((await signedCreate(user(1).sk, room, joinSecret(0x77))).status).toBe(200);
    const rival = roomIdFor();
    const conflict = await reqAs(user(1).sk, "POST", "/v1/call/rooms", {
      v: 1, room: rival, secretHash: await sha256Hex(joinSecret(0x78)), group: GROUP, revision: 1, rosterHash: await rosterHashOf(GROUP, OWNER, 1, set(0, 1)), members: set(0, 1),
    });
    expect(conflict.status).toBe(409);
    expect(conflict.body.error.code).toBe("group_room_exists");
    expect(await roomExists(room)).toBe(true);
    expect(await roomExists(rival)).toBe(false);
  });
});

function atobBytes(value: string): Uint8Array {
  return Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
}
