// SyncCS GitHub OAuth PKCE callback and single-use app handoff.
// No GitHub client secret or Supabase service_role credential is ever sent to the app.
const PROJECT = Deno.env.get("SUPABASE_URL")!;
const SERVICE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const TABLE = PROJECT + "/rest/v1/synccs_oauth_pending";
const secureHeaders = {
  "cache-control": "no-store, private",
  "referrer-policy": "no-referrer",
  "x-content-type-options": "nosniff",
  "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
};

function json(value: unknown, status = 200): Response {
  return new Response(JSON.stringify(value), {
    status, headers: { ...secureHeaders, "content-type": "application/json; charset=utf-8" },
  });
}
// Supabase-hosted Edge Functions intentionally rewrite text/html to text/plain.
// Return an actual plaintext completion notice rather than raw HTML markup.
function notice(title: string, description: string, status = 200): Response {
  return new Response(title + "\n\n" + description + "\n", {
    status,
    headers: { ...secureHeaders, "content-type": "text/plain; charset=utf-8" },
  });
}
function validNonce(v: unknown): v is string {
  return typeof v === "string" && /^[A-Za-z0-9_-]{35,90}$/.test(v);
}
async function hashNonce(nonce: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(nonce));
  return Array.from(new Uint8Array(bytes)).map(x => x.toString(16).padStart(2, "0")).join("");
}
async function database(path: string, method: string, body?: unknown) {
  const response = await fetch(TABLE + path, {
    method,
    headers: {
      apikey: SERVICE_KEY,
      authorization: "Bearer " + SERVICE_KEY,
      accept: "application/json",
      ...(body !== undefined ? { "content-type": "application/json", prefer: "resolution=merge-duplicates,return=minimal" } : {}),
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  });
  if (!response.ok) throw Error("Database service unavailable");
  return response;
}

Deno.serve(async (request: Request) => {
  try {
    const url = new URL(request.url);
    if (request.method === "GET") {
      const nonce = url.pathname.split("/").filter(Boolean).pop();
      const code = url.searchParams.get("code");
      const error = url.searchParams.get("error");
      if (error) {
        return notice("GitHub sign-in was cancelled or failed.", "Return to CloudStream > SyncCS and try again.", 400);
      }
      if (!validNonce(nonce) || !code || code.length > 2048) {
        return notice("Invalid GitHub sign-in request.", "Return to CloudStream > SyncCS and try again.", 400);
      }
      // Only the one-time state hash is visible to the database. Never send a code into HTML/URL.
      const hashed = await hashNonce(nonce);
      await database("?on_conflict=nonce_hash", "POST", [{
        nonce_hash: hashed, auth_code: code,
        expires_at: new Date(Date.now() + 5 * 60000).toISOString(),
      }]);
      // Android Auth Tab intercepts this private scheme and closes itself,
      // returning to the caller without requiring a CloudStream manifest edit.
      if (url.pathname.includes("/auth-tab/")) {
        return new Response(null, {
          status: 302,
          headers: {
            ...secureHeaders,
            location: "synccs://login-complete?state=" + encodeURIComponent(nonce),
          },
        });
      }
      return notice("GitHub sign-in complete.", "Return to CloudStream > SyncCS. The sign-in will finish automatically. You can close this browser tab.");
    }
    if (request.method !== "POST") {
      return new Response("Method not allowed", { status: 405, headers: { ...secureHeaders, allow: "GET, POST" } });
    }
    const incoming = await request.json().catch(() => null);
    if (!incoming || !validNonce(incoming.state)) return json({ error: "Invalid login state" }, 400);
    const hashed = await hashNonce(incoming.state);
    const search = "?select=auth_code,expires_at&nonce_hash=eq." + hashed + "&limit=1";
    const response = await database(search, "GET");
    const rows = await response.json() as { auth_code: string; expires_at: string }[];
    if (rows.length === 0) return json({ status: "pending" });
    const row = rows[0];
    await database("?nonce_hash=eq." + hashed, "DELETE");
    if (Date.parse(row.expires_at) < Date.now()) return json({ status: "expired" }, 410);
    return json({ status: "approved", code: row.auth_code });
  } catch (e) {
    console.error("OAuth relay failure:", e instanceof Error ? e.name : "unknown");
    return json({ error: "Temporary login service failure" }, 503);
  }
});
