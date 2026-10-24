import { generateKeyPairSync } from "node:crypto";
import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";
import { ALLOWED, testPubkeys } from "./test/identities";

// A throwaway Firebase service account: tests check the signed token against its public key, and no
// request leaves the runtime (the mailbox's push fetch is replaced in every push test).
const fcm = generateKeyPairSync("rsa", {
  modulusLength: 2048,
  privateKeyEncoding: { type: "pkcs8", format: "pem" },
  publicKeyEncoding: { type: "spki", format: "pem" },
});

export default defineConfig({
  test: {
    setupFiles: ["./test/setup.ts"],
    // Files share the single mailbox object, so they run one after another.
    fileParallelism: false,
  },
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        // Small caps so the limit tests stay fast; the defaults are checked separately.
        bindings: {
          ALLOWED_PUBKEYS: testPubkeys(ALLOWED).join(","),
          EXPOSE_METRICS: "1",
          MAX_ENVELOPE_BYTES: "8192",
          MAX_PENDING_BYTES_PER_RECIPIENT: "24576",
          MAX_PENDING_BYTES_TOTAL: "49152",
          MAX_PAGE_BYTES: "12288",
          MAX_PAGE_MESSAGES: "20",
          // Small media limits so chunking and the size cap are exercised without huge buffers.
          MAX_MEDIA_BYTES: "65536",
          MEDIA_CHUNK_BYTES: "16384",
          MAX_MEDIA_RECIPIENTS: "4",
          SIGNALS_PER_MINUTE: "5",
          // Small cap so the link-proof guessing test stays fast; the default is checked in limits.test.ts.
          LINK_ATTEMPTS_PER_MINUTE: "5",
          FCM_SERVICE_ACCOUNT: JSON.stringify({
            project_id: "relay-test",
            client_email: "push@relay-test.iam.gserviceaccount.com",
            private_key: fcm.privateKey,
            token_uri: "https://oauth2.googleapis.com/token",
          }),
          TEST_FCM_PUBLIC_KEY: fcm.publicKey,
        },
      },
    }),
  ],
});
