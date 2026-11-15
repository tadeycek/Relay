import { _SHA256 } from "@noble/hashes/sha2.js";

/**
 * A durable SHA-256 checkpoint, never supplied by a client. Updating one bounded part per request
 * avoids re-reading a multi-GB file at completion. Noble is pinned: its protected checkpoint fields
 * are deliberately isolated here, and restart/boundary tests cover restoration.
 */
export class MediaDigest extends _SHA256 {
  static restore(snapshot: string): MediaDigest {
    const { state, length, tail } = JSON.parse(snapshot) as { state: number[]; length: number; tail: number[] };
    if (!Array.isArray(state) || state.length !== 8 || !state.every(Number.isInteger) ||
        !Number.isSafeInteger(length) || length < 0 || !Array.isArray(tail) || tail.length !== length % 64 ||
        !tail.every((n) => Number.isInteger(n) && n >= 0 && n <= 255)) throw new Error("Invalid media digest checkpoint");
    const hash = new MediaDigest();
    hash.set(state[0], state[1], state[2], state[3], state[4], state[5], state[6], state[7]);
    hash.length = length;
    hash.pos = tail.length;
    hash.buffer.set(tail);
    return hash;
  }

  checkpoint(): string {
    return JSON.stringify({ state: this.get(), length: this.length, tail: [...this.buffer.subarray(0, this.pos)] });
  }

  hex(): string {
    return [...this.digest()].map((b) => b.toString(16).padStart(2, "0")).join("");
  }
}
