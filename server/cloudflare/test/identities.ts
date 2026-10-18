import { sha256 } from "@noble/hashes/sha2.js";
import { getPublicKey } from "nostr-tools/pure";

/**
 * Deterministic test identities, so vitest.config.ts (Node) can allowlist exactly the keys the tests
 * (inside workerd) sign with. Users 0..ALLOWED-1 are on the allowlist; OUTSIDER is a valid key that is not.
 */
export const ALLOWED = 11;
export const OUTSIDER = ALLOWED;

export function testSecret(i: number): Uint8Array {
  return sha256(new TextEncoder().encode(`relay-mailbox-test-identity:${i}`));
}

export function testPubkeys(count: number): string[] {
  return Array.from({ length: count }, (_, i) => getPublicKey(testSecret(i)));
}
