/**
 * "Wake up" pushes through Firebase Cloud Messaging (Tadej, 2026-10-18), so a phone whose app is fully
 * closed fetches a new message promptly. The push carries no content and no sender: only
 * `{"type":"wake"}`. The phone then connects and catches up as usual. Google learns that this phone
 * received a push from this app, and when.
 *
 * Credentials: the Firebase service account JSON as the Worker secret FCM_SERVICE_ACCOUNT. Without it,
 * pushes are simply off.
 */

export interface ServiceAccount {
  project_id: string;
  client_email: string;
  private_key: string;
  token_uri: string;
}

export type WakeResult = "sent" | "unregistered" | "failed";
type Fetch = (request: Request) => Promise<Response>;

const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

export function serviceAccountFrom(raw: unknown): ServiceAccount | null {
  if (typeof raw !== "string" || raw.trim() === "") return null;
  try {
    const v = JSON.parse(raw);
    if (typeof v.project_id !== "string" || typeof v.client_email !== "string" || typeof v.private_key !== "string") return null;
    return { project_id: v.project_id, client_email: v.client_email, private_key: v.private_key, token_uri: v.token_uri ?? "https://oauth2.googleapis.com/token" };
  } catch {
    return null;
  }
}

const b64url = (bytes: Uint8Array | string) => {
  const raw = typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes;
  return btoa(String.fromCharCode(...raw)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

function pemToDer(pem: string): ArrayBuffer {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s+/g, "");
  return Uint8Array.from(atob(body), (c) => c.charCodeAt(0)).buffer;
}

export class PushSender {
  private readonly account: ServiceAccount;
  private readonly fetcher: Fetch;
  private access: { token: string; expiresAt: number } | null = null;

  constructor(account: ServiceAccount, fetcher: Fetch) {
    this.account = account;
    this.fetcher = fetcher;
  }

  /** Sends one content-free wake-up to [deviceToken]. "unregistered" means the token is dead: forget it. */
  async wake(deviceToken: string): Promise<WakeResult> {
    try {
      const response = await this.fetcher(new Request(`https://fcm.googleapis.com/v1/projects/${this.account.project_id}/messages:send`, {
        method: "POST",
        headers: { Authorization: `Bearer ${await this.accessToken()}`, "content-type": "application/json" },
        body: JSON.stringify({
          message: {
            token: deviceToken,
            data: { type: "wake" },
            // High priority so Android lets the app run briefly; collapsing keeps only the latest wake.
            android: { priority: "high", ttl: "86400s", collapse_key: "relay-wake" },
          },
        }),
      }));
      if (response.ok) return "sent";
      const body = await response.json().catch(() => null) as { error?: { details?: Array<{ "@type"?: string; errorCode?: string }> } } | null;
      if (body?.error?.details?.some((detail) => detail["@type"] === "type.googleapis.com/google.firebase.fcm.v1.FcmError" && detail.errorCode === "UNREGISTERED")) return "unregistered";
      if (response.status === 401) this.access = null;
      return "failed";
    } catch {
      return "failed";
    }
  }

  /** OAuth2 access token from a JWT signed with the service account key, reused until shortly before it expires. */
  private async accessToken(): Promise<string> {
    const now = Math.floor(Date.now() / 1000);
    if (this.access && this.access.expiresAt - 60 > now) return this.access.token;
    const header = b64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
    const claims = b64url(JSON.stringify({ iss: this.account.client_email, scope: SCOPE, aud: this.account.token_uri, iat: now, exp: now + 3600 }));
    const key = await crypto.subtle.importKey("pkcs8", pemToDer(this.account.private_key), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
    const signature = new Uint8Array(await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(`${header}.${claims}`)));
    const response = await this.fetcher(new Request(this.account.token_uri, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: `${header}.${claims}.${b64url(signature)}` }),
    }));
    if (!response.ok) throw new Error(`token request failed: ${response.status}`);
    const body = (await response.json()) as { access_token: string; expires_in?: number };
    this.access = { token: body.access_token, expiresAt: now + (body.expires_in ?? 3600) };
    return body.access_token;
  }
}
